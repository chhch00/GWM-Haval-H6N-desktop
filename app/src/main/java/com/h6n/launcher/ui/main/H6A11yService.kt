package com.h6n.launcher.ui.main

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import com.h6n.launcher.core.H6Log as Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.h6n.launcher.core.EdgeGesture
import com.h6n.launcher.core.FloatButton
import com.h6n.launcher.core.H6A11y
import com.h6n.launcher.core.KeyMap
import com.h6n.launcher.core.NavInfoOverlay
import com.h6n.launcher.core.NavOverlay
import com.h6n.launcher.core.SteerKey

/**
 * 桌面自带的**前台应用感知**无障碍服务（旧版 `.perm.H6AccessService` 的精简版）。
 *
 * ## 做四件事
 * 1. 收 `TYPE_WINDOW_STATE_CHANGED`，把事件里的包名交给 [H6A11y.onForeground]，
 *    由它决定覆盖原厂导航栏的悬浮层该显示还是收起。
 * 2. 桌面顶部向下划时，由 [expandShade] 下发 `GLOBAL_ACTION_NOTIFICATIONS`，
 *    让 SystemUI 把原车状态栏 / 通知面板拉下来 —— 桌面是沉浸全屏，系统状态栏被藏着，
 *    没有别的窗口能接住这个手势。
 * 3. **方向盘按键映射**：`onKeyEvent` 全局收按键，交给 [SteerKey.onKey]。
 *    这是唯一能在导航 / 音乐等任意界面收到方控键的通道，做法参考车机「应用管家」
 *    `AccService.onKeyEvent`（学习时广播 keyCode，命中映射键才消费）。
 * 4. **把本服务的 WindowManager 交给覆盖层**（[OverlayHost]）：
 *    `TYPE_ACCESSIBILITY_OVERLAY`(2032) 只有无障碍服务自己的 token 才挂得上，
 *    普通 Service 去加会抛 `BadTokenException: token null is not for an application`。
 *    老版 `SidebarHomeService` 本身就是 AccessibilityService，靠的正是这一点。
 *
 * ## 隐私 / 系统行为边界
 * - `canRetrieveWindowContent="false"`，不读任何窗口内容；
 * - `eventTypes` 只要 `typeWindowStateChanged` 一种；
 * - 按键过滤开着，但 `SteerKey.onKey` 只对「学习模式」和「已绑定的那个键」返回 true，
 *   其余一律放行给系统（音量 / 返回 / 切歌照旧）。
 *
 * 开启方式：设置 → 外观 → 停靠栏 → 无障碍服务（跳到系统无障碍页，勾选
 * 「哈弗国潮桌面·前台感知」）。不开也能用，覆盖层退回 Activity 生命周期判断。
 */
class H6A11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        /*
         * 整个回调只留一个入口，内部所有步骤包 runCatching。
         *
         * 原因（6.0.48）：Android 14+ 的 AccessibilityManagerService 会把
         * 「onServiceConnected 抛异常」或「5 秒内没完成握手」直接判成 crashed，
         * 设置页那条简介就变成「此服务出现故障」，而且**不会自动恢复**，
         * 必须用户手动关掉再打开。车机 8.1 没有这个判定，所以老版本一直没暴露。
         * 这里保证任何一步失败都只丢掉对应能力，不至于把整条服务判死。
         */
        runCatching { connect() }
            .onFailure { Log.e(TAG, "onServiceConnected 失败", it) }
    }

    private fun connect() {
        inst = this
        H6A11y.onConnected()
        attachOverlayHost()
        // 悬浮按钮与侧边手势的窗口也挂在这条 WindowManager 上（2032 才压得住原厂竖栏）
        runCatching { FloatButton.onHostChanged(this) }
        runCatching { EdgeGesture.onHostChanged(this) }

        // 配置已在 res/xml/h6_a11y.xml 里声明。这里再兜一层：个别车机固件不会
        // 老老实实按 meta-data 下发，服务信息被重置成默认值时事件就收不到了。
        runCatching {
            val si = serviceInfo ?: return@runCatching
            si.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            si.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            si.notificationTimeout = 100L
            // 方向盘按键映射要全局收 KeyEvent。XML 里也声明了一份，
            // 这里再兜一层：个别车机固件不下发 meta-data 的 flags。
            si.flags = si.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = si
        }.onFailure { Log.w(TAG, "设置 serviceInfo 失败: ${it.message}") }
        Log.i(TAG, "无障碍服务已就绪（宿主 WM=${if (OverlayHost.available) "2032" else "无"}）")
    }

    /**
     * 系统解绑（关掉开关 / 被 ROM 后台策略掐断 / 应用被强停）。
     *
     * 返回 true 表示「下次还能绑回来」，系统会走 [onRebind] 复用这个实例而不是重建；
     * 返回 false 时有些 ROM 会把服务标记成不可用，用户必须重新去设置里勾一次。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "无障碍服务解绑")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        // 重新绑回来不会再走 onServiceConnected，宿主 WindowManager 要在这里补挂
        runCatching { connect() }
            .onFailure { Log.e(TAG, "onRebind 失败", it) }
    }

    /**
     * 全局按键。方向盘键由车厂私有 CAN 注入，键码因固件而异，
     * 所以不硬编码：[SteerKey.onKey] 自己学、自己匹配，没命中就放行。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        // 按键映射的学习态优先：否则方向盘那个已绑定的键会先被 SteerKey 吃掉，
        // 用户想改键时会发现"怎么按都检测不到"。
        if (KeyMap.learning) return runCatching { KeyMap.onKey(this, event) }.getOrDefault(false)
        if (runCatching { SteerKey.onKey(this, event) }.getOrDefault(false)) return true
        return runCatching { KeyMap.onKey(this, event) }.getOrDefault(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        runCatching { H6A11y.onForeground(this, pkg) }
            .onFailure { Log.w(TAG, "处理前台切换失败: ${it.message}") }
    }

    override fun onInterrupt() {
        // 系统要求实现，没有任何持续反馈需要中断
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        // 先撤宿主再报断开：覆盖层正挂在这条 WindowManager 上，
        // 让服务收到通知后立刻改用应用级窗口重挂（否则窗口随服务一起没了）
        OverlayHost.detach(this)
        runCatching { NavOverlay.onHostChanged(applicationContext) }
        runCatching { NavInfoOverlay.onHostChanged(applicationContext) }
        runCatching { FloatButton.onHostChanged(applicationContext) }
        runCatching { EdgeGesture.onHostChanged(applicationContext) }
        H6A11y.onDisconnected()
        super.onDestroy()
    }

    /**
     * 把本服务（无障碍）的 WindowManager 交给覆盖层。
     *
     * ★ 这个 WindowManager 带无障碍 token，`addView(type = 2032)` 才不会被系统
     *   判成 `token null is not for an application`。详见 [OverlayHost]。
     */
    private fun attachOverlayHost() {
        val manager = runCatching {
            getSystemService(WINDOW_SERVICE) as? WindowManager
        }.getOrNull()
        if (manager == null) {
            Log.w(TAG, "拿不到 WindowManager，覆盖层只能退回应用级悬浮窗")
            return
        }
        OverlayHost.attach(this, manager)
        // 覆盖层可能已经挂在应用级窗口上（2038，压不住原厂竖栏），让它改用 2032 重挂
        runCatching { NavOverlay.onHostChanged(applicationContext) }
        // 导航信息悬浮窗同理：宿主 WindowManager 换了，挂在上面的窗口必须重挂
        runCatching { NavInfoOverlay.onHostChanged(applicationContext) }
        // 悬浮按钮 / 侧边手势同理
        runCatching { FloatButton.onHostChanged(applicationContext) }
        runCatching { EdgeGesture.onHostChanged(applicationContext) }
    }

    companion object {
        private const val TAG = "H6A11yService"

        @Volatile
        private var inst: H6A11yService? = null

        /**
         * 展开系统通知 / 快捷面板（原车状态栏）。
         *
         * 桌面是沉浸全屏，系统状态栏被藏了之后没有窗口接住「顶部下滑」，
         * 只能由无障碍服务下发 `GLOBAL_ACTION_NOTIFICATIONS` 让 SystemUI 自己展开。
         *
         * @return false = 无障碍服务没开 / 下发失败（调用方自己兜底）
         */
        fun expandShade(): Boolean = globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

        /**
         * 下发任意系统级动作，给自动化动作表（[com.h6n.launcher.core.AutoActions]）用。
         *
         * 常用值：1 返回 / 2 桌面 / 3 最近任务 / 4 通知面板 / 5 快捷设置。
         * 直接用 `AccessibilityService.GLOBAL_ACTION_*` 的数值，避免为了几个常量
         * 在调用方再 import 一次服务类。
         *
         * @return false = 无障碍服务没开 / 系统拒绝（调用方自己出提示）
         */
        fun globalAction(code: Int): Boolean {
            val s = inst ?: return false
            return runCatching { s.performGlobalAction(code) }.getOrDefault(false)
        }
    }
}
