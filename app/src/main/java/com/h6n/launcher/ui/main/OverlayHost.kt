package com.h6n.launcher.ui.main

import android.view.WindowManager

/**
 * 覆盖层窗口的宿主：**无障碍服务**的 WindowManager。
 *
 * ## 为什么必须有这个东西（2026-11-11 VC402 车机实测的坑）
 * `TYPE_ACCESSIBILITY_OVERLAY`(2032) 是唯一能压住原厂左侧竖栏的窗口类型，
 * 但它要求调用方持有**无障碍服务的窗口 token**。
 *
 * 我们的 `NavOverlayService` 是普通前台 Service，用它自己的 `getSystemService(WINDOW_SERVICE)`
 * 去加 2032，系统直接抛：
 *
 * ```
 * BadTokenException: Unable to add window -- token null is not for an application
 * ```
 *
 * 然后退到 `TYPE_APPLICATION_OVERLAY`(2038) —— 那个能挂上，但层级低于原厂竖栏，
 * 屏幕上看起来就是"开关开了、覆盖层状态一直挂载中、什么也没变"。
 *
 * 反编译老版（旧包 `SidebarHomeService`，smali `.super Landroid/accessibilityservice/AccessibilityService;`）
 * 才看清：它本身就是 `AccessibilityService`，`getSystemService("window")` 拿到的是带
 * 无障碍 token 的 WindowManager，再 `addView(type = 0x7f0 = 2032)`。
 *
 * 所以这里把那个 WindowManager 存成进程内单例，覆盖层服务借它挂窗口。
 * 无障碍服务没开时 [wm] 为 null，覆盖层只能退回 2038（压不住原厂竖栏），
 * 设置页「覆盖层状态」会把这种情况明确写出来。
 */
object OverlayHost {

    @Volatile
    private var owner: Any? = null

    /** 无障碍服务提供的 WindowManager；null = 无障碍没开 / 已断开。 */
    @Volatile
    var wm: WindowManager? = null
        private set

    /** 有没有带无障碍 token 的 WindowManager 可用（决定 2032 能不能挂）。 */
    val available: Boolean
        get() = wm != null

    internal fun attach(host: Any, manager: WindowManager) {
        owner = host
        wm = manager
    }

    internal fun detach(host: Any) {
        // 只清自己：服务重建时新实例先 attach、旧实例再 onDestroy 的乱序不至于把新宿主清掉
        if (owner !== host) return
        owner = null
        wm = null
    }
}
