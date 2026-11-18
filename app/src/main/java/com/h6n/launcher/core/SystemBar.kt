package com.h6n.launcher.core

import android.content.Context
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.ui.main.H6A11yService

/**
 * 把原车状态栏（SystemUI 那条带天气 / 快捷开关的面板）拉下来。
 *
 * ## 为什么需要它
 * 桌面是沉浸式全屏（`SYSTEM_UI_FLAG_FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY`），
 * 系统状态栏被藏起来之后，屏幕上就没有可以接住「从顶部往下划」的窗口了 ——
 * 车机上表现为"桌面顶部划不出原车状态栏"。
 *
 * ## 三条通道（从可靠到兜底）
 * 1. **无障碍 `GLOBAL_ACTION_NOTIFICATIONS`**：系统级动作，SystemUI 收到就把
 *    通知/快捷面板拉下来。这是第三方应用唯一正规、无需系统签名的方式，
 *    用的是我们已经有的 [H6A11yService]（设置 → 停靠栏 → 无障碍服务 里开）。
 * 2. **反射 `StatusBarManager.expandNotificationsPanel()`**：老办法，
 *    要 `EXPAND_STATUS_BAR` 权限（signature|privilege），普通应用基本会被拒；
 *    但车机 ROM 有时把这个权限放宽给桌面，试一下不亏。
 * 3. 两条都不通时由调用方兜底：临时撤掉沉浸全屏，让原车状态栏自己露出来。
 */
object SystemBar {

    private const val TAG = "H6SystemBar"

    /** @return true = 已经让系统去展开面板了 */
    fun expand(ctx: Context): Boolean {
        if (H6A11yService.expandShade()) {
            Log.i(TAG, "已通过无障碍展开通知面板")
            return true
        }
        if (reflectExpand(ctx)) {
            Log.i(TAG, "已通过 StatusBarManager 展开通知面板")
            return true
        }
        Log.w(TAG, "无障碍未开启、StatusBarManager 也没权限，退回「露出系统栏」方案")
        return false
    }

    private fun reflectExpand(ctx: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val sb = ctx.getSystemService("statusbar") ?: return@runCatching false
        val m = sb.javaClass.getMethod("expandNotificationsPanel")
        m.invoke(sb)
        true
    }.getOrElse { e ->
        Log.i(TAG, "反射展开失败: ${e.javaClass.simpleName} ${e.message}")
        false
    }
}
