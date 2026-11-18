package com.h6n.launcher.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import java.io.File

/**
 * 主线程卡死看门狗（旧版 HangGuard 的 Kotlin 等价物）。
 *
 * ## 为什么需要它
 * 桌面是常驻进程，一旦主线程被某个第三方回调或同步 IO 卡住，用户看到的就是
 * "桌面点了没反应"，而且不会产生任何崩溃日志——CrashHandler 只能兜异常，
 * 兜不住"活着但不动"。
 *
 * ## 机制
 * - 主线程每 [BEAT_MS] 喂一次心跳（postDelayed 自循环）；
 * - 独立线程每 [BEAT_MS] 检查一次，超过 [SLOW_MS] 未喂就把当时的线程栈
 *   追加到 `filesDir/hang.txt`，超过 [KILL_MS] 直接自杀让系统重新拉起桌面；
 * - 启动后 [GRACE_MS] 内不判定（冷启动本身可能很慢）；
 * - [WINDOW_MS] 内最多自杀 [MAX_KILLS] 次，避免"开机即卡→自杀→再卡"的死循环
 *   把车机变成无限重启。
 */
object HangWatchdog {

    private const val TAG = "H6Hang"
    private const val BEAT_MS = 1000L
    private const val SLOW_MS = 8_000L
    private const val KILL_MS = 15_000L
    private const val GRACE_MS = 45_000L
    private const val WINDOW_MS = 30 * 60 * 1000L
    private const val MAX_KILLS = 3
    private const val LOG_FILE = "hang.txt"

    @Volatile
    private var lastBeat = 0L

    @Volatile
    private var installed = false

    private val killTimes = ArrayList<Long>(4)

    fun install(app: Application) {
        if (installed) return
        installed = true

        val startedAt = SystemClock.elapsedRealtime()
        lastBeat = startedAt

        val main = Handler(Looper.getMainLooper())
        val beat = object : Runnable {
            override fun run() {
                lastBeat = SystemClock.elapsedRealtime()
                main.postDelayed(this, BEAT_MS)
            }
        }
        main.post(beat)

        val logFile = File(app.filesDir, LOG_FILE)

        val thread = Thread({
            while (true) {
                try {
                    Thread.sleep(BEAT_MS)
                    val now = SystemClock.elapsedRealtime()
                    val silent = now - lastBeat
                    if (now - startedAt < GRACE_MS) continue
                    if (silent < SLOW_MS) continue

                    if (silent >= KILL_MS && allowKill(now)) {
                        dump(logFile, now - startedAt, silent, true)
                        Log.e(TAG, "主线程卡死 ${silent}ms，进程自杀等待系统重启桌面")
                        Process.killProcess(Process.myPid())
                        @Suppress("UNREACHABLE_CODE")
                        System.exit(10)
                    } else if (silent >= SLOW_MS + BEAT_MS) {
                        dump(logFile, now - startedAt, silent, false)
                    }
                } catch (ie: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    // 看门狗自己不能挂
                    Log.w(TAG, "watchdog tick error", t)
                }
            }
        }, "h6-hang-watch")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /** 限流：窗口期内自杀次数不超上限。调用方是看门狗线程，用简单同步即可。 */
    private fun allowKill(now: Long): Boolean {
        synchronized(killTimes) {
            killTimes.removeAll { now - it > WINDOW_MS }
            if (killTimes.size >= MAX_KILLS) {
                Log.w(TAG, "窗口期内已自杀 ${killTimes.size} 次，本次只记录不自杀")
                return false
            }
            killTimes.add(now)
            return true
        }
    }

    /** 追加主线程栈到日志文件，保留最近约 200KB。 */
    private fun dump(file: File, uptimeMs: Long, silentMs: Long, killing: Boolean) {
        try {
            val mainStack = Looper.getMainLooper().thread.stackTrace
                .joinToString("\n    at ") { it.toString() }
            val text = buildString {
                append("=== ").append(if (killing) "HANG-KILL" else "HANG-SLOW")
                append(" uptime=").append(uptimeMs).append("ms")
                append(" silent=").append(silentMs).append("ms ===\n")
                append("thread: ").append(Looper.getMainLooper().thread.name).append('\n')
                append("    at ").append(mainStack).append("\n\n")
            }
            file.appendText(text)
            if (file.length() > 200 * 1024) {
                // 只保留后半段，避免长期运行把分区写满
                val keep = file.readText().takeLast(100 * 1024)
                file.writeText(keep)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写 hang 日志失败", t)
        }
    }
}
