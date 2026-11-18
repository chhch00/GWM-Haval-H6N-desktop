package com.h6n.launcher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.data.ConfigRepository
import com.h6n.launcher.domain.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 夜间自动息屏（从旧版 `NightScreen.java` 移植，2026-11-12）。
 *
 * ## 解决什么
 * 晚上车里的屏幕太亮；原厂息屏在开空调、弹 360 全景、语音唤醒亮屏之后不会自己再黑回去。
 * 本模块在「**桌面在前台** + **车灯亮**」两个条件同时成立时，延时若干秒调原厂息屏；
 * 一旦关灯 / 离开桌面 / 重新回到桌面，立刻恢复亮度并重新计时。
 *
 * ## 息屏方式：复用原厂背光通道，不是遮罩
 * 与原厂下拉状态栏那颗「息屏」按钮走同一条指令 —— ICarInfo 的
 * `sendItemValues(funcIndex=0x18, cmdId=0x21404410, [autoMode, dayNight, level, level])`，
 * `level=0` 即息屏，恢复到息屏前的亮度即亮屏。见 [CarBinderSource.setBacklight]。
 * 因此**不加遮罩、不监听触摸、不建任何悬浮窗**，和原厂息屏表现完全一致。
 * 亮屏由车机自己的按键 / 触摸唤醒流程负责，我们只在状态变化时把背光等级写回去。
 *
 * ## 与旧版的差异（有意为之）
 * | 旧版 | 本版 | 原因 |
 * |---|---|---|
 * | 静态 Handler 每秒 tick，独立于界面 | 跟着 MainActivity 的 ticker 走 | 重写版主界面本来就有 1s ticker，省一个循环 |
 * | 离开桌面只置 `foreground=false`，不恢复亮度 | [onBack] 立刻恢复亮度 | 否则用户切到导航时屏幕还是黑的，且那时已不再 tick，会一直黑着 |
 * | 背光恢复固定用 6 | 记录息屏前真实亮度 | 车机亮度通常是个位数等级，固定 6 会突然变亮刺眼 |
 * | 左侧悬浮「月/暗」按钮（TYPE_APPLICATION_OVERLAY） | dock 槽位 `@night` + 设置页开关 | 用户明确要求这条功能不引入悬浮层 |
 *
 * ## 线程
 * 全部在**主线程**调用（ticker / 生命周期 / 点击），读配置用内存快照，写配置走
 * [ConfigRepository] 的挂起方法并丢到 IO 线程；binder 写入本身是同步 transact，
 * 每秒最多一次、只在状态变化时发生。
 */
internal object NightScreen {

    private const val TAG = "H6Night"

    /** 配置路径：总开关（默认关，和旧版一致）。 */
    const val KEY_ENABLED = "screen.nightOff"

    /** 配置路径：进入桌面后多少秒息屏。 */
    const val KEY_DELAY = "screen.nightOffDelay"

    const val DEFAULT_DELAY_SEC = 30
    private const val DELAY_MIN_SEC = 5
    private const val DELAY_MAX_SEC = 600

    /** 取不到当前亮度时的兜底等级（旧版就是固定 6）。 */
    private const val FALLBACK_LEVEL = 6

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var app: Context? = null

    @Volatile private var enabled = false
    @Volatile private var delayMs = DEFAULT_DELAY_SEC * 1000L
    @Volatile private var foreground = false
    @Volatile private var frontSince = 0L

    /** 当前是否处于我们下发的息屏态。 */
    @Volatile private var off = false

    /** 息屏前的背光等级（1..10），-1 = 没记到。 */
    @Volatile private var savedLevel = -1

    /** 上次同步过配置的快照引用；快照不可变，同一对象就代表配置没改（v6.0.50）。 */
    @Volatile private var syncedSnap: ConfigSnapshot? = null

    // ------------------------------------------------------------------ 生命周期

    /** 进程内第一次挂载时缓存 applicationContext（读亮度 / 写配置都要）。 */
    fun attach(ctx: Context) {
        if (app == null) app = ctx.applicationContext
    }

    /** 桌面回到前台：重新计时；如果还停在息屏态（比如被系统点亮又切回来）就恢复亮度。 */
    fun onFront(ctx: Context, snap: ConfigSnapshot?) {
        attach(ctx)
        sync(snap)
        foreground = true
        frontSince = SystemClock.elapsedRealtime()
        if (off) wake("回到桌面")
    }

    /** 桌面退到后台：不再息屏，并且**立刻**把亮度还回去，绝不把黑屏带进别的应用。 */
    fun onBack() {
        foreground = false
        if (off) wake("离开桌面")
    }

    /**
     * 每秒调用一次（MainActivity 的 ticker）。
     *
     * 判定顺序：开关关 → 不在前台 → 车灯未知 → 白天 → 夜间计时。
     * 车灯未知时**什么都不做**（保持现状），避免把"读不到"当成"白天"来回切亮度。
     */
    fun tick(sig: CarSignal, snap: ConfigSnapshot?) {
        // 开关 / 延时只在配置快照换对象时重读；其余秒只是个引用比较，
        // 倒计时本身（下面 frontSince / elapsedRealtime）仍严格每秒走。
        if (snap !== syncedSnap) {
            syncedSnap = snap
            sync(snap)
        }
        if (!enabled || !foreground) return
        when (sig.night) {
            -1 -> return                                  // 车灯未知：不动作
            0 -> {                                        // 白天：确保亮屏并重新计时
                if (off) wake("车灯关闭")
                frontSince = SystemClock.elapsedRealtime()
                return
            }
        }
        if (!off && SystemClock.elapsedRealtime() - frontSince >= delayMs) sleep()
    }

    /**
     * 配置加载 / 变更时同步一次内存状态。
     *
     * dock 的月亮格读 [enabled] 而不是主题快照：写配置是异步的，快照要过一会儿才回流，
     * 点一下开关如果读快照，格子会先停在旧状态。改成由这里保证冷启动和配置回流后一致。
     */
    fun syncFrom(snap: ConfigSnapshot?) {
        sync(snap)
    }

    /** 从配置快照刷新开关与延时。快照为 null（还没加载完）时保留上一次的值。 */
    private fun sync(snap: ConfigSnapshot?) {
        if (snap == null) return
        enabled = snap.bool(KEY_ENABLED, false)
        delayMs = snap.int(KEY_DELAY, DEFAULT_DELAY_SEC)
            .coerceIn(DELAY_MIN_SEC, DELAY_MAX_SEC) * 1000L
    }

    // ------------------------------------------------------------------ 息屏 / 亮屏

    /**
     * 立即息屏。返回 false 表示车控服务没连上 / 写入失败（此时不改内部状态）。
     */
    private fun sleep(): Boolean {
        if (off) return true
        val level = currentLevel()
        val ok = CarBinderSource.setBacklight(0, autoMode(), dayNightMode())
        if (!ok) {
            Log.w(TAG, "息屏指令写入失败（车控服务未连接？）")
            return false
        }
        savedLevel = level
        off = true
        Log.i(TAG, "sleep, saved level=$level")
        return true
    }

    /** 恢复息屏前的亮度。写入失败也把状态清掉，否则会卡在"以为黑着"的状态里。 */
    private fun wake(reason: String) {
        if (!off) return
        val level = if (savedLevel in 1..10) savedLevel else FALLBACK_LEVEL
        val ok = CarBinderSource.setBacklight(level, autoMode(), dayNightMode())
        off = false
        Log.i(TAG, "wake($reason) level=$level ok=$ok")
    }

    /**
     * 设置页「试一次」：立刻息屏，[holdMs] 后自动恢复。
     *
     * 车机上夜间不一定马上到，没这个入口就没法确认背光通道是否可用。
     * 自动恢复是硬保证 —— 设置页不是桌面，万一用户看完就退出，屏幕不会一直黑着。
     */
    fun preview(ctx: Context? = null, holdMs: Long = 3000L): Boolean {
        if (ctx != null) attach(ctx)
        if (!sleep()) return false
        main.postDelayed({
            if (off) wake("试一次结束")
        }, holdMs)
        return true
    }

    // ------------------------------------------------------------------ 开关（dock / 设置页）

    /** 当前开关（内存值，由 [sync] 跟随配置刷新）。 */
    fun enabled(): Boolean = enabled

    /**
     * 由 dock 的「息屏」格子 / 设置页调用。写回配置后当前进程立即生效，不等快照回流。
     * 返回切换后的状态。
     */
    fun toggle(ctx: Context, snap: ConfigSnapshot?): Boolean {
        attach(ctx)
        sync(snap)
        val next = !enabled
        enabled = next
        if (!next && off) wake("手动关闭")
        io.launch {
            runCatching {
                ConfigRepository.get(ctx).set(KEY_ENABLED, if (next) "true" else "false")
            }.onFailure { Log.w(TAG, "保存开关失败", it) }
        }
        Log.i(TAG, "toggle -> $next")
        return next
    }

    // ------------------------------------------------------------------ 诊断

    /** 设置页状态行。 */
    fun status(sig: CarSignal?, snap: ConfigSnapshot?): String {
        sync(snap)
        val night = when (sig?.night) {
            1 -> "夜间"
            0 -> "白天"
            else -> "车灯未知"
        }
        val state = when {
            off -> "息屏中"
            !enabled -> "已关，不参与"
            sig?.night != 1 -> "等待夜间车灯"
            else -> "计时中，${delayMs / 1000}s 后息屏"
        }
        return "${if (enabled) "已开" else "已关"} · $night · $state"
    }

    /** 一条更啰嗦的说明，给设置页的 desc 用（含车控通道是否可以下发）。 */
    fun channelHint(): String =
        if (CarBinderSource.canControl()) "车控通道已连接，可直接下发背光"
        else "车控通道未连接，息屏下发会失败（只在车机上有效）"

    // ------------------------------------------------------------------ 亮度读取

    /** 系统当前亮度 0..255 → 车机背光等级 1..10；读不到给兜底值。 */
    private fun currentLevel(): Int {
        val c = app ?: return FALLBACK_LEVEL
        val raw = runCatching {
            Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        if (raw < 0) return FALLBACK_LEVEL
        return ((raw * 10 + 127) / 255).coerceIn(1, 10)
    }

    /** 原厂背光指令里的两个模式位，和旧版读的是同一对 Global 键。 */
    private fun autoMode(): Int = globalInt("bright_auto_mode_setting", 0)

    private fun dayNightMode(): Int = globalInt("bright_daynight_mode_settings", 1)

    private fun globalInt(key: String, def: Int): Int {
        val c = app ?: return def
        return runCatching { Settings.Global.getInt(c.contentResolver, key, def) }.getOrDefault(def)
    }
}
