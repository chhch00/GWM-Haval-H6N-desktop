package com.h6n.launcher.ui.main

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.R
import com.h6n.launcher.core.FactoryHome
import com.h6n.launcher.core.NavOverlay
import com.h6n.launcher.core.WallpaperStore
import com.h6n.launcher.ui.common.NightMode
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.settings.SettingsActivity
import java.util.Date
import kotlin.math.roundToInt

/**
 * 覆盖原厂导航栏的常驻悬浮层（旧版 `SidebarHomeService` 的 Kotlin 版）。
 *
 * ```
 * ┌ 悬浮窗（TYPE_ACCESSIBILITY_OVERLAY，贴左边，和桌面 dock 同一位置/宽度）
 * │  [原车桌面]      ← 只在覆盖层里出现，桌面上没有
 * │   07:54
 * │   ▣ ▣ ▣          ← 和桌面停靠栏共用一份 dock_slots 数据
 * │   ▣ ▣ ▣
 * └
 * ```
 *
 * ## 为什么窗口挂在无障碍服务上（★ 2026-11-11 车机实测）
 * 2032 需要**无障碍服务的窗口 token**，本服务是普通前台 Service，用自己的
 * WindowManager 去加会抛 `BadTokenException: token null is not for an application`；
 * 退到 2038 又能挂上但被原厂竖栏压住，用户看到的就是"开关开了什么也没变"。
 * 所以窗口统一借 [OverlayHost] 里那个（无障碍服务提供的）WindowManager 挂，
 * 无障碍没开时才退回本服务自己的 WindowManager + 2038。
 *
 * ## 为什么是前台服务
 * 覆盖层要在导航/音乐等**别的应用**上面显示，桌面不在前台时也得活着，
 * 所以必须前台服务 + 常驻通知，否则系统一回收就没了。
 *
 * ## 什么时候显示
 * 开关打开后**桌面自己的 dock 会隐藏**，这条侧栏统一由覆盖层提供：
 * - 桌面上 → 显示（替代被隐藏的 dock）
 * - 别的应用上 → 显示（盖住原厂那条竖栏）
 * - 设置页 → 收起（整屏面板，盖上就挡选项了）
 *
 * 结论由 [NavOverlay.suppressed] 统一持有，服务每次要显示前都读它 ——
 * 服务是延迟起来的，启动那一刻必须知道当前界面怎么判的。
 *
 * ## 两个"不要"
 * - **不要在这里回读 `sidebar.homeOn` 来决定要不要干活**：设置页刚落盘时
 *   DataStore 的快照还没回流，回读到旧值 `false` 就会 `stopSelf()`，
 *   表现就是"开关点开了、什么都没发生"。起停只认 [NavOverlay] 下发的动作。
 * - **不要用自适应图标当通知小图标**：`mipmap-anydpi-v26/ic_launcher.xml` 在
 *   API 26+ 上会被通知栏拒收，`startForeground` 抛异常 → 服务被系统判"没进前台"。
 */
class NavOverlayService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    /** 窗口实际挂在哪个 WindowManager 上（无障碍宿主或本服务自己的），removeView 必须用同一个。 */
    private var windowWm: WindowManager? = null

    /** 当前窗口是不是挂在无障碍宿主上（决定 2032 是否有效）。 */
    private var hosted = false

    private var root: FrameLayout? = null
    private var dock: DockView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var attached = false

    /** 当前窗口实际是用哪个 type 挂上去的（2032 / 2038），诊断行会显示。 */
    private var usingType = 0

    /** 用和桌面同一套"720 设计稿"密度的 Context，覆盖层才会和 dock 严丝合缝。 */
    private val uiCtx: Context by lazy {
        val base = applicationContext
        val dm = base.resources.displayMetrics
        val k = (dm.heightPixels / 720f).coerceIn(0.6f, 3.5f)
        val conf = Configuration(base.resources.configuration)
        conf.densityDpi = (160f * k).roundToInt()
        conf.fontScale = 1f
        base.createConfigurationContext(conf)
    }

    override fun onCreate() {
        super.onCreate()
        NavOverlay.markRunning(true)
        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        startAsForeground()
        tick()
        // 冷启动（桌面 onResume 拉起服务）：桌面此时已经把 dock 让位了，
        // 覆盖层得马上补上来，否则桌面上会有几百毫秒一条侧栏都没有。
        if (!NavOverlay.suppressed) show()
        // 自愈：1.2 秒后再确认一次到底挂上没有（见 [ensureTask]）
        main.removeCallbacks(ensureTask)
        main.postDelayed(ensureTask, 1200L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent 为空 = 系统按 START_STICKY 把我们拉回来的（进程被杀后重建）。
        // 此时开关可能已经被关掉了，只有这条路径按配置复核一次。
        if (intent == null) {
            if (!NavOverlay.enabled(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            rebuild()
            return START_STICKY
        }
        when (intent.action) {
            NavOverlay.ACTION_VISIBLE -> show()
            NavOverlay.ACTION_HIDDEN -> hide()
            NavOverlay.ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> rebuild() // REFRESH / 冷启动：按当前配置重画，显示状态跟着 suppressed 走
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NavOverlay.markRunning(false)
        main.removeCallbacksAndMessages(null)
        hide()
        runCatching { stopForeground(true) }
        dock = null
        root = null
        super.onDestroy()
    }

    // ---------------- 窗口 ----------------

    private fun rebuild() {
        val app = applicationContext as? H6NApplication ?: return
        val snap = app.config.current()
        val t = Theme(uiCtx, snap, NightMode.isNight(snap))

        val d = dock ?: buildDock(t).also { dock = it }
        d.applyTheme(t)
        d.bindClock(Date())
        // 主题/配置变化（dockW/dockX/coverW 改了）时，背板和 DockView 位置也要跟着刷
        root?.let { applyBacking(it, t) }
        d.layoutParams = DockView.overlayLayoutParams(uiCtx, t, FACTORY_BAR_PX)

        val lpp = lp ?: buildParams(t, NavOverlay.TYPE_A11Y).also { lp = it }

        // 宿主可用性变了（无障碍刚开 / 刚被关）：窗口类型必须换，
        // 光 updateViewLayout 是没用的 —— 当年"开了无障碍还是压不住"就卡在这儿。
        if (attached && hosted != OverlayHost.available) {
            hide()
        } else if (attached) {
            // 配置改了宽度/位置：已挂上的窗口也要跟着挪
            runCatching { windowWm?.updateViewLayout(root, lpp) }
        }
        if (NavOverlay.suppressed) hide() else show()
    }

    private fun buildDock(t: Theme): DockView {
        val d = DockView(
            uiCtx,
            onSettings = {
                runCatching {
                    startActivity(
                        Intent(this, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onWallpaper = {
                // 桌面 dock 让位后桌面上没有时钟可长按了，这里直接开到设置的外观页
                runCatching {
                    startActivity(
                        Intent(this, SettingsActivity::class.java)
                            .putExtra("tab", 0)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onFactory = {
                // 四种方式全失败时才提示，正常情况点了就切走，不弹多余的 toast
                if (!FactoryHome.open(this)) {
                    main.post {
                        Toast.makeText(
                            this, "没能启动原车桌面（com.beantechs.launcher 未响应）", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            // 6.0.31：点击那一刻才取当前真正挂载覆盖层的 WM / type（会随无障碍启停变化）
            pickerHost = DockPicker.PickerHost(
                wm = { windowWm },
                type = { usingType }
            )
        )
        return d
    }

    private fun buildParams(t: Theme, type: Int): WindowManager.LayoutParams {
        val snap = t.snap
        // ★ 覆盖原厂左侧竖栏的关键：窗口必须从屏幕左上角 (0,0) 起、贯穿全高、
        //   宽度与原车竖栏严格相等。y 直接 0、高度取全屏，配合 FLAG_LAYOUT_NO_LIMITS 顶满上下。
        // ★ 6.0.87：原车左侧竖栏（SystemUI `com.bean.dock.DockWindowManager`）的窗口宽度
        //   是反编译写死的 `const/16 v1, 0x80` —— 128 **物理像素**，不随 density 缩放。
        //   旧版把「128」当 dp、又按 coverW=144dp × uiCtx.density(1.5)=216px 去盖，
        //   比原车栏宽出 88px，表现就是「覆盖层过长、右边多出一条」。现在窗口宽度直接
        //   对齐原车栏的物理像素，且不再让配置把它撑大（用户配更小的值仍尊重）。
        val coverWpx = uiCtx.dp(snap.int("sidebar.coverW", 144))
        val w = coverWpx.coerceAtMost(FACTORY_BAR_PX)
        val screenH = resources.displayMetrics.heightPixels
        val h = screenH

        return WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            // NOT_FOCUSABLE：不抢输入焦点（否则车机的返回键/旋钮会先给悬浮层）
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            this.x = 0
            this.y = 0
            width = w
            height = h
        }
    }

    /**
     * 给覆盖层窗口容器铺一层**不透明背板**，把后面的原厂竖栏彻底挡死，同时**跟上桌面的颜色**。
     *
     * 窗口本身是 TRANSLUCENT、DockView 背景又是透明玻璃（GlassBg fillTop/fillBottom=透明），
     * 不叠这层背板，工厂竖栏就会从玻璃后面透出来 —— 表现就是"开关开了、我们的图标也显示，
     * 但工厂竖栏没被盖住"。老版靠内部 visual 视图的不透明背景实现同样的遮挡。
     *
     * ## 颜色怎么来（6.0.61）
     * 老版固定用 `panelTop/panelBot`，夜间就是 #232528→#131415 近黑 —— 桌面一铺浅色壁纸，
     * 左侧就凭空多出一条黑板（用户实机截图）。现在按优先级取 **当前真实视觉色**：
     * 1. 铺着壁纸 → 取壁纸左侧条带的上/中/下三段平均色（[WallpaperStore.leftBand]），
     *    连壁纸蒙层一起算进去，背板与旁边像素同色，看上去就是壁纸透了过来；
     * 2. 主题渐变（没壁纸）或取色失败 → 用 `screenTop/Mid/Bot`，和桌面底色同一套。
     * 同时把背板明度交给 [DockView.setBackingLum]，让时钟/加号/描边自动换成深色或白色。
     */
    private fun applyBacking(container: FrameLayout, t: Theme) {
        val screenWpx = resources.displayMetrics.widthPixels
        // 6.0.87：取色条带与窗口宽度同源，都对齐原车栏的 128 物理像素
        val bandPct = if (screenWpx > 0) FACTORY_BAR_PX.toFloat() / screenWpx else 0.1f

        val ch = WallpaperStore.get(this)
        val band = WallpaperStore.leftBand(
            this, ch, bandPct, WallpaperStore.scrimColor(ch.scrim, t.lightText())
        )

        val top: Int
        val mid: Int
        val bot: Int
        val lum: Float
        if (band != null) {
            top = band.top
            mid = band.mid
            bot = band.bot
            lum = band.lum
        } else {
            top = t.color("screenTop", if (t.night) 0xFF141A22.toInt() else 0xFFDCE4F0.toInt())
            mid = t.color("screenMid", if (t.night) 0xFF1B222C.toInt() else 0xFFC6D3E6.toInt())
            bot = t.color("screenBot", if (t.night) 0xFF232C38.toInt() else 0xFFAABAD4.toInt())
            lum = 0.299f * Color.red(mid) / 255f +
                0.587f * Color.green(mid) / 255f +
                0.114f * Color.blue(mid) / 255f
        }

        // 6.0.87：窗口宽度已与原车栏严格相等（128px），没有"余量"可渐隐 ——
        // 若在这里留渐隐，原厂竖栏会从渐隐段透出来，正是要治的"盖不住"。
        container.background = BackdropBg(top, mid, bot, 0f)
        dock?.setBackingLum(lum)
    }

    /** 一个候选挂载目标：用谁的 WindowManager、什么窗口类型、是不是无障碍宿主。 */
    private class Target(val wm: WindowManager, val type: Int, val hosted: Boolean)

    /**
     * 挂载目标，**按顺序试**。
     *
     * ★ 第一条必须是「无障碍宿主 + `TYPE_ACCESSIBILITY_OVERLAY`(2032)」：
     *   - 原厂左侧竖栏是系统级窗口，应用级 `TYPE_APPLICATION_OVERLAY`(2038) 会被它压住，
     *     表现就是"开关开了、窗口也挂上了、屏幕上什么也没变"；
     *   - 而 2032 只有**无障碍服务的 WindowManager** 挂得上，普通 Service 用自己的
     *     WindowManager 去加会抛 `BadTokenException: token null is not for an application`
     *     （VC402 车机实测，截图里那条 toast 就是它）。
     *     老版 `SidebarHomeService` 本身就是 AccessibilityService，所以它 0x7f0 一直好使。
     *
     * 无障碍没开时只有第二条可用：能显示、能被点，但压不住竖栏 —— 设置页会提示去开启。
     */
    private fun targets(): List<Target> {
        val out = ArrayList<Target>(2)
        OverlayHost.wm?.let { out.add(Target(it, NavOverlay.TYPE_A11Y, true)) }
        val own = wm ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (own != null) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            out.add(Target(own, type, false))
        }
        return out
    }

    /**
     * 请求显示。**延迟 250ms 再真正挂窗口**：
     * 桌面 onResume（回桌面）和设置页 onCreate/onPause 常常前后脚到，
     * 直接 addView 会在设置页上闪一下覆盖层。延迟后复查 [NavOverlay.suppressed] 即可避开。
     */
    private fun show() {
        if (NavOverlay.suppressed || attached) return
        tries = 0
        main.removeCallbacks(showTask)
        main.postDelayed(showTask, 250L)
    }

    private val showTask = Runnable { doShow() }

    private fun doShow() {
        if (NavOverlay.suppressed || attached) return
        val app = applicationContext as? H6NApplication ?: return
        val snap = app.config.current()
        val t = Theme(uiCtx, snap, NightMode.isNight(snap))
        val d = dock ?: buildDock(t).also { dock = it }
        d.applyTheme(t)
        d.bindClock(Date())

        // 窗口容器铺不透明背板（挡住后面的原厂竖栏），再按桌面停靠栏的同一套位置把 DockView 放进去，
        // 而不是让它铺满整窗 —— 这样我们的图标位置与桌面一致，工厂竖栏却被完整盖住。
        val container = root ?: FrameLayout(uiCtx).also { root = it }
        applyBacking(container, t)
        if (container.indexOfChild(d) < 0) {
            container.addView(d, DockView.overlayLayoutParams(uiCtx, t, FACTORY_BAR_PX))
        } else {
            d.layoutParams = DockView.overlayLayoutParams(uiCtx, t, FACTORY_BAR_PX)
        }

        // 依次试 [targets]：先「无障碍宿主 + 2032」（能压住原厂竖栏），
        // 挂不上再退本服务自己的 WindowManager + 2038
        var last: Throwable? = null
        for (target in targets()) {
            val p = buildParams(t, target.type)
            val r = runCatching { target.wm.addView(container, p) }
            if (r.isSuccess) {
                lp = p
                windowWm = target.wm
                hosted = target.hosted
                usingType = target.type
                attached = true
                NavOverlay.markAttached(true, target.type)
                NavOverlay.clearError()
                NavOverlay.notifyState(this)
                Log.i(TAG, "覆盖层已挂载 type=${target.type} hosted=${target.hosted}")
                return
            }
            last = r.exceptionOrNull()
            Log.w(TAG, "addView type=${target.type} 失败：${last?.javaClass?.simpleName} ${last?.message}")
        }
        // 全失败 / token 失效：服务退场，别在后台挂着一个看不见的常驻通知，
        // 同时把原因写进 NavOverlay.lastError（设置页「覆盖层状态」会显示）
        val why = (last?.javaClass?.simpleName ?: "?") + " " + (last?.message ?: "")
        NavOverlay.markError(
            if (!OverlayHost.available)
                "窗口挂载失败：$why；无障碍服务未开启，2032 压不住原厂竖栏"
            else "窗口挂载失败：$why"
        )
        NavOverlay.notifyState(this)
        stopSelf()
    }

    private fun hide() {
        main.removeCallbacks(showTask)
        if (!attached) return
        runCatching { windowWm?.removeView(root) }
        attached = false
        windowWm = null
        hosted = false
        NavOverlay.markAttached(false, usingType)
        NavOverlay.notifyState(this)
    }

    // ---------------- 自愈 ----------------

    /**
     * 每 1.5 秒看一次：服务活着、没被压住、窗口却没挂上 → 再试。
     *
     * 解决"打开开关后一直显示『正在挂载窗口…』但什么都不出现"：
     * 起服务的时机常常和设置页的 onPause / 桌面的 onResume 撞在一起，
     * 那一次 `show()` 可能因为 suppressed 还没放开而空跑，之后没人再叫它。
     * 这里给 8 次机会（约 12 秒），挂上就停。
     */
    private val ensureTask = object : Runnable {
        override fun run() {
            if (!NavOverlay.running) return
            if (!NavOverlay.suppressed && !attached && tries < 8) {
                tries++
                doShow()
            }
            if (!attached && tries < 8) main.postDelayed(this, 1500L)
        }
    }

    private var tries = 0

    // ---------------- 时钟 ----------------

    /** 15 秒一跳（车机时钟不走秒，15 秒足够保证分钟变化及时跟上）。 */
    private fun tick() {
        main.postDelayed({
            if (NavOverlay.running) {
                runCatching { dock?.bindClock(Date()) }
                tick()
            }
        }, 15_000L)
    }

    // ---------------- 前台通知 ----------------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "侧栏覆盖层", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "覆盖原厂导航栏时保持运行"
                setShowBadge(false)
            }
            runCatching { nm?.createNotificationChannel(ch) }
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            // 必须是单色 PNG/矢量小图标；用 mipmap 的自适应图标会被通知栏拒收
            .setSmallIcon(R.drawable.ic_notify_dock)
            .setContentTitle("哈弗国潮桌面")
            .setContentText("侧栏覆盖层运行中")
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
        runCatching { startForeground(NOTIFY_ID, n) }
            .onFailure { NavOverlay.markError("前台通知失败：${it.message}") }
    }

    private companion object {
        const val TAG = "H6NavOverlaySvc"
        const val CHANNEL = "h6_nav_overlay"
        const val NOTIFY_ID = 3801
        const val MATCH = -1

        /**
         * 原车左侧竖栏窗口的物理像素宽（反编译 `com.bean.dock.DockWindowManager`：
         * `const/16 v1, 0x80` → `LayoutParams.width = 128`，gravity=START、x=0、满高，
         * SystemUI 全程没有改写 density）。覆盖层窗口宽度必须与它严格相等：
         * 宽了会露一条"多余的边"，窄了盖不住原厂栏。
         */
        const val FACTORY_BAR_PX = 128
    }
}

/**
 * 覆盖层背板：三段竖向渐变（与桌面左侧同色）+ 右缘渐隐。
 *
 * ## 为什么不是 GradientDrawable
 * `GradientDrawable` 只能给一个方向的渐变；右缘那段横向渐隐需要把像素**清成透明**，
 * 只能在同一离屏层里用 `DST_OUT` 做（和 [com.h6n.launcher.ui.common.GlassBg]
 * 挖内圈是同一手法）。不透明是硬要求 —— 背板漏一点，原厂竖栏就透出来了。
 */
private class BackdropBg(
    private val top: Int,
    private val mid: Int,
    private val bot: Int,
    private val fadePx: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var cachedW = -1f
    private var cachedH = -1f
    private var vShader: LinearGradient? = null
    private var hShader: LinearGradient? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        if (w != cachedW || h != cachedH) {
            vShader = LinearGradient(
                0f, 0f, 0f, h, intArrayOf(top, mid, bot),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            hShader = if (fadePx > 0f) LinearGradient(
                Math.max(0f, w - fadePx), 0f, w, 0f,
                intArrayOf(0x00000000, 0xFF000000.toInt()), null, Shader.TileMode.CLAMP
            ) else null
            cachedW = w
            cachedH = h
        }

        if (hShader == null) {
            paint.shader = vShader
            canvas.drawRect(b, paint)
            paint.shader = null
            return
        }
        // DST_OUT 只作用于当前层，所以整块背板都得在离屏层里画
        val layer = canvas.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        paint.shader = vShader
        canvas.drawRect(b, paint)
        clear.shader = hShader
        canvas.drawRect(b, clear)
        clear.shader = null
        paint.shader = null
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
