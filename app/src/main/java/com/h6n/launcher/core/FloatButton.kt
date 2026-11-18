package com.h6n.launcher.core

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * 悬浮按钮：**单击返回键、长按回桌面**，可随意拖动（对齐车机「应用管家」的悬浮按钮）。
 *
 * ## 窗口
 * 优先借无障碍服务的 WindowManager 挂 `TYPE_ACCESSIBILITY_OVERLAY`(2032)：
 * 不需要「显示在其他应用上层」权限，也能压在原厂竖栏之上（原厂那条竖栏是 2032 层级）。
 * 无障碍没开时才退到 `TYPE_APPLICATION_OVERLAY`(2038)，此时必须有悬浮窗权限，
 * 且压不住原厂竖栏 —— 设置页的状态行会把这种情况写清楚。
 *
 * ## 手势
 * 自己算，不用 `GestureDetector`：
 * ```
 * DOWN  记下起点与按下时刻，起一个 520ms 的长按定时器
 * MOVE  位移超过 6dp 就认为在拖动 → 取消长按，窗口跟着手指走
 * UP    没拖动过 + 没触发过长按 → 单击（返回）
 * ```
 * 长按一旦触发就先回桌面，随后抬手的 UP 不再算单击（`fired` 位）。
 * 必须处理 `ACTION_CANCEL`：翻页 / 系统弹窗抢事件时会来这一条，不处理就会卡在
 * 「按着不放」的状态，下一次点击直接误触发长按。
 *
 * ## 位置
 * 按 **dp** 存 SharedPreferences(`h6_float_btn`)，换分辨率 / 重启后位置不变；
 * 拖动时按窗口边界钳位，拖不出屏幕。
 */
object FloatButton {

    private const val TAG = "H6FloatBtn"
    private const val SP = "h6_float_btn"
    private const val K_X = "x"
    private const val K_Y = "y"

    /** 长按判定：比系统默认 500ms 稍长一点，避免车机触摸抖动误判。 */
    private const val LONG_MS = 520L

    /**
     * 抬手后多久变回「半透明」。
     * 不立刻变淡的原因：刚点完的那一下如果马上下沉，视觉上像闪了一下；
     * 留 650ms 让人确认「操作已完成、球还在原位」。
     */
    private const val DIM_MS = 650L

    /** 平时的不透明度默认值（百分比）。太低会在浅色壁纸上彻底看不见。 */
    private const val ALPHA_DEF = 45

    /** 默认直径（dp）。6.0.105 起 56 → 112：用户反馈车上这个球太小、不好按。 */
    private const val SIZE_DEF = 112

    @Volatile
    var attached: Boolean = false
        private set

    /** 最近一次没挂上的原因（设置页诊断）。 */
    @Volatile
    var lastError: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var uiCtx: Context? = null
    private var ball: Ball? = null
    private var wm: WindowManager? = null
    private var usingType = 0

    private var posX = Int.MIN_VALUE
    private var posY = Int.MIN_VALUE
    private var sizeDp = 56
    private var alphaPct = ALPHA_DEF

    // 一次按压的状态机
    private var downRawX = 0f
    private var downRawY = 0f
    private var baseX = 0
    private var baseY = 0
    private var pressed = false
    private var moved = false
    private var fired = false
    private var downAt = 0L
    private var longTask: Runnable? = null
    private var dimTask: Runnable? = null

    // ---------------- 对外 ----------------

    /** 配置变了 / 无障碍宿主变了 / 想强制重挂，都可以无脑调这个。 */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        main.post { apply(app) }
    }

    /** 无障碍服务连上 / 断开：窗口挂在它的 WindowManager 上，必须重挂。 */
    fun onHostChanged(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        main.post {
            detach()
            apply(app)
        }
    }

    /** 设置页「复位位置」：回到右下角默认点。 */
    fun resetPos(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        runCatching {
            app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply()
        }
        posX = Int.MIN_VALUE
        posY = Int.MIN_VALUE
        main.post {
            detach()
            apply(app)
        }
    }

    /** 设置页状态行。 */
    fun describe(ctx: Context): String {
        val cfg = cfgOf(ctx)
        if (cfg?.bool("auto.floatOn", true) != true) return "已在设置里关闭"
        if (attached) {
            return if (usingType == TYPE_A11Y) "正在显示（无障碍窗口 2032）"
            else "正在显示（应用级窗口 $usingType）"
        }
        if (lastError.isNotEmpty()) return lastError
        return "未挂上"
    }

    // ---------------- 挂 / 摘 ----------------

    private fun apply(app: Context) {
        appCtx = app
        val cfg = cfgOf(app)
        if (cfg?.bool("auto.floatOn", true) != true) {
            detach()
            return
        }
        // ★ 6.0.105：默认 56 → 112（用户要求「加大 2 倍」，车机上 56dp 确实偏小、不好按）。
        //   上限放到 200，设置里可以再调大；下限 72，别小到按不准。
        sizeDp = cfg?.int("auto.floatSize", SIZE_DEF)?.coerceIn(72, 200) ?: SIZE_DEF
        alphaPct = cfg?.int("auto.floatAlpha", ALPHA_DEF)?.coerceIn(15, 100) ?: ALPHA_DEF
        val ui = scaledCtx(app) ?: return
        val v = ensureBall(ui)
        ensurePos(ui)

        if (attached) {
            val p = lp(ui, usingType)
            runCatching { wm?.updateViewLayout(v, p) }
                .onFailure {
                    lastError = "更新窗口失败：${it.javaClass.simpleName}"
                    detach()
                }
            return
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

        var last: Throwable? = null
        for ((manager, t) in tries) {
            val r = runCatching { manager.addView(v, lp(ui, t)) }
            if (r.isSuccess) {
                wm = manager
                usingType = t
                attached = true
                lastError = ""
                Log.i(TAG, "悬浮按钮已挂载 type=$t")
                return
            }
            last = r.exceptionOrNull()
        }
        lastError = "挂载失败：" + (last?.javaClass?.simpleName ?: "?") + " " + (last?.message ?: "")
        Log.w(TAG, lastError)
    }

    private fun ensureBall(ui: Context): Ball {
        ball?.let {
            if (it.sizeDp != sizeDp) {
                it.sizeDp = sizeDp
                it.requestLayout()
            }
            it.restAlpha = alphaPct / 100f
            it.invalidate()
            return it
        }
        val v = Ball(ui, sizeDp).apply {
            restAlpha = alphaPct / 100f
            setOnTouchListener { _, e -> touch(e, ui) }
        }
        ball = v
        return v
    }

    private fun lp(ui: Context, type: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            this.type = type
            format = PixelFormat.TRANSLUCENT
            /*
             * FLAG_NOT_TOUCH_MODAL 必须有：否则这块小球四周的整个窗口区域会把触摸
             * 全吃掉（车机上表现为"点不动底下的应用"）。
             * FLAG_NOT_FOCUSABLE：不抢焦点、不弹输入法。
             * 不能加 FLAG_NOT_TOUCHABLE —— 那样球自己也点不动。
             */
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            width = ui.dp(sizeDp)
            height = ui.dp(sizeDp)
            x = ui.dp(posX)
            y = ui.dp(posY)
        }

    private fun detach() {
        cancelDim()
        if (!attached) return
        val v = ball
        val ok = runCatching { wm?.removeView(v) }.isSuccess
        attached = false
        wm = null
        usingType = 0
        if (!ok) ball = null
    }

    // ---------------- 位置 ----------------

    private fun ensurePos(ctx: Context) {
        if (posX != Int.MIN_VALUE) return
        val sp = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val dm = ctx.resources.displayMetrics
        val defX = ((dm.widthPixels / dm.density).toInt() - sizeDp - 24).coerceAtLeast(0)
        val defY = ((dm.heightPixels / dm.density).toInt() * 0.42f).toInt()
        posX = sp.getInt(K_X, defX)
        posY = sp.getInt(K_Y, defY)
    }

    private fun savePos(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
                .edit().putInt(K_X, posX).putInt(K_Y, posY).apply()
        }
    }

    private fun moveTo(x: Int, y: Int, ui: Context) {
        val dm = ui.resources.displayMetrics
        val d = dm.density
        val w = ui.dp(sizeDp)
        val maxX = ((dm.widthPixels - w) / d).roundToInt().coerceAtLeast(0)
        val maxY = ((dm.heightPixels - w) / d).roundToInt().coerceAtLeast(0)
        val nx = x.coerceIn(0, maxX)
        val ny = y.coerceIn(0, maxY)
        if (nx == posX && ny == posY) return
        posX = nx
        posY = ny
        if (!attached) return
        runCatching { wm?.updateViewLayout(ball, lp(ui, usingType)) }
    }

    // ---------------- 触摸 ----------------

    private fun touch(e: MotionEvent, ui: Context): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                baseX = posX
                baseY = posY
                pressed = true
                moved = false
                fired = false
                downAt = SystemClock.uptimeMillis()
                // 手指一碰就浮起来：平时半透明，按下去实心，位置一眼能确认
                solidize()
                scheduleLong()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                val slop = ui.dp(6)
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    cancelLong()
                }
                if (moved) {
                    val d = ui.resources.displayMetrics.density
                    moveTo(baseX + (dx / d).roundToInt(), baseY + (dy / d).roundToInt(), ui)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelLong()
                val was = pressed
                val tap = was && !moved && !fired
                pressed = false
                if (moved) savePos(ui)
                if (tap) {
                    // 单击 = 返回键（用户明确要求）
                    val app = ui.applicationContext
                    AutoActions.performAndToast(app, AutoActions.BACK)
                }
                dimSoon()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLong()
                pressed = false
                moved = false
                // 翻页 / 弹窗抢事件时也要变回半透明，否则球会一直亮着
                dimSoon()
                return true
            }
        }
        return false
    }

    private fun scheduleLong() {
        cancelLong()
        val t = object : Runnable {
            override fun run() {
                if (!pressed || moved || fired) return
                fired = true
                // 长按 = 回桌面（用户明确要求）
                appCtx?.let { AutoActions.performAndToast(it, AutoActions.HOME) }
            }
        }
        longTask = t
        main.postDelayed(t, LONG_MS)
    }

    private fun cancelLong() {
        longTask?.let { main.removeCallbacks(it) }
        longTask = null
    }

    /** 按下 / 拖动中：实心，方便对准位置。 */
    private fun solidize() {
        cancelDim()
        ball?.setSolid(true)
    }

    /** 抬手后过 [DIM_MS] 再变回半透明，避免"点一下就消失"的错觉。 */
    private fun dimSoon() {
        cancelDim()
        val t = Runnable { ball?.setSolid(false) }
        dimTask = t
        main.postDelayed(t, DIM_MS)
    }

    private fun cancelDim() {
        dimTask?.let { main.removeCallbacks(it) }
        dimTask = null
    }

    // ---------------- 工具 ----------------

    private fun cfgOf(ctx: Context) =
        (ctx.applicationContext as? H6NApplication)?.config?.current()

    private fun canDraw(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true

    /** 与桌面同一套「720 设计稿」密度，球的大小才和其它卡片成比例。 */
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

    /** 自己画的球：深色玻璃底 + 白色返回箭头；平时半透明，按住时才实心。 */
    private class Ball(ctx: Context, var sizeDp: Int) : View(ctx) {

        /**
         * 平时的不透明度 0~1，由配置 `auto.floatAlpha` 注入（默认 45%）。
         * 悬浮球天天挂在屏幕边上，做成实心会一直抢注意力；半透明既看得见又不挡内容。
         */
        @Volatile
        var restAlpha = 0.45f

        /** true = 按下 / 拖动中，用满不透明度。 */
        private var solid = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF192431.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(2).toFloat()
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = ctx.dp(5).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        /** 按下 / 抬起切换。只在状态真的变了才重绘。 */
        fun setSolid(v: Boolean) {
            if (solid == v) return
            solid = v
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val px = context.dp(sizeDp)
            setMeasuredDimension(px, px)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            /*
             * 不透明度只改 Paint.alpha，不走 View.alpha ——
             * View.alpha 会让整块窗口过一次离屏合成，车机 GPU 本来就吃紧；
             * 这里只是一个 56dp 的圆，重绘一帧更便宜。
             * 底/描边/箭头衰减速度不同：底按比例降，描边和箭头降得慢一点，
             * 否则半透明时箭头糊成一团，看不出是返回键。
             */
            val a = if (solid) 1f else restAlpha.coerceIn(0.15f, 1f)
            fill.alpha = (0xE6 * a).roundToInt().coerceIn(0, 255)
            ring.alpha = (0x40 * (a * 1.7f).coerceAtMost(1f)).roundToInt().coerceIn(0, 255)
            glyph.alpha = (0xFF * (a * 1.55f).coerceAtMost(1f)).roundToInt().coerceIn(0, 255)

            // 线宽按直径等比：56dp 时是 2dp / 5dp，放大到 112dp 后跟着变粗，
            // 否则大球上的箭头顶不住，看着像缩水了。
            val k = minOf(w, h) / context.dp(56)
            ring.strokeWidth = context.dp(2) * k
            glyph.strokeWidth = context.dp(5) * k

            val r = (minOf(w, h) - glyph.strokeWidth) / 2f
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)

            // 返回箭头：横线 + 左侧箭头头部
            val sx = w * 0.34f
            val ex = w * 0.66f
            path.reset()
            path.moveTo(ex, cy)
            path.lineTo(sx, cy)
            path.moveTo(sx + w * 0.12f, cy - w * 0.12f)
            path.lineTo(sx, cy)
            path.lineTo(sx + w * 0.12f, cy + w * 0.12f)
            canvas.drawPath(path, glyph)
        }
    }

    /** 无障碍窗口类型，与 [com.h6n.launcher.ui.main.NavOverlay.TYPE_A11Y] 同值。 */
    private const val TYPE_A11Y = 2032
}
