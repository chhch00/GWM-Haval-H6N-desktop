package com.h6n.launcher.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast

/**
 * 方向盘按键 → 驾驶模式切换的「键值自学 + 长按映射」。
 *
 * ## 为什么要自己检测键值
 * 哈弗 H6 国潮版的方向盘按键走车厂私有 CAN → 系统注入的 KeyEvent，
 * 键码既不是标准的 KEYCODE_MEDIA_*，不同批次固件也可能不一样。硬编码键值
 * 一定会对不上，所以照「应用管家」按键映射的做法（反编译
 * `com/yunpan/appmanage/service/AccService.smali` 的 `onKeyEvent`）：
 *  - 开一个「学习模式」，用户长按一下方向盘键；
 *  - 无障碍服务在 `onKeyEvent` 里读 `KeyEvent.getKeyCode()`，
 *    广播 `<包名>.STEER_KEY_DETECTED`（extra: keyCode / action / repeat）给设置页面板显示；
 *  - 学习期间把这个键吞掉（return true），免得学的时候顺带切了歌 / 唤起语音；
 *  - 用户确认后把 keyCode 存进 SP，之后只在匹配这个键、且**按住够久**时触发切换。
 *
 * ## 为什么按「按住时长」而不是系统的 isLongPress
 * 车机 CAN 注入的 KeyEvent 经常没有 `FLAG_LONG_PRESS`，repeat 事件也未必连发，
 * 有的固件甚至只在抬键时补一个事件。所以这里完全用自己的单调时钟计时：
 *  - `ACTION_DOWN`（repeatCount=0）：记下起点，安排一个 [holdMs] 后的定时器；
 *  - 定时器到点且手指还按着 → 触发一次，**一次按压只切一次**（pressFired）；
 *  - 期间若收到 repeat 事件 / 抬键事件，用「本地已过时长 ≥ holdMs」补触发一次，
 *    防止主线程卡住导致定时器迟到；
 *  - 短按（不到 holdMs 就抬键）→ 取消定时器，**不切换**。
 * 这样不依赖固件填的 downTime / eventTime，也不依赖系统有没有标 longPress。
 *
 * ## 两条按键入口
 * 1. [com.h6n.launcher.ui.main.H6A11yService.onKeyEvent] —— 全局，
 *    开了无障碍服务后，在导航 / 音乐 / 桌面上都能按；
 * 2. `ScaledActivity.dispatchKeyEvent` —— 桌面与设置页的前台兜底，
 *    没开无障碍时照样能用（学习面板就跑在设置页里，必须走这条）。
 *
 * 两条入口都调 [onKey]；同一个 downTime 的重复 DOWN 只认第一个。
 * 命中绑定键时 DOWN / repeat / UP 一律吞掉，避免长按计时期间原车功能被触发。
 */
object SteerKey {

    private const val SP = "h6_steer_key"
    private const val K_CODE = "key_code"          // 绑定的键码，-1 = 未绑定
    private const val K_ON = "enabled"             // 总开关
    private const val K_HOLD = "hold_ms"           // 长按判定时长
    private const val K_LAST = "last_sent"         // 上次下发的目标模式（读不到车况时用来判断方向）
    private const val K_RESULT = "last_result"     // 上次切换结果（设置页诊断用）
    private const val K_AT = "last_at"             // 上次切换时间

    /** 学习模式下广播检测到的键值；设置页面板动态注册接收。 */
    const val ACTION_DETECTED = "com.h6n.launcher.STEER_KEY_DETECTED"
    const val EXTRA_CODE = "keyCode"
    const val EXTRA_ACTION = "action"
    const val EXTRA_REPEAT = "repeat"

    /** 可选的长按时长（毫秒）：车机按键手感差异大，设置页点一下循环切换。 */
    val HOLD_OPTIONS = longArrayOf(500L, 800L, 1200L)
    private const val DEFAULT_HOLD_MS = 800L

    /** 两次切换之间的最小间隔：只用来挡两条入口的重复投递，不参与长按判定。 */
    private const val DEBOUNCE_MS = 350L

    private val MAIN = Handler(Looper.getMainLooper())
    private val LOCK = Any()

    /** 是否处于「按键学习」状态（进程内，不落盘：进程被杀后自动退出学习）。 */
    @Volatile
    var learning: Boolean = false
        private set

    /** 学习期间最近按下的键码（只记 ACTION_DOWN，最多 6 条）。 */
    @Volatile
    var detected: Int = -1
        private set

    /** 学习期间最近几次按键，给面板显示「刚才按了哪几个」。 */
    private val history = ArrayList<Int>()

    // 一次按压的状态机（全部在 LOCK 内读写）
    private var pressActive = false
    private var pressFired = false
    private var pressStart = 0L
    private var pressDownTime = -1L
    private var pending: Runnable? = null
    private var lastToggleAt = 0L

    // ------------------------------------------------------------------ 配置

    fun keyCode(ctx: Context): Int = runCatching {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(K_CODE, -1)
    }.getOrDefault(-1)

    fun enabled(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(K_ON, true)
    }.getOrDefault(true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()
        if (!on) synchronized(LOCK) { cancelPending(); pressActive = false; pressFired = false }
    }

    fun bind(ctx: Context, code: Int) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt(K_CODE, code)
            .putBoolean(K_ON, true)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putInt(K_CODE, -1).apply()
        synchronized(LOCK) { cancelPending(); pressActive = false; pressFired = false }
    }

    /** 当前长按判定时长，毫秒。 */
    fun holdMs(ctx: Context): Long = runCatching {
        val v = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getLong(K_HOLD, DEFAULT_HOLD_MS)
        if (v in HOLD_OPTIONS) v else DEFAULT_HOLD_MS
    }.getOrDefault(DEFAULT_HOLD_MS)

    /** 设置页显示用，如「0.8 秒」。 */
    fun holdLabel(ctx: Context): String = "%.1f 秒".format(holdMs(ctx) / 1000f)

    /** 点一下切到下一档，返回新值。 */
    fun cycleHoldMs(ctx: Context): Long {
        val cur = holdMs(ctx)
        val i = HOLD_OPTIONS.indexOf(cur).let { if (it < 0) 1 else it }
        val next = HOLD_OPTIONS[(i + 1) % HOLD_OPTIONS.size]
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putLong(K_HOLD, next).apply()
        return next
    }

    /**
     * 上次下发的目标模式。旧版存的是错误值域（0/1/6），这里做一次合法化：
     * 不在 1..10 就退回标准，避免第一次长按算出莫名其妙的目标。
     */
    fun lastSent(ctx: Context): Int = runCatching {
        val v = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt(K_LAST, SteerMode.STANDARD)
        if (v in 1..10) v else SteerMode.STANDARD
    }.getOrDefault(SteerMode.STANDARD)

    internal fun setLastSent(ctx: Context, mode: Int) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putInt(K_LAST, mode).apply()
    }

    /** 最近一次按键切换的结果（含时间），设置页状态行直接显示。 */
    fun lastResult(ctx: Context): String = runCatching {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val r = sp.getString(K_RESULT, "").orEmpty()
        val at = sp.getLong(K_AT, 0L)
        if (r.isEmpty() || at == 0L) "还没有长按过绑定的键"
        else {
            val hm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(at))
            "$hm  $r"
        }
    }.getOrDefault("—")

    fun noteResult(ctx: Context, text: String) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_RESULT, text)
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------------ 学习

    fun beginLearn() {
        synchronized(LOCK) {
            learning = true
            detected = -1
            synchronized(history) { history.clear() }
            cancelPending()
            pressActive = false
            pressFired = false
        }
    }

    fun endLearn() {
        synchronized(LOCK) {
            learning = false
            cancelPending()
            pressActive = false
            pressFired = false
        }
    }

    fun recent(): List<Int> = synchronized(history) { history.toList() }

    /** 键码显示名：车机自定义键码 keyCodeToString 会给 UNKNOWN，就把数字露出来。 */
    fun keyLabel(code: Int): String {
        if (code < 0) return "未绑定"
        val n = runCatching { KeyEvent.keyCodeToString(code) }.getOrDefault("")
        return if (n.isBlank() || n == "KEYCODE_UNKNOWN") "自定义键 $code" else "$n ($code)"
    }

    // ------------------------------------------------------------------ 分发

    /**
     * 两个按键入口统一走这里。
     *
     * @return true = 这个按键已被我们消费，不要再传给系统（学习模式 / 命中绑定键）
     */
    fun onKey(ctx: Context, ev: KeyEvent?): Boolean = synchronized(LOCK) {
        if (ev == null) return false
        val code = ev.keyCode
        val down = ev.action == KeyEvent.ACTION_DOWN

        // HOME / BACK 永远放行：学习面板开着时不能把用户困在对话框里
        val sysKey = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME
        if (sysKey) return false

        // 学习态：所有非系统键都吞掉，DOWN 记键值；长按期间若固件换了键码也记进历史
        if (learning) {
            if (down) {
                if (ev.repeatCount == 0) {
                    detected = code
                    addHistory(code)
                    broadcast(ctx, code, ev.action, 0)
                } else {
                    addHistory(code)
                    broadcast(ctx, code, ev.action, ev.repeatCount)
                }
            }
            return true
        }

        val bound = keyCode(ctx)
        if (!enabled(ctx) || bound < 0 || code != bound) return false

        val need = holdMs(ctx)
        val held = SystemClock.uptimeMillis() - pressStart

        if (!down) {
            // 抬键：短按直接取消，什么也不做；定时器被卡住时按本地时长补一次
            cancelPending()
            val wasActive = pressActive
            if (wasActive && !pressFired && held >= need) fire(ctx)
            pressActive = false
            pressFired = false
            return true
        }

        if (ev.repeatCount > 0) {
            // 长按连发：只吞掉，绝不因为 repeat 再切一次
            if (pressActive && !pressFired && held >= need) {
                cancelPending()
                fire(ctx)
            }
            return true
        }

        // 两条入口可能收到同一次按压，只认第一个 DOWN
        if (pressActive && ev.downTime == pressDownTime) return true

        pressActive = true
        pressFired = false
        pressStart = SystemClock.uptimeMillis()
        pressDownTime = ev.downTime
        cancelPending()

        // 少数固件不连发 repeat，而是补一个「已经按住很久」的 DOWN：
        // 系统自己能证明按够了就直接切；其余一律走本地定时器。
        // 过长的时间差（注入事件把 downTime 填 0）视为无效，别把短按误判成长按。
        val nativeHeld = ev.eventTime - ev.downTime
        val nativeLong = ev.isLongPress || nativeHeld in need..5000L
        if (nativeLong) {
            fire(ctx)
            return true
        }

        val task = object : Runnable {
            override fun run() {
                synchronized(LOCK) {
                    if (!pressActive || pressFired) return
                    if (SystemClock.uptimeMillis() - pressStart < need - 20L) return
                    fire(ctx)
                }
            }
        }
        pending = task
        MAIN.postDelayed(task, need)
        return true
    }

    /** 真正下发一次切换；必须在 LOCK 内调用。 */
    private fun fire(ctx: Context) {
        if (pressFired) return
        val now = SystemClock.uptimeMillis()
        if (now - lastToggleAt < DEBOUNCE_MS) return
        pressFired = true
        lastToggleAt = now
        cancelPending()
        val app = ctx.applicationContext
        // Binder transact 是同步的，不能压在无障碍服务 / UI 线程上
        Thread {
            val msg = SteerMode.toggle(app)
            MAIN.post {
                runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun cancelPending() {
        pending?.let { MAIN.removeCallbacks(it) }
        pending = null
    }

    private fun addHistory(code: Int) {
        synchronized(history) {
            history.add(0, code)
            while (history.size > 6) history.removeAt(history.size - 1)
        }
    }

    private fun broadcast(ctx: Context, code: Int, action: Int, repeat: Int) {
        runCatching {
            val i = Intent(ACTION_DETECTED).setPackage(ctx.packageName)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_REPEAT, repeat)
            ctx.sendBroadcast(i)
        }
    }
}

/**
 * 方向盘按键的动作：**长按后只在标准 与 运动 之间直接切换**。
 *
 * 写通道完全复用旧版 `VehicleData.setDriveModeAsync` 的两条（见
 * [CarBinderSource.setDriveMode]）：PlatformAdapter transact 5 +
 * ICarInfo CARSETTINGS(0x0A) item 0x25。没有新造协议。
 *
 * ★ 这里用的是**语义值**（[CarBinderSource.MODE_STANDARD] 等），
 *   真正写进车机的值由 [CarBinderSource.wireOf] 换算 —— 车型值域不同，
 *   换算集中在一处，这里不要自己拼数字。6.0.33 修「点标准进经济」那批错位，
 *   长按切换跟着一起正了。
 *
 * 当前是经济 / 读不到车况(-1) 时，用上次下发的目标模式继续左右横跳，
 * 不会出现「读不到模式 → 每次都切到同一个模式」看起来没反应的情况。
 */
object SteerMode {

    /** 与 [CarBinderSource.MODE_STANDARD] 同值，单独列出便于阅读。 */
    const val STANDARD = CarBinderSource.MODE_STANDARD
    const val SPORT = CarBinderSource.MODE_SPORT

    fun modeName(mode: Int): String = CarBinderSource.driveModeName(mode)

    /** @return 给用户看的结果文案（同时写进设置页诊断行）。 */
    fun toggle(ctx: Context): String {
        val cur = SignalHub.current().driveMode
        val eff = if (cur == STANDARD || cur == SPORT) cur else SteerKey.lastSent(ctx)
        val target = if (eff == SPORT) STANDARD else SPORT

        val ok = runCatching { CarBinderSource.setDriveMode(target) }.getOrDefault(false)
        if (ok) SteerKey.setLastSent(ctx, target)
        val msg = if (ok) "方向盘长按 → 已切到「${modeName(target)}」"
        else "驾驶模式通道没连上（车机上才会生效）"
        SteerKey.noteResult(ctx, msg)
        return msg
    }
}
