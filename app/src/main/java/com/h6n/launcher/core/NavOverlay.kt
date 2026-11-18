package com.h6n.launcher.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import androidx.core.content.ContextCompat
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.ui.main.NavOverlayService
import com.h6n.launcher.ui.main.OverlayHost

/**
 * 「覆盖原厂导航栏」的开关与生命周期。
 *
 * ## 它解决什么
 * 原车左侧那条竖栏（时钟 + 原厂快捷方式）是原厂桌面的，切到导航/音乐等其他应用时
 * 依然压在最上层，很碍事。旧版 `SidebarHomeService` 用一个悬浮窗把它整条盖住，
 * 换成自己的停靠栏 + 一个「返回原车桌面」按钮。这里是那套逻辑的 Kotlin 版。
 *
 * ## 三条硬约束（都是踩过的坑）
 * 1. **必须显式开启悬浮窗权限**（`SYSTEM_ALERT_WINDOW`），否则 `addView` 直接抛
 *    BadTokenException；权限只能跳系统页让用户点，不能代码授予。
 * 2. **窗口类型必须是 `TYPE_ACCESSIBILITY_OVERLAY`(2032)，而且只能挂在无障碍服务的
 *    WindowManager 上**（[OverlayHost]）。普通 Service 加 2032 会被系统判成
 *    `token null is not for an application`；退到 2038 又压不住原厂竖栏。
 *    并且 `FLAG_NOT_FOCUSABLE`：不抢输入焦点，不然车机上的返回键/旋钮会先给悬浮层。
 * 3. **打开后桌面自带的 dock 要让位**（[replacesDock] → MainActivity 把 dock 设 GONE）：
 *    同一条侧栏由覆盖层统一提供，桌面上看到的就是覆盖层本身，切应用时不会"换一条"。
 *    还没有悬浮窗权限时不让位 —— 否则桌面上一条侧栏都没有。
 *    只有**设置页**会把覆盖层压住（[setSuppressed]`(true)`），那是整屏面板，盖上去挡选项。
 *
 * 开关走配置 `sidebar.homeOn`（设置 → 外观 → 停靠栏 → 侧栏覆盖层），**默认关**。
 *
 * ## 关于「打开没效果」
 * 悬浮窗这条路外面看不见的失败点太多（没权限 / 系统不给 START_STICKY / addView 被拒），
 * 用户只会看到"什么都没发生"。所以这里把每一步的结果都记进 [lastError] /
 * [running] / [attached]，设置页的「覆盖层状态」一行直接把它们显示出来：
 * 出问题能一眼看出卡在哪一步，而不是靠猜。
 */
object NavOverlay {

    private const val TAG = "H6NavOverlay"

    const val ACTION_REFRESH = "com.h6n.launcher.overlay.REFRESH"
    const val ACTION_VISIBLE = "com.h6n.launcher.overlay.VISIBLE"
    const val ACTION_HIDDEN = "com.h6n.launcher.overlay.HIDDEN"
    const val ACTION_STOP = "com.h6n.launcher.overlay.STOP"

    /** 覆盖层挂上 / 掉下来时广播一次，桌面收到就重算 dock 显隐（不用猜时间点轮询）。 */
    const val ACTION_STATE = "com.h6n.launcher.overlay.STATE"

    /**
     * `TYPE_ACCESSIBILITY_OVERLAY`(2032)：唯一能压住原厂左侧竖栏的窗口类型。
     * ★ 只有无障碍服务的 WindowManager（[OverlayHost]）挂得上，普通 Service 会抛
     *   `BadTokenException: token null is not for an application`。
     */
    const val TYPE_A11Y = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    private fun snap(ctx: Context) =
        (ctx.applicationContext as? H6NApplication)?.config?.current()

    /** 开关是否打开（配置项，默认关）。 */
    fun enabled(ctx: Context): Boolean = snap(ctx)?.bool("sidebar.homeOn", false) ?: false

    /** 有没有悬浮窗权限。 */
    fun canDraw(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    /** 服务进程是否活着（`onCreate` 起、`onDestroy` 止）。 */
    @Volatile
    var running: Boolean = false
        private set

    /** 窗口是否真的挂到 WindowManager 上了。`running` 但 `attached=false` = 卡在 addView。 */
    @Volatile
    var attached: Boolean = false
        private set

    /** 挂上去用的窗口类型：2032 = TYPE_ACCESSIBILITY_OVERLAY，2038 = TYPE_APPLICATION_OVERLAY，0 = 未挂。 */
    @Volatile
    var attachedType: Int = 0
        private set

    /** 最近一次失败原因（空串 = 最近一次操作没出错）。设置页「覆盖层状态」直接显示。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 由 [NavOverlayService] 回写运行状态。 */
    internal fun markRunning(on: Boolean) {
        running = on
        if (!on) {
            attached = false
            attachedType = 0
        }
    }

    internal fun markAttached(on: Boolean, type: Int) {
        attached = on
        attachedType = if (on) type else 0
    }

    /** 状态变了就吱一声，桌面据此刷新 dock 显隐。 */
    internal fun notifyState(ctx: Context) {
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(ctx.packageName))
        }
    }

    internal fun markError(msg: String) {
        lastError = msg
        Log.w(TAG, msg)
    }

    internal fun clearError() {
        lastError = ""
    }

    /**
     * 覆盖层当前是否被"压住"（true = 不显示）。
     *
     * 放在 object 里而不是服务里：服务是延迟起来的，启动那一刻必须能读到
     * 当前界面给的结论，否则在设置页里打开开关会先闪一下覆盖层。
     *
     * 谁设 true：设置页（整屏面板，盖上就挡住选项了）。
     * 谁设 false：桌面（此时桌面自己的 dock 已隐藏，正需要覆盖层顶上）、
     *            以及无障碍服务发现前台是别的应用时。
     */
    @Volatile
    var suppressed: Boolean = false
        private set

    /**
     * 桌面自己的 dock 是否该让位给覆盖层。
     *
     * 三个条件都满足才让位：
     * 1. 开关打开；
     * 2. 悬浮窗权限在手；
     * 3. **服务真的活着**（[running]）——否则覆盖层挂不上，dock 又让了位，
     *    桌面上就一条侧栏都没有了（6.0.7 开了开关没反应时正是这个现象）。
     */
    fun replacesDock(ctx: Context): Boolean = enabled(ctx) && canDraw(ctx) && (running || attached)

    /**
     * 按配置把覆盖层拉起来 / 停掉。桌面每次改配置、回前台时都可以无脑调。
     *
     * @return 需要授权时返回 false（调用方负责提示去开权限）
     */
    fun sync(ctx: Context): Boolean = apply(ctx, enabled(ctx))

    /**
     * 按**显式值**起停覆盖层。
     *
     * 设置页刚写完配置时 DataStore 的快照可能还没回流，此时读 [enabled] 会拿到旧值，
     * 于是"点了开关没反应"。所以开关点击处直接用新值调这里，不走配置回读。
     *
     * @return 需要授权时返回 false
     */
    fun apply(ctx: Context, on: Boolean): Boolean {
        if (!on) {
            stop(ctx)
            clearError()
            return true
        }
        if (!canDraw(ctx)) {
            markError("缺少「显示在其他应用上层」权限")
            stop(ctx)
            return false
        }
        start(ctx, ACTION_REFRESH)
        return true
    }

    /** 只重画（改主题 / 改停靠栏配置 / 切昼夜后调）。服务没在跑就什么都不做。 */
    fun refresh(ctx: Context) {
        if (!enabled(ctx) || !canDraw(ctx)) return
        start(ctx, ACTION_REFRESH)
    }

    /**
     * 无障碍服务连上 / 断开时调 —— 覆盖层的宿主 WindowManager 换了，必须重挂。
     *
     * - 连上：[OverlayHost.available] 变 true，服务会改用 2032 重挂（真正压住原厂竖栏）；
     * - 断开：2032 的窗口随服务一起失效，服务会退回 2038（至少还看得见）。
     *
     * 由 [com.h6n.launcher.ui.main.H6A11yService] 在 `onServiceConnected` /
     * `onDestroy` 里触发。
     */
    fun onHostChanged(ctx: Context) {
        if (!enabled(ctx) || !canDraw(ctx)) return
        // 服务还在跑就先发 REFRESH 让它重挂；没跑就按 VISIBLE 起一次（无障碍服务起前台服务不受后台限制）
        start(ctx, if (running) ACTION_REFRESH else ACTION_VISIBLE)
    }

    fun stop(ctx: Context) {
        // 没在跑就别为了一个 STOP 动作把服务拉起来（sync 会被高频调用）
        if (!running) return
        runCatching {
            ctx.startService(Intent(ctx, NavOverlayService::class.java).setAction(ACTION_STOP))
        }.onFailure { markError("停止覆盖层失败：${it.message}") }
    }

    /**
     * 压住 / 放开覆盖层。
     *
     * - 设置页：`true`（整屏面板，盖上去会挡住左侧选项）
     * - 桌面：`false`（此时桌面自己的 dock 已隐藏，正需要覆盖层来当这条侧栏）
     * - 无障碍发现前台是别的应用：`false`
     *
     * 同一个结论不重复下发：窗口每切一次都 startForegroundService 一次是浪费。
     */
    fun setSuppressed(ctx: Context, on: Boolean) {
        if (suppressed == on) return
        suppressed = on
        if (!canDraw(ctx)) return
        // ★ 这里**不看 enabled(ctx)**：设置页刚把开关写进 DataStore，快照可能还没回流，
        //   回读到旧的 false 就把这条"放开"指令吞掉了 —— 覆盖层于是一直停在"挂载中"。
        //   服务在跑就一定要把动作送到。
        if (on) {
            if (running) start(ctx, ACTION_HIDDEN)
        } else {
            if (running || enabled(ctx)) start(ctx, ACTION_VISIBLE)
        }
    }

    /**
     * 起服务的统一入口。
     *
     * 先试 `startForegroundService`（API 26+ 后台起前台服务的正规姿势），
     * 车机 ROM 上偶发被拒（有的 ROM 对前台服务限额），再退到普通 `startService` ——
     * 调用点全在前台（Activity 生命周期 / 无障碍事件），普通 startService 是允许的。
     */
    private fun start(ctx: Context, action: String) {
        val i = Intent(ctx, NavOverlayService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = runCatching { ContextCompat.startForegroundService(ctx, i) }
            if (r.isSuccess) return
            markError("前台服务被系统拒绝：${r.exceptionOrNull()?.message}")
        }
        runCatching { ctx.startService(i) }
            .onFailure { markError("启动覆盖层服务失败：${it.message}") }
    }

    /** 设置页「覆盖层状态」那一行的文案。 */
    fun describe(ctx: Context): String {
        if (!enabled(ctx)) return "未开启"
        if (!canDraw(ctx)) return "缺少悬浮窗权限——覆盖层显示不出来，去下面「悬浮窗权限」授权"
        if (lastError.isNotEmpty()) return lastError
        return when {
            suppressed -> "设置页内临时收起（返回桌面自动出现）"
            attached && attachedType == TYPE_A11Y -> "已生效：覆盖层正在显示（无障碍窗口 2032，在原厂竖栏之上）"
            attached -> "已挂上但只是应用级窗口（$attachedType），压不住原厂竖栏——" +
                "去下面「无障碍服务」开启后返回，会自动换成 2032 重挂"
            running -> "服务已启动，正在挂载窗口…（8 秒内没动静就是被系统挡了，把这一行截图）"
            else -> "服务未在运行——开关重新关一次再打开会重试"
        }
    }

    /**
     * 诊断串：把几个关键位一次性摊开，用户截一张图就能定位卡在哪一步。
     * 2026-10-11 排查"覆盖原厂导航栏没效果"时加的；2026-11-11 加无障碍宿主位。
     */
    fun diagnose(ctx: Context): String {
        val b = StringBuilder()
        b.append(if (enabled(ctx)) "开关:开" else "开关:关")
        b.append(if (canDraw(ctx)) " · 权限:有" else " · 权限:无")
        b.append(if (OverlayHost.available) " · 无障碍:开" else " · 无障碍:关")
        b.append(if (running) " · 服务:在" else " · 服务:停")
        b.append(if (attached) " · 窗口:已挂(type $attachedType)" else " · 窗口:未挂")
        if (suppressed) b.append(" · 设置页内收起")
        if (lastError.isNotEmpty()) b.append(" · 错误:").append(lastError)
        return b.toString()
    }

    /**
     * 挂一个纯红测试块 5 秒（压在原厂竖栏位置上）。
     *
     * 用来区分几种"没效果"：
     * - 红块出现、并且盖住了原厂竖栏 → 2032 走通了，问题在我们自己的窗口参数；
     * - 红块出现但被原厂竖栏压住 → 层级不够（多半是无障碍没开，用的 2038）；
     * - 红块根本不出现 → 系统不给第三方悬浮窗 / 连应用级窗口都被拒。
     */
    fun testOverlay(ctx: Context): String {
        if (!canDraw(ctx)) return "没有悬浮窗权限，先去授权"
        val app = ctx.applicationContext
        val own = app.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return "拿不到 WindowManager"
        // 优先用无障碍服务的 WindowManager（2032 只有它挂得上）
        val host = OverlayHost.wm
        val v = android.view.View(app).apply { setBackgroundColor(0xFFFF3B30.toInt()) }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        val tries = ArrayList<Pair<android.view.WindowManager, Int>>(3)
        if (host != null) tries.add(host to TYPE_A11Y)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            tries.add(own to android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            tries.add(own to android.view.WindowManager.LayoutParams.TYPE_PHONE)
        }

        var last: Throwable? = null
        for ((manager, type) in tries) {
            val p = android.view.WindowManager.LayoutParams(
                180, 480, type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 60
            }
            val r = runCatching {
                manager.addView(v, p)
                handler.postDelayed({ runCatching { manager.removeView(v) } }, 5000L)
            }
            if (r.isSuccess) {
                return if (type == TYPE_A11Y)
                    "已挂上红色测试块（无障碍 2032）。看左侧：出现且盖住原厂竖栏＝这条路通了"
                else
                    "只挂上了应用级窗口（$type），说明无障碍没开、2032 挂不上；" +
                        "红块会被原厂竖栏压住属正常，去开启无障碍服务后再测"
            }
            last = r.exceptionOrNull()
        }
        return "测试块挂载失败：" + (last?.javaClass?.simpleName ?: "?") + " " + last?.message
    }

    /** 跳系统「显示在其他应用上层」授权页。 */
    fun openPermissionPage(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        // 先试带包名的页（能直接落到本应用那一条），失败再试不带包名的总列表，
        // 有些车机 ROM 只实现后者。
        val ok = runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (ok) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { markError("打不开悬浮窗授权页：${it.message}") }
    }
}
