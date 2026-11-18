package com.h6n.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.h6n.launcher.core.H6Log as Log

/**
 * 把桌面的昼夜判定同步给高德地图车机版（`com.autonavi.amapauto`）。
 *
 * ## 协议（高德车机版对外广播标准协议，两条一起发）
 * 收：`AUTONAVI_STANDARD_BROADCAST_RECV`，显式投给
 * `com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver`
 *
 * | KEY_TYPE | extra | 取值 |
 * |---|---|---|
 * | `10048` | `EXTRA_DAY_NIGHT_MODE` | 0 自动 / **1 白天** / **2 黑夜** |
 * | `10017` | `EXTRA_HEADLIGHT_STATE` | 0 大灯开（夜间） / 1 大灯关（白天） |
 *
 * 为什么两条都发：高德的昼夜设置是「自动 / 白天 / 黑夜」三态，用户可能自己设成
 * 「跟随大灯」——那条走 10017；也可能被 10048 锁成固定模式。两条同时发，
 * 不管高德内部处于哪种状态，收到之后都落到和桌面一致的结果。
 *
 * 高德没装 / 被禁用时 `sendBroadcast` 只是没人接收，不抛异常，所以整条链路不需要失败处理。
 * 发送节流：同一状态 30 秒内只发一次；主题切换、回到桌面、每 5 分钟补发一次（force）。
 */
object AmapNightSync {

    private const val TAG = "H6AmapNight"

    /** 高德车机版接收广播的 action */
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_RECV"

    private const val PKG = "com.autonavi.amapauto"
    private const val RCVR = "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"

    private const val KEY_TYPE = "KEY_TYPE"
    private const val K_DAY_NIGHT = 10048
    private const val K_HEADLIGHT = 10017

    @Volatile private var lastNight = -1
    @Volatile private var lastAt = 0L

    /** 最近一次发送结论，供设置页「车身信号」诊断显示。 */
    @Volatile var lastReport: String = "未发送"
        private set

    /**
     * @param enabled 设置页「同步高德昼夜」开关（`theme.amapSync`）
     * @param force 同状态下也强制补发（回到桌面 / 每 5 分钟一次）
     */
    fun apply(ctx: Context, night: Boolean, enabled: Boolean = true, force: Boolean = false) {
        if (!enabled) {
            lastReport = "已关闭"
            return
        }
        val v = if (night) 1 else 0
        val now = System.currentTimeMillis()
        if (!force && v == lastNight && now - lastAt < 30_000L) return

        val ok1 = send(ctx, K_DAY_NIGHT, "EXTRA_DAY_NIGHT_MODE", if (night) 2 else 1)
        val ok2 = send(ctx, K_HEADLIGHT, "EXTRA_HEADLIGHT_STATE", if (night) 0 else 1)
        lastNight = v
        lastAt = now
        lastReport = (if (night) "夜间" else "白天") +
            if (ok1 || ok2) " · 已发送" else " · 高德未安装"
        Log.i(TAG, "sync ${if (night) "night" else "day"} -> $lastReport")
    }

    /** 显式投递；接收方不存在时返回 false（不代表高德没装，只代表这条没投出去）。 */
    private fun send(ctx: Context, key: Int, extra: String, value: Int): Boolean {
        val i = Intent(ACTION)
        i.component = ComponentName(PKG, RCVR)
        i.putExtra(KEY_TYPE, key)
        i.putExtra(extra, value)
        // 高德被强停/刚升级时也要能收到
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        return try {
            ctx.sendBroadcast(i)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send $key 失败", t)
            false
        }
    }
}
