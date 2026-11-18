package com.h6n.launcher.core

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.main.OverlayHost
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 侧边手势：**左/右边缘横滑 = 返回键、底边上滑 = 回桌面**。
 *
 * ## 为什么用「三条细边窗口」而不是一个全屏窗口
 * 全屏透明窗口会吃掉整个屏幕的触摸，底下的应用就点不动了 —— 这条在 6.0.35 给
 * 导航悬浮窗去掉 `FLAG_NOT_TOUCHABLE` 时已经踩过一次。所以这里只在三条边上各叠
 * 一条**很窄**的窗口（默认 10dp），手势从边上起手才归我们，屏幕中间完全是原应用。
 *
 * ```
 *  ┌──────────────────────────────┐
 *  │▌                            ▌│  ← 左右各一条，宽 edgeW
 *  │▌      应用区域（不拦截）      ▌│
 *  │▌                            ▌│
 *  ├──────────────────────────────┤
 *  │▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁│  ← 底边一条，高 edgeW
 *  └──────────────────────────────┘
 * ```
 *
 * ## 判定
 * DOWN 记起点，UP 时看位移：
 * - 左边缘：`dx ≥ swipeMin` → **返回键**（6.0.105 起，原来这条是拉状态栏）
 * - 左边缘：`dy ≥ swipeMin`（向下划）→ 仍保留拉车机状态栏，别把老操作弄丢了
 * - 右边缘：`dx ≤ -swipeMin` → 返回
 * - 底边缘：`dy ≤ -swipeMin` → 回桌面
 *
 * 只看主方向位移，不做速度判定：车机手指滑动比手机慢很多，用速度阈值会时灵时不灵。
 * 反方向的滑动（比如左边缘往上滑）一律不动作，让用户不至于误触。
 *
 * ## 6.0.105：滑动过程有指示条
 * 三条边是透明窗口，以前滑的时候屏幕上什么都看不见，用户不知道「滑够了没有」。
 * 现在手指一按下就在边上冒出一条发光胶囊，长度和不透明度跟着滑动进度长：
 * - 没滑够 = 冷蓝色（半透明）
 * - 滑够了 = 变绿（可以松手了）
 * 抬手后 200ms 淡出。纯 Paint 绘制，没有额外的窗口，也没有离屏层。
 *
 * ## 权限
 * 和悬浮按钮同一套：优先无障碍 2032，否则 2038 + 悬浮窗权限。
 * 都没有就整套不挂，设置页状态行会写明原因。
 */
object EdgeGesture {

    private const val TAG = "H6EdgeGesture"

    private const val DIR_RIGHT = 0
    private const val DIR_LEFT = 1
    private const val DIR_UP = 2

    @Volatile
    var attached: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var uiCtx: Context? = null
    private var wm: WindowManager? = null
    private var usingType = 0
    private var edgeW = 10
    private var swipeMin = 36
    private var want = booleanArrayOf(false, false, false) // 左 / 右 / 底

    private val views = arrayOfNulls<Strip>(3)

    /** 三条边的动作，与 [views] 下标一一对应。 */
    private val actions = arrayOf(AutoActions.BACK, AutoActions.BACK, AutoActions.HOME)

    // ---------------- 对外 ----------------

    fun sync(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        main.post { apply(app) }
    }

    /** 无障碍宿主变了：窗口必须重挂，否则跟着旧 WindowManager 一起消失。 */
    fun onHostChanged(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        main.post {
            detach()
            apply(app)
        }
    }

    fun describe(ctx: Context): String {
        val cfg = cfgOf(ctx)
        if (cfg?.bool("auto.edgeOn", true) != true) return "已在设置里关闭"
        if (attached) {
            val on = ArrayList<String>(3)
            if (want[0]) on += "左·返回"
            if (want[1]) on += "右·返回"
            if (want[2]) on += "底·桌面"
            if (on.isEmpty()) return "三条边都关掉了"
            val where = if (usingType == TYPE_A11Y) "无障碍 2032" else "应用级 $usingType"
            return on.joinToString(" / ") + "（$where）"
        }
        if (lastError.isNotEmpty()) return lastError
        return "未挂上"
    }

    // ---------------- 挂 / 摘 ----------------

    private fun apply(app: Context) {
        appCtx = app
        val cfg = cfgOf(app)
        if (cfg?.bool("auto.edgeOn", true) != true) {
            detach()
            return
        }
        edgeW = cfg?.int("auto.edgeW", 10)?.coerceIn(4, 40) ?: 10
        swipeMin = cfg?.int("auto.swipeMin", 36)?.coerceIn(16, 160) ?: 36
        want[0] = cfg?.bool("auto.edgeLeft", true) ?: true
        want[1] = cfg?.bool("auto.edgeRight", true) ?: true
        want[2] = cfg?.bool("auto.edgeBottom", true) ?: true

        if (!want[0] && !want[1] && !want[2]) {
            detach()
            return
        }

        val ui = scaledCtx(app) ?: return

        if (attached) {
            // 配置变了（宽度 / 阈值 / 单边开关）：全部重挂最省事，窗口只有三条
            detach()
        }

        val tries = ArrayList<Pair<WindowManager, Int>>(2)
        OverlayHost.wm?.let { tries.add(it to TYPE_A11Y) }
        if (canDraw(app)) {
            val own = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (own != null) {
                val t = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                tries.add(own to t)
            }
        }
        if (tries.isEmpty()) {
            lastError = "无障碍未开启，且没有悬浮窗权限"
            return
        }

        for ((manager, t) in tries) {
            val added = ArrayList<Strip>(3)
            var ok = true
            for (i in 0..2) {
                if (!want[i]) continue
                val v = strip(ui, i)
                val r = runCatching { manager.addView(v, lp(ui, i, t)) }
                if (r.isFailure) {
                    ok = false
                    lastError = "挂载失败：" + (r.exceptionOrNull()?.javaClass?.simpleName ?: "?")
                    break
                }
                added += v
            }
            if (ok) {
                wm = manager
                usingType = t
                attached = true
                lastError = ""
                Log.i(TAG, "侧边手势已挂载 type=$t，边数=${added.size}")
                return
            }
            // 挂到一半失败：把已经挂上的撤掉，换下一条通道重试
            for (v in added) runCatching { manager.removeView(v) }
        }
        Log.w(TAG, lastError)
    }

    private fun strip(ui: Context, index: Int): Strip {
        views[index]?.let { return it }
        val v = Strip(ui, index).apply {
            minPx = ui.dp(swipeMin)
            onFire = { act -> AutoActions.performAndToast(ui.applicationContext, act) }
        }
        views[index] = v
        return v
    }

    private fun lp(ui: Context, index: Int, type: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            /*
             * 三条边都不能带 FLAG_NOT_TOUCHABLE（那就收不到手势了），
             * 但必须带 FLAG_NOT_TOUCH_MODAL：否则窗口会把整屏的触摸都截下来。
             */
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val w = ui.dp(edgeW)
            when (index) {
                0 -> { // 左
                    gravity = Gravity.TOP or Gravity.START
                    x = 0; y = 0
                    width = w; height = WindowManager.LayoutParams.MATCH_PARENT
                }
                1 -> { // 右
                    gravity = Gravity.TOP or Gravity.END
                    x = 0; y = 0
                    width = w; height = WindowManager.LayoutParams.MATCH_PARENT
                }
                else -> { // 底
                    gravity = Gravity.BOTTOM or Gravity.START
                    x = 0; y = 0
                    width = WindowManager.LayoutParams.MATCH_PARENT; height = w
                }
            }
        }

    private fun detach() {
        val manager = wm
        // 不管 attached 标记如何，都尝试把三条边撤干净，避免残留窗口吃掉触摸
        for (i in views.indices) {
            val v = views[i] ?: continue
            if (manager != null) runCatching { manager.removeView(v) }
        }
        views.fill(null)
        attached = false
        wm = null
        usingType = 0
    }

    // ---------------- 视图 ----------------

    /**
     * 一条边。窗口本身是透明的、只有 [edgeW] 宽（默认 10dp），
     * 手势滑动时在这一条里画一段发光胶囊当反馈。
     *
     * 为什么反馈必须画在窗口内部：窗口只有 10dp 宽，超出边界的绘制会被裁掉；
     * 想画得更宽就得把窗口加宽，而加宽会抢走应用那一片的触摸 —— 得不偿失。
     * 10dp 宽的一条竖线在车机屏幕上已经足够醒目（尤其是变绿的时候）。
     */
    private class Strip(ctx: Context, private val dir: Int) : View(ctx) {

        var minPx = 0
        var onFire: ((String) -> Unit)? = null

        private var downX = 0f
        private var downY = 0f
        private var tracking = false

        /** 0..1：滑动进度（位移到 [minPx] 就是 1）。 */
        private var prog = 0f

        /** 指示条整体不透明度：抬手后由动画收到 0。 */
        private var glow = 0f
        private var fade: ValueAnimator? = null

        /** 指示条位置：竖条看 Y，横条看 X（视图坐标系）。 */
        private var anchor = 0f

        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rf = RectF()

        private val action: String
            get() = when (dir) {
                0 -> AutoActions.BACK
                1 -> AutoActions.BACK
                else -> AutoActions.HOME
            }

        init {
            setBackgroundColor(0x00000000)
            isClickable = true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    tracking = true
                    anchor = if (dir == 2) e.x else e.y
                    prog = 0f
                    show()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    track(e.rawX - downX, e.rawY - downY)
                    anchor = if (dir == 2) e.x else e.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return false
                    tracking = false
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val horizontal = abs(dx) >= abs(dy)
                    // 方向 + 距离都够才触发；四条判定各自独立，互不吃手势
                    val act = when {
                        dir == 0 && horizontal && dx >= minPx -> action      // 左缘右滑 → 返回
                        dir == 0 && !horizontal && dy >= minPx -> AutoActions.SHADE // 左缘下滑 → 状态栏
                        dir == 1 && horizontal && dx <= -minPx -> action    // 右缘左滑 → 返回
                        dir == 2 && !horizontal && dy <= -minPx -> action   // 底缘上滑 → 桌面
                        else -> null
                    }
                    if (act != null) {
                        prog = 1f
                        invalidate()
                        onFire?.invoke(act)
                    }
                    fadeOut()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    fadeOut()
                    return true
                }
            }
            return false
        }

        /** 更新进度：左缘看向右 / 向下、右缘看向左、底缘看向上。 */
        private fun track(dx: Float, dy: Float) {
            if (minPx <= 0) return
            val d = when (dir) {
                0 -> when {
                    abs(dx) >= abs(dy) && dx >= 0f -> dx
                    dy >= 0f -> dy
                    else -> -1f
                }
                1 -> if (dx <= 0f && abs(dx) >= abs(dy)) -dx else -1f
                else -> if (dy <= 0f && abs(dy) >= abs(dx)) -dy else -1f
            }
            if (d < 0f) {
                // 反方向：这一笔不是我们的手势，直接熄灭
                prog = 0f
                glow = 0f
                return
            }
            prog = (d / minPx).coerceIn(0f, 1f)
            glow = 1f
        }

        private fun show() {
            fade?.cancel()
            fade = null
            glow = 1f
            invalidate()
        }

        /** 抬手：200ms 淡出，别硬切。 */
        private fun fadeOut() {
            fade?.cancel()
            if (glow <= 0f) return
            val a = ValueAnimator.ofFloat(glow, 0f).apply {
                duration = 200L
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
            }
            a.start()
            fade = a
        }

        override fun onDetachedFromWindow() {
            fade?.cancel()
            fade = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val g = glow.coerceIn(0f, 1f)
            if (g <= 0.02f) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 滑够了 = 绿（可以松手了）；没滑够 = 冷蓝
            val ready = prog >= 1f
            bar.color = if (ready) 0xFF3ECF6E.toInt() else 0xFF4FD1FF.toInt()
            bar.alpha = ((if (ready) 235 else 150) * g).toInt().coerceIn(0, 255)

            // 长度跟着进度长：起点 56dp，滑满 190dp
            val d = context.resources.displayMetrics.density
            val len = minOf(
                if (dir == 2) w else h,
                (56f + prog * 134f) * d
            )
            val rad = minOf(w, h) / 2f
            if (dir == 2) {
                val cx = anchor.coerceIn(len / 2f, w - len / 2f)
                rf.set(cx - len / 2f, 0f, cx + len / 2f, h)
            } else {
                val cy = anchor.coerceIn(len / 2f, h - len / 2f)
                rf.set(0f, cy - len / 2f, w, cy + len / 2f)
            }
            canvas.drawRoundRect(rf, rad, rad, bar)
        }
    }

    // ---------------- 工具 ----------------

    private fun cfgOf(ctx: Context) =
        (ctx.applicationContext as? H6NApplication)?.config?.current()

    private fun canDraw(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

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

    private const val TYPE_A11Y = 2032
}
