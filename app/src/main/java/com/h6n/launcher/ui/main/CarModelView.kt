package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.h6n.launcher.R
import com.h6n.launcher.core.CarBinderSource
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.TireRules
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import java.util.Locale

/**
 * 胎压卡中间那辆车模。
 *
 * ## 来源
 * 原版不是一张会变的位图，而是：`car_top.png`（145×312 俯视图）打底，
 * `WindowStrip` 往上叠四块侧窗玻璃 + 天窗玻璃，`DoorMotion` 再叠四门与尾门。
 * 这里把三者的几何原样搬过来（都是位图像素坐标，按绘制矩形等比缩放），
 * 所以车窗/车门位置与原版完全一致，不会"差半个车门"。
 *
 * ## 状态取值
 * - 车窗 `windowPos[i]`：位置值 0~100，判定方式看 `window.mode`
 *   （auto：0/1/100 都算关；pos：>0 算开；inv：≥90 算关）
 * - 天窗 `sunroof`：位置值 0~100，判定方式看 `window.sunroofMode`
 *   （auto：≥ window.sunroofOpenMin 且 <95 算开；pos / inv / off 同四门）
 * - 车门 `doors[i]`：1 为开，0 为关
 *
 * ## 颜色约定
 * 车窗 / 天窗**未关 = 琥珀色玻璃 + 亮边 + 外发光**，一眼能看出哪扇没关；
 * 关着只画一层淡玻璃（车顶视图里玻璃本来就该在）。
 * 水温 / 变速箱油的机盖贴片已取消 —— 两个数改在状态栏显示。
 */
@SuppressLint("ViewConstructor")
class CarModelView(ctx: Context) : View(ctx) {

    /** 车模底图。detach 时**不**回收 —— 卡片可能被摘下再挂回；只在 [release] 里释放。 */
    private var car: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.car_top)
    } catch (t: Throwable) {
        null
    }

    /**
     * 是否挂在窗口上。[onDraw] 只有在 attached 时才允许申请下一帧，
     * 否则 View 被摘下后 `postInvalidateOnAnimation` 仍会往 Choreographer 里排回调，
     * 让一个已经离开窗口的 View（连同它的 Activity Context）多活一帧。
     */
    private var attached = false

    private var theme: Theme? = null
    private var signal = CarSignal.EMPTY

    /** page2.carScalePct / 100；100 = 刚好铺满卡片（含卡片内边距那点余量） */
    private var carScale = 1.0f
    /** page2.carAlphaPct / 100 */
    private var carAlpha = 0.9f
    /** page2.carDimNightPct / 100，夜间把车压暗 */
    private var carDimNight = 0.6f
    private var tireUnit = 0
    private var tirePosMap = intArrayOf(0, 1, 2, 3)
    private var sunroofOpenMin = 6

    /** 车窗 / 天窗判定方式：auto / pos / inv（天窗另有 off） */
    private var winMode = "auto"
    private var roofMode = "auto"

    /** 车模可以溢出自身高度范围的余量（dp）：卡片上下内边距减掉一点留白，这样车能顶到卡边 */
    private var slackDp = 0

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val carRect = RectF()

    // ---------------- 开合动画 ----------------
    /**
     * 四门 + 尾门 / 四窗 + 天窗的当前开度（0 关 1 全开）。
     *
     * 车身信号是"开/关"两态，直接照搬会硬切；原版 `DoorMotion` 是逐帧推进的，
     * 这里用同样的思路：目标值来自信号，绘制值每帧朝目标靠拢（约 0.28s 走完），
     * 停止变化时不再刷新（onDraw 里判断有没有在动）。
     */
    private val doorProg = FloatArray(5)
    private val winProg = FloatArray(4)
    private var roofProg = 0f
    private var lastFrameNs = 0L

    /**
     * 上一次触发重绘的「车模相关」信号（v6.0.43）。
     *
     * 车模只吃胎压 / 胎温 / 四门 / 四窗 / 天窗这五组值；车速、油量、挡位、里程
     * 每秒都在变却和车模毫无关系。原先每次 bind 都无条件 invalidate()，等于被一个
     * 无关字段的变化拖着把整张车模（位图 + 四门四窗 + 四枚贴片）重画一遍。
     * 用 null 作初值：第一次 bind 必定画一次。
     */
    private var lastTireKpa: List<Int>? = null
    private var lastTireTemp: List<Int>? = null
    private var lastDoors: List<Int>? = null
    private var lastWindows: List<Int>? = null
    private var lastSunroof = Int.MIN_VALUE

    /** 四枚贴片的文本缓存：数值没变就不重新拼字符串（每秒重绘也不产生垃圾）。 */
    private val kPaText = arrayOfNulls<String>(4)
    private val kPaCache = IntArray(4) { Int.MIN_VALUE }
    private val tempText = arrayOfNulls<String>(4)
    private val tempCache = IntArray(4) { Int.MIN_VALUE }

    // ---------------- 窗口生命周期 ----------------
    /**
     * 本 View 没有 `ValueAnimator`：开合动画是 [onDraw] → [stepAnim] →
     * `postInvalidateOnAnimation()` 的自驱动逐帧逼近，到位后自动停止。
     * 所以「停止动画」要做的是：**断掉下一帧的申请**，而不是 cancel 某个 Animator。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        // 摘下期间可能过了很久，时间戳必须清零，否则重新挂回第一帧 dt 会被算成一大截
        // （虽然 stepAnim 里 coerceIn 到 0.12s，还是会看到门「瞬移」一下）。
        lastFrameNs = 0L
        // 开度进度不清零：车门/车窗的物理状态应当延续，重新挂回接着走完剩下的过渡。
        invalidate()
    }

    override fun onDetachedFromWindow() {
        attached = false
        lastFrameNs = 0L
        super.onDetachedFromWindow()
    }

    /**
     * Activity 真正销毁时调用，释放车模位图与主题引用。
     * 不要在 [onDetachedFromWindow] 里调：翻页/重建卡片只是把 View 摘下来，
     * 位图还在用，recycle 之后重新挂回就是一张空白车。
     */
    fun release() {
        attached = false
        lastFrameNs = 0L
        car?.let { if (!it.isRecycled) it.recycle() }
        car = null
        theme = null
    }

    fun applyTheme(t: Theme) {
        theme = t
        carScale = t.snap.int("page2.carScalePct", 100).coerceIn(60, 130) / 100f
        carAlpha = t.snap.int("page2.carAlphaPct", 90).coerceIn(10, 100) / 100f
        carDimNight = t.snap.int("page2.carDimNightPct", 60).coerceIn(0, 100) / 100f
        tireUnit = t.snap.int("page2.tireUnit", 0)
        sunroofOpenMin = t.snap.int("window.sunroofOpenMin", 6).coerceIn(0, 95)
        tirePosMap = parseMap(t.snap.str("page2.tirePosMap", "0,1,2,3"))
        winMode = normMode(t.snap.str("window.mode", "auto"), false)
        roofMode = normMode(t.snap.str("window.sunroofMode", "auto"), true)
        // 天窗键允许在配置里强制指定，剩下的交给平台属性候选表探测
        CarBinderSource.sunroofKeyOverride = t.snap.str("window.sunroofKey", "")
        invalidate()
    }

    /** auto / pos / inv（天窗多一个 off）；其它值一律回 auto。 */
    private fun normMode(raw: String?, roof: Boolean): String {
        val m = raw?.trim()?.lowercase().orEmpty()
        if (m == "pos" || m == "inv") return m
        if (roof && m == "off") return m
        return "auto"
    }

    /** 卡片上下内边距减去留白（dp）：车模因此可以画到卡边，比"只在 body 里居中"大一截。 */
    fun setSlackDp(slack: Int) {
        val v = slack.coerceIn(0, 60)
        if (v == slackDp) return
        slackDp = v
        requestLayout()
        invalidate()
    }

    fun bind(s: CarSignal) {
        signal = s
        // ★ v6.0.43：只在这五组值真的变了才重绘。
        //   车速从 0 到 120 的过程中车模一动不动，没必要跟着重画。
        if (s.tireKpa == lastTireKpa && s.tireTempC == lastTireTemp &&
            s.doors == lastDoors && s.windowPos == lastWindows && s.sunroof == lastSunroof
        ) {
            return
        }
        lastTireKpa = s.tireKpa
        lastTireTemp = s.tireTempC
        lastDoors = s.doors
        lastWindows = s.windowPos
        lastSunroof = s.sunroof
        invalidate()
    }

    private fun parseMap(raw: String): IntArray {
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size >= 4) intArrayOf(parts[0], parts[1], parts[2], parts[3])
        else intArrayOf(0, 1, 2, 3)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = car ?: return
        val t = theme ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 车模高度 =（自身高度 + 上下余量 slackDp）× carScalePct，水平居中。
        // 温度卡删掉以后胎压卡占了整列高度，车模比原来两卡对半分时大一倍多；
        // 宽度再收一道（≤ 卡片宽 78%），给左右各留一条写胎压数字的通道。
        val baseH = h + 2f * context.dp(slackDp)
        val byHeight = baseH * carScale
        val byWidth = w * 0.78f * bmp.height / bmp.width
        val targetH = Math.min(byHeight, byWidth)
        val targetW = targetH * bmp.width / bmp.height
        val left = (w - targetW) / 2f
        val top = (h - targetH) / 2f
        carRect.set(left, top, left + targetW, top + targetH)

        // 先推进动画：还有没走完的位移就再要一帧。
        // attached 闸门保证 View 脱离窗口后不再往 Choreographer 排回调。
        if (attached && stepAnim()) postInvalidateOnAnimation()

        bmpPaint.alpha = (255 * carAlpha * (if (t.night) carDimNight else 1f)).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, carRect, bmpPaint)
        bmpPaint.alpha = 255

        drawGlass(canvas, t)
        drawDoors(canvas, t)
        drawTires(canvas, t)
    }

    /**
     * 把绘制用的开度往信号目标值靠拢。
     *
     * 用指数逼近而不是线性步进：开门/开窗"先快后慢"，末端自然收住，
     * 不会走到最后一帧还在匀速滑动。τ≈0.13s，整段约 0.45 秒到位。
     * @return 是否还在动（true 表示需要下一帧）
     */
    private fun stepAnim(): Boolean {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f
        else ((now - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.12f)
        lastFrameNs = now
        val k = (dt * 7.5f).coerceIn(0f, 1f)

        var moving = false
        fun approach(cur: Float, target: Float): Float {
            val d = target - cur
            if (Math.abs(d) <= 0.004f) return target
            moving = true
            val step = d * k
            val next = cur + if (Math.abs(step) < 0.012f) Math.signum(d) * 0.012f else step
            return if (Math.abs(target - next) < 0.004f) target else next
        }

        val win = signal.windowPos
        for (i in 0 until 4) {
            val raw = win.getOrNull(i) ?: -1
            winProg[i] = approach(winProg[i], if (windowOpen(raw)) 1f else 0f)
        }
        roofProg = approach(roofProg, if (roofOpen(signal.sunroof)) 1f else 0f)

        val doors = signal.doors
        for (i in 0 until 5) {
            val open = (doors.getOrNull(i) ?: 0) == 1
            doorProg[i] = approach(doorProg[i], if (open) 1f else 0f)
        }
        return moving
    }

    // ---------------- 车窗 / 天窗 ----------------

    private fun drawGlass(canvas: Canvas, t: Theme) {
        val sx = carRect.width() / SRC_W
        val sy = carRect.height() / SRC_H
        for (i in 0 until 4) {
            patch(canvas, WIN[i], winProg[i], sx, sy, t, 0.10f)
        }
        // 天窗恒定绘制：即使读不到位置，车顶视图里那块玻璃也该在
        patch(canvas, ROOF, roofProg, sx, sy, t, 0.16f)
    }

    /**
     * 四门车窗"是否未关"。判定方式照旧版 `WindowStrip`：
     * auto —— 0 / 1 / 100 都算关（本车"开"报的是小枚举值，用阈值反而会把开吃掉）；
     * pos —— 位置百分比，>0 即开；inv —— 100 为关，越小越开。
     */
    private fun windowOpen(raw: Int): Boolean {
        if (raw < 0) return false
        return when (winMode) {
            "pos" -> raw > 0
            "inv" -> raw < 90
            else -> raw > 1 && raw < 99
        }
    }

    /**
     * 天窗"是否未关"。auto 用阈值（≥ sunroofOpenMin 且 <95 才算开，挡住第 5 位恒为 1 的情况），
     * pos / inv 同四门，off 表示数据不可靠、不提示。
     */
    private fun roofOpen(raw: Int): Boolean {
        if (raw < 0) return false
        return when (roofMode) {
            "off" -> false
            "pos" -> raw > 0
            "inv" -> raw < 90
            else -> raw >= sunroofOpenMin && raw < 95
        }
    }

    /**
     * 一块玻璃。
     *
     * 关闭（[prog]=0）也要画：车顶视图里车窗/天窗是**深色玻璃**，不是"没有"，
     * 早期只在开启时绘制，看起来就像这台车没有车窗。
     * 开启时在同一块区域叠橙色，透明度随 [prog] 渐变，形成开合过渡。
     */
    private fun patch(canvas: Canvas, box: FloatArray, prog: Float, sx: Float, sy: Float, t: Theme, radiusRatio: Float) {
        val l = carRect.left + box[0] * sx
        val top = carRect.top + box[1] * sy
        val r = carRect.left + box[2] * sx
        val b = carRect.top + box[3] * sy
        val rad = Math.max(1.1f, (b - top) * radiusRatio)
        rect.set(l, top, r, b)

        // 1) 玻璃本体（关着也是玻璃）
        fill.style = Paint.Style.FILL
        fill.color = if (t.night) 0x3D9FB6D6 else 0x337E95B5
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.color = if (t.night) 0x1AFFFFFF else 0x1FFFFFFF
        canvas.drawRoundRect(rect, rad, rad, fill)

        // 2) 玻璃边框：白天浅灰蓝，夜间淡白，保证在车模深色车顶上能分辨
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = Math.max(0.6f, sx * 1.5f)
        stroke.pathEffect = null
        stroke.color = if (t.night) 0x66C9D8EE else 0x5A6E7F96
        canvas.drawRoundRect(rect, rad, rad, stroke)

        // 3) 开启部分：琥珀玻璃 + 外发光 + 上沿高光（未关一眼就能看出来）
        if (prog > 0.01f) {
            val a = prog.coerceIn(0f, 1f)
            val amber = if (t.night) 0xFFFFB01E.toInt() else 0xFFE08A00.toInt()
            val amberLine = if (t.night) 0xFFFFC24D.toInt() else 0xFFB25E00.toInt()

            // 外发光：硬件加速下 setShadowLayer 对圆角矩形不生效，用一圈放大的半透明块模拟
            val pad = Math.max(1.5f, sx * 4.5f)
            rect.set(l - pad, top - pad, r + pad, b + pad)
            fill.color = amber
            fill.alpha = (0x40 * a).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, rad + pad, rad + pad, fill)
            rect.set(l, top, r, b)

            // 琥珀玻璃本体
            fill.color = amber
            fill.alpha = (0x8A * a).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, rad, rad, fill)

            // 亮边
            stroke.pathEffect = null
            stroke.strokeWidth = Math.max(0.9f, sx * 2.2f)
            stroke.color = amberLine
            stroke.alpha = (255 * a).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, rad, rad, stroke)

            // 上沿一道高光，像玻璃降下去后露出来的窗框
            val hi = Math.max(0.8f, (b - top) * 0.055f)
            rect.set(l + rad * 0.5f, top + hi, r - rad * 0.5f, top + hi * 2.1f)
            fill.color = amberLine
            fill.alpha = (220 * a).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, hi * 0.5f, hi * 0.5f, fill)
            rect.set(l, top, r, b)
            stroke.alpha = 255
        }
        fill.alpha = 255
    }

    // ---------------- 车门 / 尾门 ----------------

    private fun drawDoors(canvas: Canvas, t: Theme) {
        val sx = carRect.width() / SRC_W
        val sy = carRect.height() / SRC_H
        for (i in 0 until 4) {
            val p = doorProg[i]
            if (p <= 0.01f) continue
            drawDoor(canvas, i, p, sx, sy, t)
        }
        val tp = doorProg[4]
        if (tp > 0.01f) drawTrunk(canvas, tp, sx, sy, t)
    }

    private fun drawDoor(canvas: Canvas, idx: Int, prog: Float, sx: Float, sy: Float, t: Theme) {
        val b = DOOR[idx]
        val l = carRect.left + b[0] * sx
        val top = carRect.top + b[1] * sy
        val r = carRect.left + b[2] * sx
        val bt = carRect.top + b[3] * sy
        val left = idx == 0 || idx == 2
        val hingeX = if (left) r else l
        val w = r - l
        val len = bt - top
        val rad = Math.max(1f, Math.min(w, len) * 0.22f)

        val amber = if (t.night) 0xFFFFB74D.toInt() else 0xFFFF9800.toInt()
        val amberLine = if (t.night) 0xFFFFD98A.toInt() else 0xFFFFC46A.toInt()

        // 门洞：开门处挖空，并随开度往车外延伸 —— 光是门框变色看不出"门开了"
        val out = w * 1.7f * prog
        fill.color = if (t.night) 0xFF0A1220.toInt() else 0xFF1B2836.toInt()
        fill.alpha = (225 * prog).toInt().coerceIn(0, 255)
        rect.set(if (left) l - out else l, top, if (left) r else r + out, bt)
        canvas.drawRoundRect(rect, rad, rad, fill)

        // 铰链处门缝亮线
        stroke.pathEffect = null
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = Math.max(0.7f, sx * 1.6f)
        stroke.color = if (t.night) 0xFFFFC24D.toInt() else 0xFFFF8F00.toInt()
        stroke.alpha = (230 * prog).toInt().coerceIn(0, 255)
        canvas.drawLine(hingeX, top, hingeX, top + len, stroke)

        // 门板绕前铰链外旋：角度 = 全开角 × 进度。
        // 门板长度取门框的 2.6 倍，转开后整扇门明显伸到车身外面，不再是一小块橙色贴片。
        val save = canvas.save()
        canvas.translate(hingeX, top)
        canvas.rotate((if (left) DOOR_ANGLE else -DOOR_ANGLE) * prog)
        val pw = w * 2.6f
        val x0 = if (left) -pw else 0f
        rect.set(x0, 0f, x0 + pw, len)
        fill.color = amber
        fill.alpha = (235 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad, rad, fill)

        // 门板内衬：深色车身面板，留出琥珀色的门沿与外框
        rect.set(x0 + pw * 0.10f, len * 0.10f, x0 + pw * 0.90f, len * 0.90f)
        fill.color = if (t.night) 0xFF3C4E63.toInt() else 0xFF2F4257.toInt()
        fill.alpha = (225 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad * 0.6f, rad * 0.6f, fill)

        rect.set(x0, 0f, x0 + pw, len)
        stroke.strokeWidth = Math.max(0.9f, sx * 2.2f)
        stroke.color = amberLine
        stroke.alpha = (245 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad, rad, stroke)

        // 门把手：靠近自由端，转开后跟着门一起走
        val hx = if (left) x0 + pw * 0.28f else x0 + pw * 0.60f
        rect.set(hx, len * 0.44f, hx + pw * 0.15f, len * 0.49f)
        fill.color = amberLine
        fill.alpha = (240 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad * 0.2f, rad * 0.2f, fill)
        canvas.restoreToCount(save)

        fill.alpha = 255
        stroke.alpha = 255
    }

    private fun drawTrunk(canvas: Canvas, prog: Float, sx: Float, sy: Float, t: Theme) {
        val l = carRect.left + TAIL[0] * sx
        val top = carRect.top + TAIL[1] * sy
        val r = carRect.left + TAIL[2] * sx
        val bt = carRect.top + TAIL[3] * sy
        val h = bt - top
        val lidH = h * 0.26f
        val rad = Math.max(1f, h * 0.20f)

        fill.color = if (t.night) 0xFF0A1220.toInt() else 0xFF1B2836.toInt()
        fill.alpha = (238 * prog).toInt().coerceIn(0, 255)
        rect.set(l + sx * 1.5f, top + lidH, r - sx * 1.5f, bt - sy * 0.5f)
        canvas.drawRoundRect(rect, rad * 0.5f, rad * 0.5f, fill)

        stroke.pathEffect = null
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = Math.max(0.9f, sy * 2.2f)
        stroke.color = if (t.night) 0xFFFFC24D.toInt() else 0xFFFF8F00.toInt()
        stroke.alpha = (245 * prog).toInt().coerceIn(0, 255)
        canvas.drawLine(l + sx * 2f, top + lidH, r - sx * 2f, top + lidH, stroke)

        rect.set(l, top, r, bt)
        stroke.strokeWidth = Math.max(0.7f, sx * 1.4f)
        stroke.alpha = (175 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad, rad, stroke)

        // 尾门盖板上翻：绕后沿抬起，整块门板跟着开度伸出来
        val lift = h * 0.85f * prog
        rect.set(l + sx * 1.2f, top - lift, r - sx * 1.2f, top + lidH - lift * 0.18f)
        fill.color = if (t.night) 0xFFFFB74D.toInt() else 0xFFFF9800.toInt()
        fill.alpha = (235 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad * 0.5f, rad * 0.5f, fill)
        stroke.strokeWidth = Math.max(0.8f, sy * 1.6f)
        stroke.color = if (t.night) 0xFFFFD98A.toInt() else 0xFFFFC46A.toInt()
        stroke.alpha = (245 * prog).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, rad * 0.5f, rad * 0.5f, stroke)

        fill.alpha = 255
        stroke.alpha = 255
    }

    // ---------------- 四个胎压 ----------------

    private fun drawTires(canvas: Canvas, t: Theme) {
        val vals = signal.tireKpa
        if (vals.isEmpty()) return
        val main = t.color("p2TextMain", Theme.INK)

        // 两侧通道宽度：胎压数字就摆在通道正中间，各画一枚圆角贴片。
        // 贴片和机盖上的水温贴片同一套逻辑：白天浅底深字、夜间深底浅字，都保反差。
        val wing = (width - carRect.width()) / 2f
        if (wing < 34f) return
        var size = t.size("slotValueText", 26).toFloat()
        if (wing > size * 5f) size *= 1.15f

        val lightChip = !t.night
        val pillBg = if (lightChip) 0xE6FFFFFF.toInt() else 0xCC1B2635.toInt()
        val pillStroke = if (lightChip) 0x14000000 else 0x33FFFFFF
        // 胎温：比旧版的 0.62 淡化实一档，但仍比胎压数字轻，不至于两个数打架
        val tempColor = if (lightChip) 0xB3566070.toInt() else 0xBFC2CCDA.toInt()

        // 6.0.67：异常从「只换字色」升级为「整枚贴片填红」。
        // 旧版是 26sp 的 #C0392B 压在 0xE6 白底上，实车上和普通数字几乎分不出来
        // （用户 2026-11-15 反馈「胎压异常红色不够明显」）。填红后数字一律纯白，
        // 外沿再叠一圈 0x33 的淡红描边当光晕 —— 纯描边实现，不碰 shadowLayer / 软件层。
        val lowBg = if (lightChip) 0xFFD91E10.toInt() else 0xFFF0483A.toInt()
        val lowHalo = if (lightChip) 0x2ED91E10 else 0x33F0483A
        val lowEdge = if (lightChip) 0xFF96120A.toInt() else 0x4DFFFFFF
        val lowTemp = if (lightChip) 0xFFFFD9D3.toInt() else 0xFFFFE4DF.toInt()

        // 阈值每帧读一次，四个轮子共用；设置页改完下一帧就按新值上色
        val lowKpa = TireRules.lowKpa()

        val cxL = carRect.left - wing / 2f
        val cxR = carRect.right + wing / 2f
        // 上下两排的中心线：0/1 是前轮（上排），2/3 是后轮（下排）。
        // v6.0.43 起直接算，不再每帧 new 一个 FloatArray。
        val rowTop = carRect.top + carRect.height() * 0.26f
        val rowBottom = carRect.top + carRect.height() * 0.74f

        for (i in 0 until 4) {
            val box = tirePosMap[i]
            val v = vals.getOrNull(box) ?: continue
            // 6.0.65：判据从旧的 180..320 收紧到「低于阈值就红」（默认 240，6.0.68 起可调）——
            // 阈值和 TireAlert 的告警判据共用 TireRules 一份，不能各自写死。
            // 旧判据下限 180 太低：胎压掉到 200 时数字还是白的，等变红早就该补气了。
            val normal = v >= lowKpa && v <= TireRules.HIGH_KPA
            val low = !normal
            val x = if (i == 0 || i == 2) cxL else cxR

            // 胎温：贴片里不再带单位 —— 卡片标题已经是「胎压 · kPa」，贴片只放 `${n}℃`。
            // 字号 0.64×、与胎压数字同样用 bold：旧版是 0.6×base 的常规体再淡化到 0.62，
            // 放在这么小的贴片里几乎看不见。判据沿用旧版：> -40 才算有温度。
            val tempC = signal.tireTempC.getOrNull(box) ?: -1
            val tTxt = if (tempC > -40) cachedTempText(i, tempC) else null

            val vtxt = cachedKpaText(i, v)
            // 异常时数字再放大 9%：填红之后字号大一档，等红灯余光就能扫到
            val vs = if (low) size * 1.09f else size
            text.typeface = t.bold
            text.textSize = vs
            text.textAlign = Paint.Align.CENTER
            val vw = text.measureText(vtxt)
            var tw = 0f
            if (tTxt != null) {
                text.textSize = vs * TEMP_SCALE
                tw = text.measureText(tTxt)
            }

            val contentW = Math.max(vw, tw)
            // 异常贴片左右多留一点红：填色的面积比字本身更能说明问题
            val boxW = Math.min(contentW + vs * (if (low) 1.34f else 1.05f), wing * 0.94f)
            val boxH = vs * 2.12f
            val cy = if (i < 2) rowTop else rowBottom

            rect.set(x - boxW / 2f, cy - boxH / 2f, x + boxW / 2f, cy + boxH / 2f)
            val rad = boxH / 2f
            fill.style = Paint.Style.FILL
            fill.color = if (low) lowBg else pillBg
            canvas.drawRoundRect(rect, rad, rad, fill)
            stroke.style = Paint.Style.STROKE
            stroke.pathEffect = null
            if (low) {
                // 先画加宽的淡红外沿，再画本体描边 —— 等效于光晕，但只是一次描边
                stroke.strokeWidth = Math.max(1.4f, vs * 0.24f)
                stroke.color = lowHalo
                canvas.drawRoundRect(rect, rad, rad, stroke)
            }
            stroke.strokeWidth = Math.max(0.7f, vs * 0.05f)
            stroke.color = if (low) lowEdge else pillStroke
            canvas.drawRoundRect(rect, rad, rad, stroke)

            // 两行（胎压 + 温度）整体垂直居中；温度读不到就变成单行，数字自己居中
            val baseY = cy + vs * (if (tTxt != null) 0.06f else 0.36f)
            text.typeface = t.bold
            text.textSize = vs
            // 填红底上必须是纯白，不再走主题的 p2TextWarn（那是 #C0392B 的暗砖红）
            text.color = if (low || !lightChip) 0xFFFFFFFF.toInt() else main
            canvas.drawText(vtxt, x, baseY, text)

            if (tTxt != null) {
                // 温度比旧版实一些（0xB3 不透明），但仍然比胎压数字轻一档
                text.typeface = t.bold
                var ts = vs * TEMP_SCALE
                val avail = boxW - vs * 0.28f
                if (avail > 0f && tw > avail) {
                    ts = (ts * avail / tw).coerceAtLeast(vs * 0.46f)
                }
                text.textSize = ts
                text.color = if (low) lowTemp else tempColor
                canvas.drawText(tTxt, x, cy + vs * 0.64f, text)
            }
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** 胎压文本缓存：同一个数值不重复 `toString()`（每秒重绘也不产生垃圾）。 */
    private fun cachedKpaText(i: Int, v: Int): String {
        kPaText[i]?.let { if (kPaCache[i] == v) return it }
        val s = v.toString()
        kPaCache[i] = v
        kPaText[i] = s
        return s
    }

    /** 胎温文本缓存，同上。 */
    private fun cachedTempText(i: Int, c: Int): String {
        tempText[i]?.let { if (tempCache[i] == c) return it }
        val s = "$c℃"
        tempCache[i] = c
        tempText[i] = s
        return s
    }

    /** 供胎压卡标题使用：`胎压 · kPa`。 */
    fun unitLabel(): String = when (tireUnit) {
        1 -> "bar"
        2 -> "psi"
        else -> "kPa"
    }

    fun tireSummary(): String {
        val vals = signal.tireKpa
        if (vals.size < 4) return "--"
        return vals.joinToString("/") { it.toString() }
    }

    /**
     * 有任意一轮越界（低于 [TireRules.lowKpa] 或高于 [TireRules.HIGH_KPA]）。
     * 卡片标题跟着变红用；没有传感器的轮位（< [TireRules.VALID_MIN]）不算。
     */
    fun tireWarn(): Boolean {
        val vals = signal.tireKpa
        if (vals.size < 4) return false
        val lowKpa = TireRules.lowKpa()
        return vals.any { it >= TireRules.VALID_MIN && (it < lowKpa || it > TireRules.HIGH_KPA) }
    }

    fun tempSummary(): String {
        val vals = signal.tireTempC
        if (vals.size < 4) return ""
        return vals.joinToString("/") { String.format(Locale.CHINA, "%d", it) }
    }

    companion object {
        private const val SRC_W = 145f
        private const val SRC_H = 312f
        private const val DOOR_ANGLE = 58f

        /** 贴片里胎温相对胎压数字的字号比例（旧版 0.60 且是常规体，看不清）。 */
        private const val TEMP_SCALE = 0.68f

        /** 侧窗玻璃框（位图像素坐标），顺序：左前/右前/左后/右后 */
        private val WIN = arrayOf(
            floatArrayOf(19.2f, 134.5f, 28.8f, 180f),
            floatArrayOf(116.2f, 134.5f, 124.8f, 180f),
            floatArrayOf(19.2f, 182f, 28.8f, 227.5f),
            floatArrayOf(116.2f, 182f, 124.8f, 227.5f)
        )

        /** 全景天窗玻璃框 */
        private val ROOF = floatArrayOf(52f, 155f, 93f, 220f)

        /** 四门框，顺序同上 */
        private val DOOR = arrayOf(
            floatArrayOf(9.5f, 126f, 20.5f, 180f),
            floatArrayOf(124.5f, 126f, 135.5f, 180f),
            floatArrayOf(9.5f, 182f, 20.5f, 236f),
            floatArrayOf(124.5f, 182f, 135.5f, 236f)
        )

        /** 尾门 */
        private val TAIL = floatArrayOf(26f, 285f, 119f, 308f)
    }
}
