package com.h6n.launcher.core

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.h6n.launcher.H6NApplication
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按键映射：**任意一个物理按键 → 任意一个动作**（单击 / 长按两种触发）。
 *
 * 做法对齐车机「应用管家」的按键映射（`com/yunpan/appmanage` 的 `AccService.onKeyEvent`
 * + `ActivityKeyMapAcc`）：
 *
 * ```
 * 1. 学习：无障碍 onKeyEvent 里读 KeyEvent.getKeyCode()，广播给设置页的面板显示；
 *    学习期间把所有非系统键吞掉（return true），免得"学的时候顺带切了歌"。
 * 2. 绑定：一条绑定 = (键码, 触发方式, 动作)，动作见 [AutoActions]。
 * 3. 运行：命中绑定的键，按触发方式执行动作，并把 DOWN/repeat/UP 全部吞掉，
 *    原车那条键的功能不再触发（这正是用户想要的"改键"）。
 * ```
 *
 * ## 与 [SteerKey] 的关系（重要）
 * [SteerKey] 是 6.0.x 已有的「方向盘长按 → 切驾驶模式」，它自己也有一份键值学习与
 * 长按计时。两者互不合并：
 * - 入口顺序是 **先 [SteerKey] 后 [KeyMap]**，同一个键两边都绑了时 SteerKey 优先；
 * - [KeyMap] 在绑定时会检查，如果这个键已经被 SteerKey 占用，直接拒绝并提示，
 *   避免出现"绑了没反应"的困惑。
 * - 驾驶模式这个动作在 [AutoActions] 里也能选，只是走一次切换，不需要占用键位学习。
 *
 * ## 触发方式为何自己计时
 * 与 [SteerKey] 同一批车机实测结论：车厂 CAN 注入的 KeyEvent 经常不填 `FLAG_LONG_PRESS`，
 * repeat 事件也未必连发。所以用 `SystemClock.uptimeMillis()` 自己算按住时长，
 * 单击 = 抬键时不足 [TAP_MAX_MS]，长按 = 按住满 [HOLD_MS] 立刻触发（一次按压只触发一次）。
 *
 * ## 存储
 * `SharedPreferences("h6_key_map")` 的 `binds` 键里放一个 JSON 数组：
 * ```json
 * [{"code":185,"hold":true,"action":"back"}, ...]
 * ```
 * 键位学习的结果不适合手改，所以不进 `ui_config.json` 配置树；总开关走配置
 * `auto.keyOn`，设置页的开关才能和其它行一样即时生效。
 */
object KeyMap {

    private const val SP = "h6_key_map"
    private const val K_BINDS = "binds"

    /** 长按判定时长（毫秒）。 */
    const val HOLD_MS = 600L

    /** 单击上限：抬键时按住时间超过这个值又没到长按，视为"按太久"，不触发。 */
    const val TAP_MAX_MS = 550L

    /** 学习模式广播（设置页面板动态注册接收）。 */
    const val ACTION_DETECTED = "com.h6n.launcher.KEY_MAP_DETECTED"
    const val EXTRA_CODE = "keyCode"
    const val EXTRA_REPEAT = "repeat"

    /** 一条绑定。`hold = true` 表示长按触发，false 表示单击触发。 */
    data class Bind(val code: Int, val hold: Boolean, val action: String)

    private val MAIN = Handler(Looper.getMainLooper())
    private val LOCK = Any()

    /** 学习态（进程内，不落盘）。 */
    @Volatile
    var learning: Boolean = false
        private set

    @Volatile
    var detected: Int = -1
        private set

    private val history = ArrayList<Int>()
    private var cache: List<Bind>? = null

    // 一次按压的状态
    private var activeCode = -1
    private var activeHold = false
    private var activeStart = 0L
    private var activeDownTime = -1L
    private var activeFired = false
    private var pending: Runnable? = null

    // ------------------------------------------------------------------ 读取 / 写入

    /** 配置备份恢复后：丢掉内存缓存，下次 [binds] 直接读刚写回的 SP。 */
    fun reload() {
        synchronized(LOCK) { cache = null }
    }

    fun binds(ctx: Context): List<Bind> {
        cache?.let { return it }
        val out = ArrayList<Bind>(6)
        runCatching {
            val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_BINDS, "") ?: ""
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val code = o.optInt("code", -1)
                    if (code < 0) continue
                    out += Bind(code, o.optBoolean("hold", false), o.optString("action", ""))
                }
            }
        }
        cache = out
        return out
    }

    fun save(ctx: Context, list: List<Bind>) {
        val arr = JSONArray()
        for (b in list) {
            arr.put(JSONObject().put("code", b.code).put("hold", b.hold).put("action", b.action))
        }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_BINDS, arr.toString()).apply()
        cache = ArrayList(list)
    }

    /** 加一条；同一个键 + 同一种触发方式会被覆盖（不重复堆）。 */
    fun add(ctx: Context, b: Bind) {
        val list = ArrayList(binds(ctx).filterNot { it.code == b.code && it.hold == b.hold })
        list += b
        list.sortBy { it.code }
        save(ctx, list)
    }

    fun removeAt(ctx: Context, index: Int) {
        val list = ArrayList(binds(ctx))
        if (index in list.indices) list.removeAt(index)
        save(ctx, list)
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    fun enabled(ctx: Context): Boolean =
        (ctx.applicationContext as? H6NApplication)?.config?.current()?.bool("auto.keyOn", true) ?: true

    /** 这个键是否已被方向盘长按（[SteerKey]）占用。 */
    fun takenBySteer(ctx: Context, code: Int): Boolean =
        SteerKey.enabled(ctx) && SteerKey.keyCode(ctx) == code

    // ------------------------------------------------------------------ 学习

    fun beginLearn() = synchronized(LOCK) {
        learning = true
        detected = -1
        synchronized(history) { history.clear() }
        cancelPending()
        activeCode = -1
        activeFired = false
    }

    fun endLearn() = synchronized(LOCK) {
        learning = false
        cancelPending()
        activeCode = -1
        activeFired = false
    }

    fun recent(): List<Int> = synchronized(history) { history.toList() }

    // ------------------------------------------------------------------ 分发

    /**
     * 两个按键入口（无障碍 / 桌面 activity）统一走这里。
     *
     * @return true = 这个事件已被消费，不要再传给系统
     */
    fun onKey(ctx: Context, ev: KeyEvent?): Boolean = synchronized(LOCK) {
        if (ev == null) return false
        val code = ev.keyCode
        // HOME / BACK 永不接管：映射了它们会让用户没法退出应用
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BACK) return false
        val down = ev.action == KeyEvent.ACTION_DOWN

        if (learning) {
            if (down) {
                if (ev.repeatCount == 0) {
                    detected = code
                    addHistory(code)
                } else {
                    addHistory(code)
                }
                broadcast(ctx, code, ev.repeatCount)
            }
            return true
        }

        if (!enabled(ctx)) return false

        val bind = binds(ctx).firstOrNull { it.code == code } ?: return false

        val now = SystemClock.uptimeMillis()

        if (!down) {
            cancelPending()
            val wasActive = activeCode == code
            val held = now - activeStart
            if (wasActive && !activeFired && !bind.hold && held <= TAP_MAX_MS) fire(ctx, bind)
            activeCode = -1
            activeFired = false
            return true
        }

        // 长按连发：只吞掉，绝不因为 repeat 再触发一次
        if (ev.repeatCount > 0) {
            if (bind.hold && activeCode == code && !activeFired && now - activeStart >= HOLD_MS) {
                cancelPending()
                fire(ctx, bind)
            }
            return true
        }

        // 两条入口可能收到同一次按压，只认第一个 DOWN
        if (activeCode == code && ev.downTime == activeDownTime) return true

        activeCode = code
        activeHold = bind.hold
        activeFired = false
        activeStart = now
        activeDownTime = ev.downTime
        cancelPending()

        if (bind.hold) {
            // 固件自己标了 longPress / 已经按住够久 → 直接触发，其余走本地定时器
            val nativeHeld = ev.eventTime - ev.downTime
            val nativeLong = ev.isLongPress || nativeHeld in HOLD_MS..5000L
            if (nativeLong) {
                fire(ctx, bind)
                return true
            }
            val t = object : Runnable {
                override fun run() {
                    synchronized(LOCK) {
                        if (activeCode != code || activeFired || !activeHold) return
                        if (SystemClock.uptimeMillis() - activeStart < HOLD_MS - 20L) return
                        fire(ctx, bind)
                    }
                }
            }
            pending = t
            MAIN.postDelayed(t, HOLD_MS)
        }
        return true
    }

    /** 真正执行动作；必须在 LOCK 内调用。 */
    private fun fire(ctx: Context, bind: Bind) {
        if (activeFired) return
        activeFired = true
        cancelPending()
        val app = ctx.applicationContext
        // 动作可能拉起应用 / 走 Binder，别压在无障碍线程上
        MAIN.post { AutoActions.performAndToast(app, bind.action) }
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

    private fun broadcast(ctx: Context, code: Int, repeat: Int) {
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_DETECTED).setPackage(ctx.packageName)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_REPEAT, repeat)
            )
        }
    }

    /** 绑定列表的显示文案，如「KEYCODE_F1 (131) · 长按 → 返回键」。 */
    fun describe(ctx: Context, b: Bind): String =
        SteerKey.keyLabel(b.code) + " · " + (if (b.hold) "长按" else "单击") +
            " → " + AutoActions.labelWithArg(ctx, b.action)
}
