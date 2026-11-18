package com.h6n.launcher.core

import android.os.SystemClock
import android.view.Choreographer
import java.util.Locale

/**
 * 运行时性能读数（v6.0.45）。
 *
 * 「长时间运行内存检查」在车机上没法靠 adb 蹲点（无线调试经常连不上），
 * 所以把三件事做成设置页可自读的一行：**堆占用 / 线程数 / 进程运行时长**，
 * 再给一个按需的 3 秒帧率探针。
 *
 * 设计约束：
 * - 帧率采样**不常驻** —— 只在用户点「测帧率」时注册 Choreographer 回调，
 *   3 秒后自动摘掉。常驻采样会让静止桌面也每帧被唤醒，白耗电。
 * - [threadCount] 会抓全栈（`getAllStackTraces`），比较贵，只在点开对话框时调。
 */
object PerfStats {

    /** 由 [com.h6n.launcher.H6NApplication.onCreate] 打点，用来算进程运行时长。 */
    private var startedAt = 0L

    fun attach() {
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
    }

    /** `堆 18.2MB / 192MB` —— Dalvik 堆口径，和 Android Studio 的 Java 一致。 */
    fun memLine(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()).toFloat() / 1048576f
        val maxMb = rt.maxMemory().toFloat() / 1048576f
        return "堆 " + String.format(Locale.CHINA, "%.1f", usedMb) +
            "MB / " + String.format(Locale.CHINA, "%.0f", maxMb) + "MB"
    }

    /** 进程线程数；`-1` 表示读取失败（个别 ROM 会限制）。 */
    fun threadCount(): Int = try {
        Thread.getAllStackTraces().size
    } catch (t: Throwable) {
        -1
    }

    /** `2h13m` / `48m` / `37s`。 */
    fun uptime(): String {
        if (startedAt == 0L) return "--"
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000L
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /** 一行汇总，设置页直接显示。 */
    fun summary(): String = memLine() + " \u00b7 \u7ebf\u7a0b " + threadCount() + " \u00b7 \u8fd0\u884c " + uptime()

    /**
     * 采样 [ms] 毫秒的真实出帧率。
     *
     * 必须在主线程调（`Choreographer.getInstance()` 依赖当前线程的 Looper）。
     * 桌面静止、没有任何动画时 Choreographer 不会有回调，此时返回 0（表示「没在画」），
     * 而不是 0fps —— 两者含义完全不同，别混着看。
     */
    fun probeFrames(ms: Long, done: (Int) -> Unit) {
        val ch = Choreographer.getInstance()
        val endAt = SystemClock.elapsedRealtime() + ms
        var frames = 0
        var firstNs = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (firstNs == 0L) firstNs = frameTimeNanos
                frames++
                if (SystemClock.elapsedRealtime() < endAt) {
                    ch.postFrameCallback(this)
                    return
                }
                val spanMs = (frameTimeNanos - firstNs) / 1_000_000f
                val fps = if (frames > 1 && spanMs > 0f) ((frames - 1) * 1000f / spanMs).toInt() else 0
                done(fps)
            }
        }
        ch.postFrameCallback(cb)
    }
}
