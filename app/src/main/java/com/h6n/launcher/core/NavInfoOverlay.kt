package com.h6n.launcher.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.main.NavInfoView
import com.h6n.launcher.ui.main.OverlayHost
import kotlin.math.roundToInt

/**
 * 「其他应用在前台时」把红绿灯 / 车道信息挂成悬浮窗。
 *
 * ```
 * 桌面在前台      → 不挂（车速卡右侧已经有同一份信息了，挂上就是两份）
 * 高德车机版前台  → 不挂（用户明确要求：用高德自己的界面看）
 * 其它任意应用    → 挂（右上方一块深色小面板）
 * ```
 *
 * ## 为什么不用前台服务
 * 数据源 [AmapNavi] 的广播接收器挂在 `Application` 上，窗口借的是**无障碍服务的
 * WindowManager**（[OverlayHost]）—— 两者本来就活在桌面进程里，桌面是 HOME，
 * 系统不会随手回收。再起一个前台服务只会多一条常驻通知，没有收益。
 * 代价：无障碍没开时只能退到应用级窗口 [TYPE_APPLICATION_OVERLAY]，那种情况需要
 * 「显示在其他应用上层」权限，且进程被回收后窗口就没了。
 *
 * ## 显示判定优先级
 * 1. 无障碍能读到前台包名（[H6A11y.foregroundPkg] 非空）→ 就按包名判，最准；
 * 2. 读不到（无障碍没开）→ 用「桌面是否在前台」（[desktopFront]，由 MainActivity 维护）
 *    和「高德自己报的前后台」（10019，[AmapNavi.amapForeground]）兜底。
 *
 * 线程：外部随便调，内部一律切到主线程再碰窗口。
 */
object NavInfoOverlay {

    private const val TAG = "H6NavInfoOverlay"

    /** 桌面（MainActivity）当前是否在前台。由 MainActivity 的 onResume / onPause 维护。 */
    @Volatile
    var desktopFront: Boolean = false

    @Volatile
    var attached: Boolean = false
        private set

    /** 最近一次没挂上的原因（设置页诊断用）。 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 临时压住（true = 不显示）。设置页整屏面板期间用它，
     * 否则整屏面板右上角会被这块小悬浮窗盖住。
     *
     * 为什么不只靠前台包名：无障碍没开时读不到包名，桌面 onPause 那一刻
     * 会被判成"切到别的应用"，悬浮窗就会在设置页上闪一下。
     */
    @Volatile
    var suppressed: Boolean = false
        private set

    fun setSuppressed(ctx: Context, on: Boolean) {
        if (suppressed == on) return
        suppressed = on
        sync(ctx)
    }

    private val main = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var uiCtx: Context? = null
    private var container: FrameLayout? = null
    private var view: NavInfoView? = null
    private var wm: WindowManager? = null
    private var usingType = 0
    private var listening = false
    private var posted = false

    /**
     * 最近一个「别人的」前台包名（既不是桌面自己、也不是高德）。
     *
     * ★ 6.0.105：为什么要单独记一份。窗口挂的是 `TYPE_ACCESSIBILITY_OVERLAY`，
     * 它同样是本应用进程的窗口，无障碍服务有时会给我们回一个「前台 = 本应用」的
     * 窗口事件（尤其车机 ROM 的窗口实现）。原来 [otherAppFront] 只看
     * `H6A11y.foregroundPkg`，一收到这种事件就判成「回到自家界面了」并立刻摘窗口 ——
     * 表现正是「切到别的应用，悬浮窗闪一下就消失」。
     * 现在自家包只当「不知道」，显隐仍按最近一个外来应用算。
     */
    @Volatile
    private var lastOther: String = ""

    /** 延迟摘窗的兜底任务：短暂的状态抖动不该把窗口摘掉。 */
    private var detachTask: Runnable? = null

    private val dataTick: () -> Unit = {
        appCtx?.let { post(it) }
    }

    /** 前台应用变了 / 桌面进出前台 / 无障碍宿主变了，都可以无脑调这个。 */
    fun sync(ctx: Context) {
        post(ctx.applicationContext)
    }

    /**
     * 无障碍服务报来「前台换成了 [pkg]」。
     *
     * 自家包不算「切到别的应用」，只记下来（桌面/设置页各自的 onResume 会决定显隐），
     * 免得我们自己的悬浮窗窗口事件把自己判成"回到桌面"。
     */
    fun onForegroundPkg(ctx: Context, pkg: String) {
        val app = ctx.applicationContext
        if (pkg.isNotEmpty() && pkg != app.packageName) {
            lastOther = pkg
        }
        post(app)
    }

    private fun post(app: Context) {
        ensureListening(app)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply(app)
            return
        }
        if (posted) return
        posted = true
        main.post {
            posted = false
            apply(app)
        }
    }

    private fun ensureListening(app: Context) {
        appCtx = app
        if (listening) return
        listening = true
        // 数据每秒都在变（倒计时），订阅一次就够，之后靠 apply() 里的 dedup 去重
        AmapNavi.addListener(dataTick)
    }

    // ---------------- 判定 ----------------

    private fun wantShow(ctx: Context): Boolean {
        if (suppressed) return false
        if (!AmapNavi.overlayOn(ctx)) return false
        if (!AmapNavi.snapshot().hasData) return false
        if (!otherAppFront(ctx)) return false
        return OverlayHost.available || canDraw(ctx)
    }

    /**
     * 现在前台是不是「既不是桌面、也不是高德」的第三个应用。
     *
     * 桌面和高德都在前台时都不挂：桌面有自己的卡片，高德有自己的界面。
     *
     * ★ 6.0.105：前台包名是**我们自己**时不能直接判「回到桌面」—— 我们挂的
     *   2032 悬浮窗、悬浮球、侧边手势都是本应用窗口，车机 ROM 会为此发一条
     *   「前台 = 本应用」的窗口事件。那时按 [lastOther] 判：最近一个外来应用不是
     *   高德就继续显示，桌面的 onResume 会用 [desktopFront] 把它收掉。
     */
    private fun otherAppFront(ctx: Context): Boolean {
        val fg = H6A11y.foregroundPkg
        if (fg.isEmpty()) {
            // 无障碍没开：桌面自己报 + 高德自己报，两个都否才认为是"别的应用"
            return !desktopFront && !AmapNavi.amapForeground()
        }
        if (fg != ctx.packageName) return fg != AmapNavi.PKG
        if (desktopFront) return false
        return lastOther.isNotEmpty() && lastOther != AmapNavi.PKG
    }

    private fun canDraw(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    // ---------------- 挂 / 摘 ----------------

    private fun apply(app: Context) {
        if (!wantShow(app)) {
            hideSoon(app)
            return
        }
        cancelHide()
        val ui = scaledCtx(app) ?: return
        val box = ensureViews(ui)
        val v = view ?: return
        val s = AmapNavi.snapshot()
        v.setData(
            s.navi && AmapNavi.enabled(app),
            if (AmapNavi.lightOn(app)) s.light else null,
            if (AmapNavi.laneOn(app)) s.lanes else emptyList(),
            if (AmapNavi.enabled(app)) s.info else AmapNavi.Info()
        )

        if (attached) {
            // 只是数据 / 配置变了：窗口已经挂在正确的位置上，重排一下尺寸就够
            val p = lp(ui, usingType)
            runCatching { wm?.updateViewLayout(box, p) }
                .onFailure {
                    lastError = "更新窗口失败：${it.javaClass.simpleName}"
                    detach()
                }
            return
        }

        // 优先无障碍宿主 + 2032（不需要悬浮窗权限、也不会被原厂竖栏压住）
        val tries = ArrayList<Pair<WindowManager, Int>>(2)
        OverlayHost.wm?.let { tries.add(it to NavOverlay.TYPE_A11Y) }
        if (canDraw(app)) {
            val own = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (own != null) {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                tries.add(own to type)
            }
        }
        if (tries.isEmpty()) {
            lastError = "无障碍未开启，且没有悬浮窗权限"
            return
        }

        var last: Throwable? = null
        for ((manager, type) in tries) {
            val p = lp(ui, type)
            val r = runCatching { manager.addView(box, p) }
            if (r.isSuccess) {
                wm = manager
                usingType = type
                attached = true
                lastError = ""
                Log.i(TAG, "导航信息悬浮窗已挂载 type=$type")
                return
            }
            last = r.exceptionOrNull()
        }
        lastError = "挂载失败：" + (last?.javaClass?.simpleName ?: "?") + " " + (last?.message ?: "")
        Log.w(TAG, lastError)
    }

    private fun ensureViews(ui: Context): FrameLayout {
        val v = view ?: NavInfoView(ui).apply {
            // 悬浮窗盖在别人的界面上，底色是深色玻璃 → 固定白字，不跟桌面昼夜主题
            setInk(0xFFFFFFFF.toInt())
            // 悬浮窗没有车机车速表可看，巡航态在最左边补一个高德报的车速
            setShowSpeed(true)
        }.also { view = it }

        val box = container ?: FrameLayout(ui).apply {
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            background = panel(ui)
        }.also { container = it }
        if (box.indexOfChild(v) < 0) {
            box.addView(v, FrameLayout.LayoutParams(WRAP, WRAP))
        }
        // 可拖动：窗口去掉了 FLAG_NOT_TOUCHABLE，触摸事件进得来
        if (box.tag == null) {
            box.tag = "drag"
            installDrag(box, ui)
        }
        return box
    }

    // ---------------- 拖动 ----------------

    /** 位置按 **dp** 存（屏幕左边 / 上边为原点），换分辨率或重启后位置不变。 */
    private var posX = Int.MIN_VALUE
    private var posY = Int.MIN_VALUE
    private var dragBaseX = 0
    private var dragBaseY = 0
    private var dragFromX = 0f
    private var dragFromY = 0f

    private fun ensurePos(ctx: Context) {
        if (posX != Int.MIN_VALUE) return
        val snap = (ctx.applicationContext as? H6NApplication)?.config?.current()
        val defX = snap?.int("navi.overlayX", 28) ?: 28
        val defY = snap?.int("navi.overlayY", 96) ?: 96
        val sp = ctx.applicationContext
            .getSharedPreferences("h6_navi_overlay", Context.MODE_PRIVATE)
        posX = sp.getInt("x", defX)
        posY = sp.getInt("y", defY)
    }

    private fun savePos(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSharedPreferences("h6_navi_overlay", Context.MODE_PRIVATE)
                .edit().putInt("x", posX).putInt("y", posY).apply()
        }
    }

    /**
     * 拖动：`ACTION_DOWN` 记下起点，`MOVE` 按像素差换算成 dp 移动窗口，
     * 抬手写盘。窗口贴着屏幕边就停住，不会拖丢。
     */
    private fun installDrag(box: View, ui: Context) {
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragBaseX = posX
                    dragBaseY = posY
                    dragFromX = e.rawX
                    dragFromY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = ui.resources.displayMetrics.density
                    moveTo(
                        dragBaseX + ((e.rawX - dragFromX) / d).roundToInt(),
                        dragBaseY + ((e.rawY - dragFromY) / d).roundToInt(),
                        ui, v
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePos(ui)
                    true
                }
                else -> false
            }
        }
    }

    /** 设置页「复位悬浮窗位置」：清掉记忆的位置，回到配置里的默认点（右上角）。 */
    fun resetPos(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            app.getSharedPreferences("h6_navi_overlay", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
        posX = Int.MIN_VALUE
        posY = Int.MIN_VALUE
        main.post {
            detach()
            apply(app)
        }
    }

    private fun moveTo(x: Int, y: Int, ui: Context, box: View) {        val dm = ui.resources.displayMetrics
        val d = dm.density
        val maxX = ((dm.widthPixels - box.width) / d).toInt().coerceAtLeast(0)
        val maxY = ((dm.heightPixels - box.height) / d).toInt().coerceAtLeast(0)
        val nx = x.coerceIn(0, maxX)
        val ny = y.coerceIn(0, maxY)
        if (nx == posX && ny == posY) return
        posX = nx
        posY = ny
        if (!attached) return
        runCatching { wm?.updateViewLayout(box, lp(ui, usingType)) }
    }

    private fun panel(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(PANEL_BG)
        cornerRadius = ctx.dp(20).toFloat()
        setStroke(ctx.dp(2), 0x33FFFFFF)
    }

    private fun lp(ui: Context, type: Int): WindowManager.LayoutParams {
        ensurePos(ui)
        return WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            /*
             * v6.0.35：用户要求「悬浮窗可以随意拖动」→ 去掉 FLAG_NOT_TOUCHABLE。
             * 代价是面板下方那一点点区域不再把点击透传给底下的应用（面板本身只有
             * 一小块，且没有任何点击交互，拖动时正是要吃掉触摸）。
             * FLAG_NOT_FOCUSABLE 保留：不抢输入焦点、不弹输入法。
             */
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            // 左上角为原点，拖动时 x/y 直接加减像素，比 TOP|END 的「距右边」好算
            gravity = Gravity.TOP or Gravity.START
            this.x = ui.dp(posX)
            this.y = ui.dp(posY)
            width = WRAP
            height = WRAP
        }
    }

    private fun detach() {
        cancelHide()
        if (!attached) return
        val box: View? = container
        val ok = runCatching { wm?.removeView(box) }.isSuccess
        attached = false
        wm = null
        usingType = 0
        if (!ok) {
            // 摘不下来就整块丢掉，别留一个"有父容器"的 View 下次 addView 抛异常
            container = null
            view?.release()
            view = null
        }
    }

    /**
     * 该收了 —— 但**不要立刻摘**。
     *
     * 切换应用、导航数据换帧、无障碍报一次「前台=本应用」，都可能让 [wantShow]
     * 短暂为 false。以前这里是直接 `detach()`，实车表现就是「闪一下就消失」。
     * 现在等 [HIDE_DELAY_MS] 再复查一次：期间只要又该显示，窗口连一次都没被摘过。
     * 只有「设置里关了 / 设置页压住」这种确定性收起才立刻执行。
     */
    private fun hideSoon(app: Context) {
        if (suppressed || !AmapNavi.overlayOn(app)) {
            cancelHide()
            detach()
            return
        }
        if (detachTask != null) return
        val r = Runnable {
            detachTask = null
            // 复查一次：延迟期间状态可能又变回来了
            if (!wantShow(app)) detach()
        }
        detachTask = r
        main.postDelayed(r, HIDE_DELAY_MS)
    }

    private fun cancelHide() {
        detachTask?.let { main.removeCallbacks(it) }
        detachTask = null
    }

    /**
     * 无障碍服务连上 / 断开时调：窗口是挂在它的 WindowManager 上的，必须重挂。
     * 断开时窗口会随服务一起消失，所以先清状态再按新宿主重来。
     */
    fun onHostChanged(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            detach()
            apply(app)
        }
    }

    /** 设置页「导航信息悬浮窗」状态行。 */
    fun describe(ctx: Context): String {
        if (!AmapNavi.overlayOn(ctx)) return "已在设置里关闭"
        if (!AmapNavi.snapshot().hasData) return "暂无导航数据（打开高德车机版开始导航）"
        if (attached) {
            return if (usingType == NavOverlay.TYPE_A11Y)
                "正在显示（无障碍窗口 2032）" else "正在显示（应用级窗口 $usingType）"
        }
        if (lastError.isNotEmpty()) return lastError
        return if (otherAppFront(ctx)) "应显示但没挂上" else "当前不需要显示"
    }

    // ---------------- 工具 ----------------

    /** 和桌面同一套「720 设计稿」密度，悬浮窗才会和桌面卡片一样大。 */
    private fun scaledCtx(app: Context): Context? {
        uiCtx?.let { return it }
        val base = app.applicationContext ?: return null
        return runCatching {
            val dm = base.resources.displayMetrics
            val k = (dm.heightPixels / 720f).coerceIn(0.6f, 3.5f)
            val conf = Configuration(base.resources.configuration)
            conf.densityDpi = (160f * k).roundToInt()
            conf.fontScale = 1f
            base.createConfigurationContext(conf)
        }.onFailure { Log.w(TAG, "创建缩放上下文失败：${it.message}") }
            .getOrNull()?.also { uiCtx = it }
    }

    private const val WRAP = -2

    /** 该收时等这么久再复查（见 [hideSoon]）：压住「闪一下就消失」。 */
    private const val HIDE_DELAY_MS = 1200L

    /** 深色玻璃底：盖在导航 / 音乐这些深浅不一的界面上都看得清。 */
    private val PANEL_BG = 0xCC0E1622.toInt()
}
