package com.h6n.launcher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.H6NApplication
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开机任务：**开机后按「延迟秒数 + 动作」逐条执行**，最典型的用法是自动打开某个应用。
 *
 * 对齐车机「应用管家」的 `BootReceiver` + `BootTaskService`：
 * 开机广播 → 起任务 → 按配置的延迟一条条执行 → 执行完退出。
 *
 * ## 与「应用管家」的两点不同
 * 1. **不起前台服务**：桌面本身就是 HOME 进程，开机必起、系统不会随手回收，
 *    再挂一条常驻通知没有收益（导航悬浮窗也是这个思路，见 [NavInfoOverlay]）。
 * 2. **多一道「刚开机」判定**：有些车机固件把 `BOOT_COMPLETED` 发得很晚，
 *    甚至根本不给第三方应用发；所以除了广播，桌面回到前台时也会查一次
 *    `SystemClock.elapsedRealtime()`，开机 3 分钟内且有任务没跑过就补跑。
 *
 * ## 防重复
 * 一次开机只跑一次：`SharedPreferences("h6_boot_task").last_run` 记毫秒时间戳，
 * [MIN_GAP_MS] 内再次触发直接跳过（广播 + 桌面补跑两条路都可能到达）。
 *
 * ## 存储
 * `tasks` 键里是 JSON 数组：`[{"d":5,"a":"app:com.autonavi.amapauto/.MainActivity"}]`。
 * 动作语法与 [AutoActions] 完全一致，所以开机任务除了开应用，也能「回桌面」「展开状态栏」。
 */
object BootTask {

    private const val TAG = "H6BootTask"
    private const val SP = "h6_boot_task"
    private const val K_TASKS = "tasks"
    private const val K_LAST = "last_run"
    private const val K_RESULT = "last_result"

    /** 同一个开机周期内两次执行的最小间隔。 */
    private const val MIN_GAP_MS = 5 * 60 * 1000L

    /** 开机后多久内算「刚开机」（桌面回前台的补跑窗口）。 */
    private const val BOOT_WINDOW_MS = 3 * 60 * 1000L

    /** 延迟下限：0 = 开机广播一到就执行。 */
    const val MIN_DELAY = 0

    /** 延迟上限：1 小时。再长就不像「开机任务」了，重启后也等不满。 */
    const val MAX_DELAY = 3600

    /** 一条任务。 */
    data class Task(val delaySec: Int, val action: String)

    /** 把任意输入夹到 [MIN_DELAY]..[MAX_DELAY]。 */
    fun clampDelay(sec: Int): Int = sec.coerceIn(MIN_DELAY, MAX_DELAY)

    /** 秒数的人话版本：`0` → 立刻，`90` → 1 分 30 秒。 */
    fun secText(sec: Int): String {
        val s = clampDelay(sec)
        if (s == 0) return "立刻"
        if (s < 60) return "$s 秒"
        val m = s / 60
        val r = s % 60
        return if (r == 0) "$m 分" else "$m 分 $r 秒"
    }

    private val MAIN = Handler(Looper.getMainLooper())
    private val LOCK = Any()
    private var cache: List<Task>? = null
    private var running = false

    // ------------------------------------------------------------------ 读写

    /** 配置备份恢复后：丢掉内存缓存，下次 [tasks] 直接读刚写回的 SP。 */
    fun reload() {
        synchronized(LOCK) { cache = null }
    }

    fun tasks(ctx: Context): List<Task> {
        cache?.let { return it }
        val out = ArrayList<Task>(6)
        runCatching {
            val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_TASKS, "") ?: ""
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val a = o.optString("a", "")
                    if (a.isEmpty()) continue
                    out += Task(clampDelay(o.optInt("d", 5)), a)
                }
            }
        }
        cache = out
        return out
    }

    fun save(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        for (t in list) arr.put(JSONObject().put("d", clampDelay(t.delaySec)).put("a", t.action))
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_TASKS, arr.toString()).apply()
        cache = ArrayList(list)
    }

    fun add(ctx: Context, t: Task) = save(ctx, tasks(ctx) + t)

    fun removeAt(ctx: Context, index: Int) {
        val list = ArrayList(tasks(ctx))
        if (index in list.indices) list.removeAt(index)
        save(ctx, list)
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    fun enabled(ctx: Context): Boolean =
        (ctx.applicationContext as? H6NApplication)?.config?.current()?.bool("auto.bootOn", false) ?: false

    fun lastResult(ctx: Context): String = runCatching {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_RESULT, "").orEmpty()
    }.getOrDefault("")

    // ------------------------------------------------------------------ 执行

    /**
     * 开机触发（[BootReceiver] 调，桌面回到前台时也会补一次）。
     *
     * @param reason 写进结果日志，便于车上排查「这次到底跑没跑」
     */
    fun runBoot(ctx: Context, reason: String) {
        val app = ctx.applicationContext ?: ctx
        if (!enabled(app)) return
        val list = tasks(app)
        if (list.isEmpty()) return
        val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        synchronized(LOCK) {
            if (running) return
            val last = sp.getLong(K_LAST, 0L)
            if (last > 0 && now - last < MIN_GAP_MS) {
                Log.i(TAG, "跳过：${(now - last) / 1000}s 前刚跑过")
                return
            }
            sp.edit().putLong(K_LAST, now).apply()
        }
        execute(app, list, reason)
    }

    /** 设置页「立即试跑」：无视间隔限制，延迟与真开机时一致。 */
    fun runNow(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val list = tasks(app)
        if (list.isEmpty()) return
        execute(app, list, "手动试跑")
    }

    private fun execute(app: Context, list: List<Task>, reason: String) {
        synchronized(LOCK) {
            if (running) return
            running = true
        }
        mark(app, reason + "：开始执行 " + list.size + " 条")
        Log.i(TAG, "开始执行 ${list.size} 条任务（$reason）")
        var done = 0
        val total = list.size
        for (t in list) {
            MAIN.postDelayed({
                val msg = AutoActions.perform(app, t.action)
                done++
                mark(app, "第 $done/$total 条 · ${t.delaySec}s · " + AutoActions.labelWithArg(app, t.action) +
                    if (msg.isEmpty()) "" else " · " + msg)
                if (done >= total) synchronized(LOCK) { running = false }
            }, t.delaySec * 1000L)
        }
    }

    /**
     * 桌面回到前台时调：只在「刚开机」窗口内补跑一次。
     *
     * 为什么不每次都跑：桌面是 HOME，用户从应用退回桌面也会走 onResume，
     * 那样每退一次就重开一遍应用，体验直接崩掉。
     */
    fun onHomeReady(ctx: Context) {
        if (!enabled(ctx)) return
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        runBoot(ctx, "开机后回到桌面")
    }

    private fun mark(ctx: Context, text: String) {
        val hm = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runCatching {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(K_RESULT, "$hm  $text").apply()
        }
    }

    /** 列表显示文案：「5 秒后 · 打开 高德地图」。 */
    fun describe(ctx: Context, t: Task): String =
        secText(t.delaySec) + "后 · " + AutoActions.labelWithArg(ctx, t.action)
}
