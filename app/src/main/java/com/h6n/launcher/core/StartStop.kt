package com.h6n.launcher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log

/**
 * 自动启停（发动机启停 / ASS）默认值 —— 从旧版（v5.97.96 smali）移植。
 *
 * ## 旧版做了什么
 * 旧版 AdvOptions 在设置页给三个模式，冷启动 9 秒后按模式下发一次：
 * - `off` 自动关闭（每次启动把启停关掉）
 * - `on`  自动开启
 * - `none` 保持原样（默认，不下发）
 * 下发前先读当前状态，已经是目标值就不动（避免每次启动都写一遍车控）。
 *
 * ## 6.0.90 移植时的两点改变
 * 1. **默认值下发**：本版不带授权体系，启动后按用户设定直接生效。
 * 2. **下发通道**沿用重写版已有的 [CarBinderSource.setStartStop] 双通道
 *    （PlatformAdapter + ICarInfo），与旧版 setStartStopPlat → sendStartStop 回退一致。
 *
 * ## 冷启动触发点
 * [onColdStart] 在桌面首帧后调用，延时 [BOOT_DELAY_MS] 走一次 [applyMode]；
 * 进程内一次性闸门防止转屏 / 从设置页回来重复下发。
 */
object StartStop {

    private const val TAG = "H6StartStop"

    private const val SP = "h6_startstop"
    private const val K_MODE = "mode"

    const val MODE_NONE = "none"
    const val MODE_OFF = "off"
    const val MODE_ON = "on"

    /** 冷启动后延时：与旧版 MainActivity$12 的 0x2328=9000ms 一致。 */
    private const val BOOT_DELAY_MS = 9000L

    private val MAIN = Handler(Looper.getMainLooper())

    /** 进程内是否已排过冷启动那一次。 */
    @Volatile
    private var bootScheduled = false

    /** 测试/排障用：允许下一次回桌面重新排冷启动下发。 */
    fun resetBoot() {
        bootScheduled = false
    }

    // ------------------------------------------------------------------ 模式读写

    fun mode(ctx: Context): String {
        val m = runCatching {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_MODE, MODE_NONE)
        }.getOrDefault(MODE_NONE) ?: MODE_NONE
        return if (m == MODE_OFF || m == MODE_ON) m else MODE_NONE
    }

    fun setMode(ctx: Context, mode: String) {
        val norm = if (mode == MODE_OFF || mode == MODE_ON) mode else MODE_NONE
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(K_MODE, norm).apply()
        Log.i(TAG, "自动启停模式 → $norm")
    }

    /** 模式的界面文案。 */
    fun modeText(mode: String): String = when (mode) {
        MODE_OFF -> "自动关闭"
        MODE_ON -> "自动开启"
        else -> "保持原样"
    }

    /** 功能可用性。公开源码版不内置授权体系，恒为 true。 */
    fun unlocked(ctx: Context): Boolean = true

    /** 当前车机启停状态的展示文案。 */
    fun stateText(ctx: Context): String = when (CarBinderSource.readStartStop()) {
        1 -> "开启（有启停）"
        0 -> "已关闭"
        else -> "读不到（车机未连接或不支持）"
    }

    // ------------------------------------------------------------------ 下发

    /**
     * 按当前模式下发一次。未解锁 / 模式 none 直接跳过。
     *
     * @param force true=忽略「当前已是目标值」的判断，手动点「立即执行」时用
     * @param onDone (是否真的下发, 结果文案)，主线程回调
     */
    fun applyMode(
        ctx: Context,
        force: Boolean = false,
        onDone: ((Boolean, String) -> Unit)? = null
    ) {
        val app = ctx.applicationContext
        val m = mode(app)
        if (m == MODE_NONE) {
            onDone?.invoke(false, "模式是「保持原样」，本次未下发")
            return
        }
        if (!unlocked(app)) {
            onDone?.invoke(false, "功能未解锁，需先用授权码验证")
            return
        }
        val wantOn = m == MODE_ON
        val cur = CarBinderSource.readStartStop()
        if (!force && cur == if (wantOn) 1 else 0) {
            Log.i(TAG, "启停已是目标状态（$cur），跳过下发")
            onDone?.invoke(false, "启停已经是「${modeText(m)}」的状态，无需下发")
            return
        }
        val ok = CarBinderSource.setStartStop(wantOn)
        val msg = if (ok) "已下发：启停${modeText(m)}" else "下发失败：车控服务没连上（手机上没有这个服务，属正常）"
        Log.i(TAG, msg)
        onDone?.invoke(ok, msg)
    }

    // ------------------------------------------------------------------ 冷启动

    /**
     * 桌面首帧后调用。只排一次，延时 9 秒走到 [applyMode]。
     * 未解锁 / 保持原样时 [applyMode] 内部会直接跳过，不碰车控。
     */
    fun onColdStart(ctx: Context) {
        if (bootScheduled) return
        bootScheduled = true
        val app = ctx.applicationContext
        MAIN.postDelayed({
            runCatching { applyMode(app) }
        }, BOOT_DELAY_MS)
    }
}
