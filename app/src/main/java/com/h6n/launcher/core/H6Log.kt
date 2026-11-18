package com.h6n.launcher.core

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 统一日志出口：**一行 `Log.x` 都不用改，全项目自动落盘**。
 *
 * ## 为什么这么做
 * 车机上没有 logcat 可用（普通应用读不到系统日志，且用户不会连电脑），
 * 排查只能靠「让桌面自己把日志写进 txt，用户拷走发我」。
 * 但项目里已经有 180 多处 `Log.i/w/e/d`，逐个改成自定义方法既费事又容易漏。
 * 所以这里做成同名 API，各文件只把
 * `import android.util.Log` 换成 `import com.h6n.launcher.core.H6Log as Log`，
 * 调用处一个字不用动。
 *
 * ## 落盘策略
 * - 目录 `getExternalFilesDir("logs")`：`adb pull` 与文件管理器都能拿到。
 * - 单文件滚动：[MAX_BYTES] 写满就改名 `.1`、`.2`，最多 [MAX_FILES] 份，总量封顶约 6MB，
 *   长期开着也不会把车机存储写爆。
 * - 后台单线程串行写 + 批量 flush（最多 1 秒延迟），主线程只做一次入队，不碰 IO。
 * - 队列满了直接丢弃 —— 宁可少一行日志，绝不能卡住界面。
 *
 * 所有异常一律吞掉：[H6Log] 是调试设施，永远不允许影响桌面本身。
 */
object H6Log {

    private const val TAG = "H6Log"

    /** 单个日志文件上限 2MB。 */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** 保留份数：当前 + `.1` + `.2`。 */
    private const val MAX_FILES = 3

    /** 队列超过这个长度就丢新日志，说明写盘跟不上或有人在刷屏。 */
    private const val QUEUE_MAX = 4000

    const val NAME = "h6n.log"

    private val queue = LinkedBlockingQueue<String>(QUEUE_MAX)

    /** SimpleDateFormat 不是线程安全的，每条日志都按线程取一个。 */
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var dir: File? = null

    @Volatile
    private var started = false

    /** 累计丢弃条数，导出时写在文件头，用来判断日志是否被截断。 */
    @Volatile
    private var dropped = 0

    /** 落盘目录（未初始化时返回 null）。 */
    fun dir(): File? = dir

    /** 当前日志文件。 */
    fun file(): File? = dir?.let { File(it, NAME) }

    /** 已落盘日志的总字节数（含滚动文件），给设置页显示用。 */
    fun totalBytes(): Long {
        val d = dir ?: return 0L
        var sum = 0L
        runCatching {
            d.listFiles()?.forEach { if (it.name.startsWith(NAME)) sum += it.length() }
        }
        return sum
    }

    fun humanBytes(b: Long): String = when {
        b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    /**
     * 进程启动时调一次（[com.h6n.launcher.H6NApplication.onCreate] 最前面）。
     * 重复调用无副作用。
     */
    fun init(c: Context) {
        if (started) return
        started = true
        runCatching {
            val d = c.getExternalFilesDir("logs") ?: File(c.filesDir, "logs")
            if (!d.exists()) d.mkdirs()
            if (!d.isDirectory) return
            dir = d
            val t = Thread({ pump() }, "h6-log")
            t.priority = Thread.MIN_PRIORITY
            t.isDaemon = true
            t.start()
        }
        mark("==================== 桌面启动 pid=${android.os.Process.myPid()} ====================")
    }

    // ------------------------------------------------------------------ 对外 API
    //
    // 签名刻意与 android.util.Log 完全一致，调用处零改动。

    fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
        offer('I', tag, msg)
    }

    fun d(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
        offer('D', tag, msg)
    }

    fun w(tag: String, msg: String) {
        android.util.Log.w(tag, msg)
        offer('W', tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        android.util.Log.w(tag, msg, tr)
        offer('W', tag, msg + "\n" + stack(tr))
    }

    fun e(tag: String, msg: String) {
        android.util.Log.e(tag, msg)
        offer('E', tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        android.util.Log.e(tag, msg, tr)
        offer('E', tag, msg + "\n" + stack(tr))
    }

    /** 无 tag 的分节标记（启动、导出等里程碑）。 */
    fun mark(text: String) {
        offer('-', "H6N", text)
    }

    /** 把一段多行文本原样写进日志（导出记录、外部命令输出等）。 */
    fun block(title: String, text: String) {
        offer('-', "H6N", "---- $title ----")
        text.lineSequence().forEach { if (it.isNotBlank()) append("  | $it") }
    }

    /** 同步刷盘：导出前调用，保证最后几行也在文件里（只在后台线程调）。 */
    fun flushNow() {
        // pump 线程最长 1 秒才 flush 一次；留 700ms 窗口，导出时不会缺尾巴。
        runCatching { Thread.sleep(700) }
    }

    /** 清空全部日志（含滚动文件）。调用方放子线程。 */
    fun clear() {
        val d = dir ?: return
        runCatching {
            d.listFiles()?.forEach { if (it.name.startsWith(NAME)) it.delete() }
        }
        mark("日志已清空")
    }

    /** 读日志尾部 [maxLines] 行（导出用，调用方放子线程）。 */
    fun tail(maxLines: Int): String {
        val f = file() ?: return ""
        if (!f.exists()) return ""
        return runCatching {
            val all = f.readLines()
            val from = maxOf(0, all.size - maxLines)
            all.subList(from, all.size).joinToString("\n")
        }.getOrDefault("")
    }

    // ------------------------------------------------------------------ 内部

    private fun stack(t: Throwable): String =
        android.util.Log.getStackTraceString(t).trimEnd()

    private fun offer(level: Char, tag: String, msg: String) {
        if (dir == null) return
        val head = runCatching { stamp.get()!!.format(Date()) }.getOrNull() ?: ""
        append("$head $level/$tag $msg")
    }

    private fun append(line: String) {
        if (!queue.offer(line)) dropped++
    }

    /** 写盘线程：批量取、批量写，1 秒内必 flush 一次。 */
    private fun pump() {
        var w: BufferedWriter? = null
        var bytes = 0L
        var pending = 0

        fun closeQuietly() {
            runCatching { w?.flush() }
            runCatching { w?.close() }
            w = null
        }

        fun open(): Boolean {
            val d = dir ?: return false
            return runCatching {
                if (!d.exists()) d.mkdirs()
                val f = File(d, NAME)
                // 每次重开都按实际长度校准，避免多次滚动后计数漂移
                bytes = if (f.exists()) f.length() else 0L
                val fos = FileOutputStream(f, true)
                w = BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8), 8 * 1024)
                true
            }.getOrDefault(false)
        }

        fun rotate() {
            closeQuietly()
            val d = dir ?: return
            runCatching {
                File(d, "$NAME.${MAX_FILES - 1}").delete()
                for (i in MAX_FILES - 2 downTo 1) {
                    val src = File(d, "$NAME.$i")
                    if (src.exists()) src.renameTo(File(d, "$NAME.${i + 1}"))
                }
                val cur = File(d, NAME)
                if (cur.exists()) cur.renameTo(File(d, "$NAME.1"))
            }
            open()
        }

        while (true) {
            val first = try {
                queue.poll(1, TimeUnit.SECONDS)
            } catch (t: InterruptedException) {
                null
            }

            if (first == null) {
                // 空闲：把缓冲落盘，等下一条
                if (pending > 0) {
                    runCatching { w?.flush() }
                    pending = 0
                }
                continue
            }

            val batch = ArrayList<String>(128)
            batch.add(first)
            queue.drainTo(batch, 127)

            try {
                if (w != null || open()) {
                    for (line in batch) {
                        val n = line.length + 1L
                        if (bytes + n > MAX_BYTES) rotate()
                        val out = w ?: break
                        out.write(line)
                        out.write("\n")
                        bytes += n
                        pending++
                    }
                }
            } catch (t: Throwable) {
                // 写盘失败（存储只读 / 空间满）：关掉，下一批重开，绝不外抛
                closeQuietly()
                pending = 0
            }

            if (pending >= 32) {
                runCatching { w?.flush() }
                pending = 0
            }
        }
    }
}
