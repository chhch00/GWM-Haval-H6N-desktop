package com.h6n.launcher.ui.main

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.h6n.launcher.core.AmapNavi
import com.h6n.launcher.ui.common.Theme

/**
 * 高德导航信息面板：**巡航一套排版，导航一套排版**，两块都从 x=0 往右排。
 *
 * ```
 * 巡航（桌面卡片里 showSpeed=false，车速在左边大数字上）
 *   ● 32 ↰   XX大道
 *   ↖ ↑ ↑                 ← 车道（有才画）
 *
 * 导航（常规风格）
 *   ┌──┐
 *   │↰ │ 300 米                 进入 XX路
 *   └──┘
 *   ↖ ↑ ↑                     12.4公里 · 18分
 *   ● 32 ↰                          预计 08:42
 *   ━━━━━━━━━━━━░░░░░░░░░░
 * ```
 *
 * ## 为什么整块自绘
 * 1. 车道一次最多 6 个图标，高德在路口前能一秒推好几次；每次 `removeAllViews + addView`
 *    都是一轮 measure/layout，车机上直接掉帧。
 * 2. 车道图是浅灰 PNG，必须按主题染色（浅色卡片上用深色、悬浮窗上用白色），
 *    自绘只要给 `Paint` 挂一个 `PorterDuffColorFilter`。
 * 3. 只有一块画布，闪烁动画一个 `ValueAnimator` 就够。
 *
 * ## 左对齐 + 缩放
 * v6.0.35 起内容**一律从左边开始排**（上一版贴右边缘，用户反馈「红绿灯太靠右」）。
 * 桌面上它单独占一张卡（车速卡下、机油卡上），[setScale] 给 1.5 放大；
 * 悬浮窗里 scale=1，窗口宽度就是内容宽度。
 *
 * ## 6.0.53：胶囊右边紧挨着画「距灯 130米」
 * [AmapNavi.Light.distanceText] 来自 10001 的 `SEG_REMAIN_DIS_AUTO`（或 60073 自带的
 * 距离键）。距离文字用灯色画、和胶囊同一行。高德没给距离（巡航态常见）就不画这一块，
 * 胶囊保持原来的宽度，整套排版仍是纯自绘、零 XML。
 *
 * ## 6.0.54：「距灯 14…」不再被折叠
 * 6.0.53 把距离文字硬塞进一个固定 96dp 的框里、超了就 ellipsize；而导航态 row3 的
 * 可用宽度又是先扣掉 ETA 实测宽度算出来的。实车上 `ETA_TEXT` 本身就是「预计18:14到达」
 * 整句，旧代码又拼了一层「预计 … 到达」，宽度凭空翻倍 → 距离文字只剩几十 dp，
 * 于是「距灯 146米」被截成「距灯 14…」。两个真因叠加，缺一都不会露出来。
 *
 * 现在这一行按「必须完整显示的项先拿宽度」来排：胶囊 → 灯距（Paint 实测宽度，
 * 放不下就按实测比例缩字号，绝不省略）→ ETA（拿剩余宽度，放不下退成时刻）。
 * 固定预留宽度只留给悬浮窗 WRAP 测量用（文本每秒变，不能让它每秒重排），
 * **不再拿来裁字**。详见 [drawNavi] / [drawLightGroup]。
 *
 * 线程：所有方法都在主线程调用（数据源 [AmapNavi] 的回调本身就在主线程）。
 */
@SuppressLint("ViewConstructor")
class NavInfoView(ctx: Context) : View(ctx) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val dst = Rect()
    private val path = Path()
    private val head = Path()

    /** 主色（车道 / 箭头 / 倒计时都用它）。深色主题下由 [applyTheme] 换成浅色。 */
    private var ink = 0xFF1B1F24.toInt()
    private var typeface: Typeface? = null

    private var navi = false
    private var light: AmapNavi.Light? = null
    private var lanes: List<AmapNavi.Lane> = emptyList()
    private var info: AmapNavi.Info = AmapNavi.Info()

    /** 悬浮窗用：巡航时在最左边补一个车速（桌面卡片里车速是大数字，不重复）。 */
    private var showSpeed = false

    /** 整体缩放。桌面导航卡 1.5（用户要求加大 1.5 倍），悬浮窗 1.0。 */
    private var scale = 1f

    /** 完全没有数据时的占位文字（桌面卡片里显示，悬浮窗不会显示）。 */
    var placeholder: String = ""

    private var phase = 0f
    private var anim: ValueAnimator? = null

    private val d: Float get() = resources.displayMetrics.density
    private fun px(v: Float): Float = v * d * scale

    // ---- 尺寸（720 设计稿的 dp，全部过 px() 所以跟着 scale 走） ----

    private val row1H: Float get() = px(if (!navi && showSpeed) 56f else 52f)
    private val row2H: Float get() = px(40f)
    private val row3H: Float get() = px(44f)
    private val barH: Float get() = px(6f)
    private val gap: Float get() = px(6f)
    private val iconBox: Float get() = px(52f)
    private val laneH: Float get() = px(36f)
    private val laneGap: Float get() = px(4f)
    /** 红绿灯胶囊与「距灯 XXX米」之间的间距。 */
    private val lightDisGap: Float get() = px(10f)
    /**
     * 「距灯 XXX米」在悬浮窗 WRAP 场景下的**测量预留**宽度。
     * 6.0.54：96dp 连「距灯 1.2公里」都装不下，提到 132dp。这个常量只用来避免
     * 悬浮窗每秒因文本变化而重排，绘制时按实测文本宽度走，不再拿它裁字。
     */
    private val lightDisW: Float get() = px(132f)

    // ---------------- 对外 ----------------

    fun applyTheme(t: Theme) {
        ink = t.color("p2TextMain", Theme.INK)
        typeface = t.bold
        text.typeface = t.bold
        invalidate()
    }

    /** 悬浮窗用：固定成白色（面板底是深色玻璃，不跟桌面主题走）。 */
    fun setInk(color: Int) {
        ink = color
        invalidate()
    }

    /** 悬浮窗用：巡航态左侧显示高德报的车速。 */
    fun setShowSpeed(on: Boolean) {
        if (showSpeed == on) return
        showSpeed = on
        requestLayout()
        invalidate()
    }

    /** 桌面导航卡用 1.5（加大 1.5 倍），悬浮窗用 1。 */
    fun setScale(s: Float) {
        if (scale == s) return
        scale = s
        requestLayout()
        invalidate()
    }

    /**
     * 换一屏数据。内容和上次一样就直接返回 —— 高德导航时 10001 一秒好几条，
     * 不去重的话每次都要重排重绘。
     */
    fun setData(
        navi: Boolean,
        l: AmapNavi.Light?,
        list: List<AmapNavi.Lane>,
        i: AmapNavi.Info
    ) {
        // 先把闪烁状态对齐：卡片被摘下来再挂上去时数据没变，但动画被 release() 停了
        ensureBlink((l != null && l.countdown in 1..5) || list.any { it.advised })
        if (navi == this.navi && l == light && list == lanes && i == info) return
        val oldH = desiredHeight()
        val oldW = desiredWidth()
        this.navi = navi
        light = l
        lanes = list
        info = i
        // 巡航 ↔ 导航、有车道 ↔ 没车道，自身尺寸都会变，得让父布局重量一次
        if (oldH != desiredHeight() || oldW != desiredWidth()) requestLayout()
        invalidate()
    }

    fun release() {
        anim?.cancel()
        anim = null
    }

    // ---------------- 动画 ----------------

    private fun ensureBlink(need: Boolean) {
        if (!need) {
            anim?.cancel()
            anim = null
            if (phase != 0f) {
                phase = 0f
                invalidate()
            }
            return
        }
        if (anim != null) return
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    // ---------------- 测量 ----------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = when (wMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            MeasureSpec.AT_MOST ->
                Math.min(desiredWidth().toInt(), MeasureSpec.getSize(widthMeasureSpec))
            else -> desiredWidth().toInt()
        }
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST ->
                Math.min(desiredHeight().toInt(), MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight().toInt()
        }
        setMeasuredDimension(w, h)
    }

    private fun desiredHeight(): Float = if (navi) {
        row1H + gap + row2H + gap + row3H + gap + barH
    } else {
        row1H + if (lanes.isNotEmpty()) gap + row2H else 0f
    }

    /**
     * 导航态给一个下限（进度条、三列文字要排得开），巡航态按内容算。
     * 卡片里宽度是 EXACTLY，这个值只在悬浮窗（WRAP）里起作用。
     */
    private fun desiredWidth(): Float {
        if (navi) return px(300f)
        var w = 0f
        if (showSpeed && info.speed >= 0) w += speedWidth()
        if (light != null) {
            if (w > 0f) w += px(12f)
            w += lightGroupWidth(light!!)
        }
        val road = info.curRoad
        if (road.isNotEmpty()) {
            if (w > 0f) w += px(12f)
            text.textSize = px(24f)
            w += Math.min(text.measureText(road), px(190f))
        }
        if (lanes.isNotEmpty()) {
            val ln = lanes.size.coerceAtMost(MAX_LANES)
            val lw = ln * (laneH * LANE_ASPECT) + (ln - 1) * laneGap
            if (lw > w) w = lw
        }
        return w.coerceAtLeast(px(60f))
    }

    private fun speedWidth(): Float {
        text.textSize = px(34f)
        text.typeface = typeface ?: Typeface.DEFAULT_BOLD
        val n = text.measureText(if (info.speed >= 0) info.speed.toString() else "--")
        text.textSize = px(16f)
        return n + px(4f) + text.measureText("km/h")
    }

    private fun lightWidth(l: AmapNavi.Light): Float {
        val h = px(44f)
        text.textSize = h * 0.60f
        text.typeface = typeface ?: Typeface.DEFAULT_BOLD
        val tw = text.measureText(if (l.countdown > 0) l.countdown.toString() else "--")
        return px(11f) + h * 0.30f + px(7f) + tw + px(7f) + h * 0.34f + px(11f)
    }

    /** 胶囊 + 距离文字占的总宽（没距离时就是胶囊宽）。 */
    private fun lightGroupWidth(l: AmapNavi.Light): Float =
        lightWidth(l) + if (l.distanceText.isNotEmpty()) lightDisGap + lightDisW else 0f

    /** 一排车道的自然宽度（还没压窄的时候）。 */
    private fun lanesWidth(list: List<AmapNavi.Lane>): Float {
        val n = list.size.coerceAtMost(MAX_LANES)
        if (n == 0) return 0f
        return n * (laneH * LANE_ASPECT) + (n - 1) * laneGap
    }

    // ---------------- 绘制 ----------------

    override fun onDraw(canvas: Canvas) {
        if (navi) {
            drawNavi(canvas)
            return
        }
        if (light == null && lanes.isEmpty() && info.curRoad.isEmpty()) {
            drawPlaceholder(canvas)
            return
        }
        drawCruise(canvas)
    }

    /** 无数据时的一行淡字，免得桌面上那块卡片空着不知道是干什么的。 */
    private fun drawPlaceholder(c: Canvas) {
        if (placeholder.isEmpty()) return
        text.typeface = typeface
        text.textSize = px(22f)
        text.color = sub()
        text.textAlign = Paint.Align.LEFT
        c.drawText(placeholder, 0f, height / 2f - (text.ascent() + text.descent()) / 2f, text)
    }

    // ---- 导航态 ----

    private fun drawNavi(c: Canvas) {
        val w = width.toFloat()
        val i = info
        val l = light

        // row1：转向图标 + 到路口的距离 + 目标道路（右对齐）
        drawTurn(c, iconBox / 2f, row1H / 2f, iconBox, i.icon)
        var x = iconBox + px(14f)

        val dis = splitDistance(i.segDis)
        if (dis.first.isNotEmpty()) {
            text.typeface = typeface ?: Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.LEFT
            text.textSize = px(34f)
            text.color = ink
            val baseline = row1H / 2f - (text.ascent() + text.descent()) / 2f
            c.drawText(dis.first, x, baseline, text)
            val nw = text.measureText(dis.first)
            if (dis.second.isNotEmpty()) {
                text.textSize = px(18f)
                text.color = sub()
                c.drawText(dis.second, x + nw + px(4f), baseline, text)
                x += nw + px(4f) + text.measureText(dis.second)
            } else {
                x += nw
            }
            x += px(18f)
        }

        val road = i.nextRoad.ifEmpty { i.curRoad }
        if (road.isNotEmpty() && w - x > px(40f)) {
            text.textSize = px(22f)
            text.typeface = typeface
            text.color = ink
            text.textAlign = Paint.Align.RIGHT
            c.drawText(
                ellipsize(road, w - x, text), w,
                row1H / 2f - (text.ascent() + text.descent()) / 2f, text
            )
            text.textAlign = Paint.Align.LEFT
        }

        // row2：车道（左） + 全程剩余（右）。右上角还有「目标道路」，两条文本都要让位，
        // 所以车道先按 55% 宽度压，剩下的宽度才给剩余里程，避免两边叠字。
        val y2 = row1H + gap
        val laneW = if (lanes.isNotEmpty()) {
            Math.min(lanesWidth(lanes), w * 0.55f)
        } else 0f
        if (lanes.isNotEmpty()) {
            drawLanes(c, lanes, 0f, y2 + (row2H - laneH) / 2f, laneH, laneW)
        }
        val remain = joinRemain(i.remainDis, i.remainTime)
        val remainMax = w - laneW - px(14f)
        if (remain.isNotEmpty() && remainMax > px(30f)) {
            text.textSize = px(20f)
            text.typeface = typeface
            text.color = sub()
            text.textAlign = Paint.Align.RIGHT
            c.drawText(
                ellipsize(remain, remainMax, text), w,
                y2 + row2H / 2f - (text.ascent() + text.descent()) / 2f, text
            )
            text.textAlign = Paint.Align.LEFT
        }

        // row3：红绿灯胶囊 + 紧挨右边的灯距（左） + 预计到达（右）
        //
        // ★ 6.0.54：这一行改成「必须完整显示的项先拿宽度」，不再先给 ETA 留位
        // 再把残羹丢给灯距。6.0.53 的「距灯 14…」是两个真因叠加：
        //   ① `ETA_TEXT` 实测就是「预计18:14到达」整句，旧代码又拼成
        //      「预计 预计18:14到达 到达」，etaW 虚胖一倍；
        //   ② 灯距被 minOf(96dp, free) 硬裁，free 被虚胖的 etaW 吃掉后只剩几十 dp。
        // 现在灯距按实测宽度完整画出，ETA 拿剩下的宽度（不够就退成时刻）。
        val y3 = y2 + row2H + gap
        val etaFull = etaLabel(i.eta)
        val capW = if (l != null) lightWidth(l) else 0f // 注意：内部会改 text.textSize
        text.textSize = px(20f)
        text.typeface = typeface
        val disText = if (l != null) distanceLabel(l) else ""
        val disNeed = if (disText.isNotEmpty()) text.measureText(disText) else 0f
        val etaNeed = if (etaFull.isNotEmpty()) text.measureText(etaFull) else 0f

        val leftW = if (l != null) {
            capW + if (disText.isNotEmpty()) lightDisGap + disNeed else 0f
        } else 0f
        val etaAvail = w - leftW - px(14f)
        val etaDraw = when {
            etaFull.isEmpty() || etaAvail <= px(20f) -> ""
            etaNeed <= etaAvail -> etaFull
            else -> {
                // 灯距优先；ETA 放不下就只留时刻，再不够才省略号
                val clock = ETA_CLOCK.find(etaFull)?.value.orEmpty()
                if (clock.isNotEmpty() && text.measureText(clock) <= etaAvail) clock
                else ellipsize(etaFull, etaAvail, text)
            }
        }

        if (l != null) {
            val top = y3 + (row3H - px(44f)) / 2f
            // 传实测需求宽度（再夹一个「不许超出 View 右边缘」的上限）：
            // drawLightGroup 里宁可缩字号也不省略
            val disMax = Math.min(disNeed, w - capW - lightDisGap - px(12f))
            drawLightGroup(c, l, 0f, top, if (disText.isNotEmpty()) disMax else 0f)
        }
        if (etaDraw.isNotEmpty()) {
            text.textSize = px(20f)
            text.typeface = typeface
            text.color = sub()
            text.textAlign = Paint.Align.RIGHT
            c.drawText(
                etaDraw, w,
                y3 + row3H / 2f - (text.ascent() + text.descent()) / 2f, text
            )
            text.textAlign = Paint.Align.LEFT
        }

        // 底部进度条（高德没给总里程时只画轨道）
        val yb = y3 + row3H + gap
        drawBar(c, 0f, yb, w, barH, i.progress)
    }

    // ---- 巡航态 ----

    private fun drawCruise(c: Canvas) {
        val i = info
        var x = 0f
        val cy = row1H / 2f

        if (showSpeed && i.speed >= 0) {
            text.typeface = typeface ?: Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.LEFT
            text.textSize = px(34f)
            text.color = ink
            val s = i.speed.toString()
            val baseline = cy - (text.ascent() + text.descent()) / 2f
            c.drawText(s, x, baseline, text)
            val nw = text.measureText(s)
            text.textSize = px(16f)
            text.color = sub()
            c.drawText("km/h", x + nw + px(4f), baseline, text)
            x += nw + px(4f) + text.measureText("km/h") + px(14f)
        }

        light?.let {
            drawLightGroup(c, it, x, cy - px(44f) / 2f, lightDisW)
            x += lightGroupWidth(it) + px(14f)
        }

        val road = i.curRoad
        if (road.isNotEmpty() && width - x > px(30f)) {
            text.typeface = typeface
            text.textSize = px(24f)
            text.color = ink
            text.textAlign = Paint.Align.LEFT
            c.drawText(
                ellipsize(road, width - x, text), x,
                cy - (text.ascent() + text.descent()) / 2f, text
            )
        }

        if (lanes.isNotEmpty()) {
            drawLanes(c, lanes, 0f, row1H + gap, laneH, width.toFloat())
        }
    }

    // ---- 零件 ----

    /**
     * 红绿灯胶囊：底色按灯色淡染，左边一个实心圆点，右边倒计时，最右一个方向箭头。
     * `x0` 是胶囊左边缘（v6.0.35 起一律从左边排，不再贴右）。
     */
    private fun drawLight(c: Canvas, l: AmapNavi.Light, x0: Float, top: Float) {
        val col = lightColor(l)
        val h = px(44f)
        val w = lightWidth(l)
        val cy = top + h / 2f

        fill.style = Paint.Style.FILL
        fill.color = withAlpha(col, 0x24)
        fill.colorFilter = null
        r.set(x0, top, x0 + w, top + h)
        val rad = h / 2f
        c.drawRoundRect(r, rad, rad, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = px(1.5f)
        fill.color = withAlpha(col, 0x66)
        c.drawRoundRect(r, rad, rad, fill)

        fill.style = Paint.Style.FILL
        fill.color = col
        val dotR = h * 0.15f
        val dotX = x0 + px(11f) + dotR
        c.drawCircle(dotX, cy, dotR, fill)

        text.typeface = typeface ?: Typeface.DEFAULT_BOLD
        text.textSize = h * 0.60f
        text.color = col
        text.textAlign = Paint.Align.LEFT
        val label = if (l.countdown > 0) l.countdown.toString() else "--"
        val tw = text.measureText(label)
        val tx = dotX + dotR + px(7f)
        c.drawText(label, tx, cy - (text.ascent() + text.descent()) / 2f, text)

        val arrowSize = h * 0.44f
        val ax = tx + tw + px(7f) + arrowSize / 2f
        buildArrow(ax, cy, arrowSize, lightDir(l))
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = h * 0.085f
        fill.strokeCap = Paint.Cap.ROUND
        fill.strokeJoin = Paint.Join.ROUND
        fill.color = col
        fill.colorFilter = null
        c.drawPath(path, fill)
    }

    /**
     * v6.0.53：胶囊 + 紧挨右边的「距灯 130米」。
     *
     * v6.0.54：**不再用固定常量裁字**。`maxDisW` 是这一行能给的宽度上限，先按 20sp
     * 实测整串文本的宽度，超了就按实测比例缩字号（保底 13sp），保证「距灯 146米」
     * 完整可见；只有连保底字号都放不下才用省略号兜底。高德没给距离时只画胶囊，
     * 排版与 6.0.52 完全一致。
     */
    private fun drawLightGroup(
        c: Canvas, l: AmapNavi.Light, x0: Float, top: Float, maxDisW: Float
    ) {
        drawLight(c, l, x0, top) // 内部会改 text.textSize，下面重设
        val label = distanceLabel(l)
        val avail = minOf(lightDisW, maxDisW)
        if (label.isEmpty() || avail <= px(12f)) return
        val tx = x0 + lightWidth(l) + lightDisGap
        val cy = top + px(44f) / 2f
        text.typeface = typeface
        text.textSize = px(20f)
        text.color = lightColor(l)
        text.textAlign = Paint.Align.LEFT
        val need = text.measureText(label)
        if (need > avail) {
            text.textSize = (text.textSize * avail / need).coerceAtLeast(px(13f))
        }
        val out = if (text.measureText(label) <= avail) label
        else ellipsize(label, avail, text)
        c.drawText(out, tx, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    /** 一排车道箭头，从左往右排；宽度不够就等比压窄，绝不溢出。 */
    private fun drawLanes(
        c: Canvas,
        list: List<AmapNavi.Lane>,
        left: Float,
        top: Float,
        h: Float,
        maxW: Float
    ) {
        val n = list.size.coerceAtMost(MAX_LANES)
        if (n == 0) return
        var one = h * LANE_ASPECT
        val totalGap = (n - 1) * laneGap
        if (n * one + totalGap > maxW) {
            one = ((maxW - totalGap) / n).coerceAtLeast(px(10f))
        }
        var x = left
        fill.style = Paint.Style.FILL
        fill.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
        for (i in 0 until n) {
            val lane = list[i]
            val bmp: Bitmap? = AmapNavi.laneBitmap(context, lane.icon)
            if (bmp != null) {
                dst.set(x.toInt(), top.toInt(), (x + one).toInt(), (top + h).toInt())
                fill.alpha = if (lane.advised) ((1f - 0.7f * phase) * 255).toInt() else 255
                c.drawBitmap(bmp, null, dst, fill)
            }
            x += one + laneGap
        }
        fill.alpha = 255
        fill.colorFilter = null
    }

    /** 路线进度条：轨道 + 已走路程。`progress < 0` 时只画轨道。 */
    private fun drawBar(c: Canvas, left: Float, top: Float, w: Float, h: Float, progress: Int) {
        fill.colorFilter = null
        fill.style = Paint.Style.FILL
        fill.color = withAlpha(ink, 0x22)
        r.set(left, top, left + w, top + h)
        val rad = h / 2f
        c.drawRoundRect(r, rad, rad, fill)
        if (progress < 0) return
        val p = progress.coerceIn(0, 100) / 100f
        if (p <= 0.001f) return
        fill.color = withAlpha(ink, 0xB0)
        r.set(left, top, left + w * p, top + h)
        c.drawRoundRect(r, rad, rad, fill)
    }

    /**
     * 转向图标（100×100 设计坐标系，按 [size] 缩放）。
     * 线宽 / 箭头三角都用比例算，所以同一个图案在卡片和悬浮窗上都一样粗。
     */
    private fun drawTurn(c: Canvas, cx: Float, cy: Float, size: Float, icon: Int) {
        c.save()
        c.translate(cx, cy)
        c.scale(size / 100f, size / 100f)

        path.reset()
        head.reset()
        var tipX = 0f
        var tipY = -48f
        var dirX = 0f
        var dirY = -1f

        when (icon) {
            AmapNavi.TURN_LEFT -> {
                path.moveTo(30f, 46f)
                path.lineTo(30f, 4f)
                path.quadTo(30f, -14f, 12f, -14f)
                path.lineTo(-10f, -14f)
                tipX = -46f; tipY = -14f; dirX = -1f; dirY = 0f
            }
            AmapNavi.TURN_RIGHT -> {
                path.moveTo(-30f, 46f)
                path.lineTo(-30f, 4f)
                path.quadTo(-30f, -14f, -12f, -14f)
                path.lineTo(10f, -14f)
                tipX = 46f; tipY = -14f; dirX = 1f; dirY = 0f
            }
            AmapNavi.TURN_LEFT_FRONT -> {
                path.moveTo(18f, 46f)
                path.lineTo(18f, 10f)
                path.lineTo(-18f, -26f)
                tipX = -34f; tipY = -42f; dirX = -0.707f; dirY = -0.707f
            }
            AmapNavi.TURN_RIGHT_FRONT -> {
                path.moveTo(-18f, 46f)
                path.lineTo(-18f, 10f)
                path.lineTo(18f, -26f)
                tipX = 34f; tipY = -42f; dirX = 0.707f; dirY = -0.707f
            }
            AmapNavi.TURN_UTURN -> {
                path.moveTo(26f, 46f)
                path.lineTo(26f, 2f)
                path.quadTo(26f, -26f, 0f, -26f)
                path.quadTo(-26f, -26f, -26f, 2f)
                path.lineTo(-26f, 12f)
                tipX = -26f; tipY = 44f; dirX = 0f; dirY = 1f
            }
            AmapNavi.TURN_WAYPOINT -> {
                path.moveTo(0f, 46f)
                path.lineTo(0f, -24f)
                tipX = 0f; tipY = -48f; dirX = 0f; dirY = -1f
                path.addCircle(0f, 6f, 15f, Path.Direction.CW)
            }
            AmapNavi.TURN_RAMP_IN -> {
                path.moveTo(0f, 46f)
                path.lineTo(0f, -24f)
                tipX = 0f; tipY = -48f; dirX = 0f; dirY = -1f
                path.moveTo(2f, 16f)
                path.lineTo(30f, -12f)
            }
            AmapNavi.TURN_RAMP_OUT -> {
                path.moveTo(0f, 46f)
                path.lineTo(0f, -24f)
                tipX = 0f; tipY = -48f; dirX = 0f; dirY = -1f
                path.moveTo(-2f, 16f)
                path.lineTo(-30f, -12f)
            }
            AmapNavi.TURN_END -> {
                path.moveTo(0f, 46f)
                path.lineTo(0f, -22f)
                path.moveTo(-20f, -20f)
                path.lineTo(20f, -20f)
                path.moveTo(-20f, -38f)
                path.lineTo(20f, -38f)
                tipX = 0f; tipY = 0f; dirX = 0f; dirY = 0f
            }
            else -> { // 直行（含未知编号兜底）
                path.moveTo(0f, 46f)
                path.lineTo(0f, -24f)
                tipX = 0f; tipY = -48f; dirX = 0f; dirY = -1f
            }
        }

        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 12f
        fill.strokeCap = Paint.Cap.ROUND
        fill.strokeJoin = Paint.Join.ROUND
        fill.color = ink
        fill.colorFilter = null
        c.drawPath(path, fill)

        if (dirX != 0f || dirY != 0f) {
            buildHead(head, tipX, tipY, dirX, dirY, 26f, 16f)
            fill.style = Paint.Style.FILL
            c.drawPath(head, fill)
        }
        c.restore()
    }

    /** 实心三角箭头：尖在 (tip)，朝 (dir) 方向长 [len]，底边半宽 [halfW]。 */
    private fun buildHead(
        p: Path, tipX: Float, tipY: Float, dirX: Float, dirY: Float,
        len: Float, halfW: Float
    ) {
        val l = Math.sqrt((dirX * dirX + dirY * dirY).toDouble()).toFloat().coerceAtLeast(0.001f)
        val ux = dirX / l
        val uy = dirY / l
        val bx = tipX - ux * len
        val by = tipY - uy * len
        val nx = -uy
        val ny = ux
        p.moveTo(tipX, tipY)
        p.lineTo(bx + nx * halfW, by + ny * halfW)
        p.lineTo(bx - nx * halfW, by - ny * halfW)
        p.close()
    }

    /**
     * 红绿灯方向箭头（统一语义：1 左、2 右、3 掉头、4 直行）。
     *
     * ★ 高德在导航态和巡航态用的 `dir` 编号**不一样**：
     * 导航 1 左 / 2 右 / 3 掉头 / 4 直行；巡航 1 左 / 2 直行 / 3 右（没有掉头）。
     * 直接照抄数字会把巡航的「直行」画成右转。
     */
    private fun lightDir(l: AmapNavi.Light): Int {
        if (l.navi) return l.dir
        return when (l.dir) {
            1 -> 1
            2 -> 4
            3 -> 2
            else -> 4
        }
    }

    private fun buildArrow(cx: Float, cy: Float, size: Float, dir: Int) {
        val h = size / 2f
        path.reset()
        when (dir) {
            1 -> { // 左转
                path.moveTo(cx + h * 0.45f, cy + h)
                path.lineTo(cx + h * 0.45f, cy - h * 0.15f)
                path.lineTo(cx - h * 0.45f, cy - h * 0.15f)
                path.moveTo(cx - h * 0.05f, cy - h * 0.62f)
                path.lineTo(cx - h * 0.45f, cy - h * 0.15f)
                path.lineTo(cx - h * 0.05f, cy + h * 0.32f)
            }
            2 -> { // 右转
                path.moveTo(cx - h * 0.45f, cy + h)
                path.lineTo(cx - h * 0.45f, cy - h * 0.15f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.15f)
                path.moveTo(cx + h * 0.05f, cy - h * 0.62f)
                path.lineTo(cx + h * 0.45f, cy - h * 0.15f)
                path.lineTo(cx + h * 0.05f, cy + h * 0.32f)
            }
            3 -> { // 掉头
                path.moveTo(cx + h * 0.42f, cy + h)
                path.lineTo(cx + h * 0.42f, cy - h * 0.1f)
                path.cubicTo(
                    cx + h * 0.42f, cy - h * 0.8f,
                    cx - h * 0.42f, cy - h * 0.8f,
                    cx - h * 0.42f, cy - h * 0.1f
                )
                path.lineTo(cx - h * 0.42f, cy + h * 0.45f)
                path.moveTo(cx - h * 0.85f, cy + h * 0.05f)
                path.lineTo(cx - h * 0.42f, cy + h * 0.5f)
                path.lineTo(cx + h * 0.01f, cy + h * 0.05f)
            }
            else -> { // 直行
                path.moveTo(cx, cy + h)
                path.lineTo(cx, cy - h)
                path.moveTo(cx - h * 0.42f, cy - h * 0.42f)
                path.lineTo(cx, cy - h)
                path.lineTo(cx + h * 0.42f, cy - h * 0.42f)
            }
        }
    }

    /**
     * 灯色。★ 导航态和巡航态的 `status` 编号**不一样**，见 [AmapNavi] 类注释：
     * 导航 4 绿 / 1 红，巡航 1 绿 / 0 红。
     */
    private fun lightColor(l: AmapNavi.Light): Int {
        val green = 0xFF34C759.toInt()
        val red = 0xFFFF3B30.toInt()
        val amber = 0xFFE0A100.toInt()
        return if (l.navi) {
            when (l.status) {
                4 -> green
                1 -> red
                else -> amber
            }
        } else {
            when (l.status) {
                1 -> green
                0 -> red
                else -> amber
            }
        }
    }

    // ---------------- 文本工具 ----------------

    /** `"300米"` / `"1.2公里"` → 数字 + 单位。高德两种写法都会出现。 */
    private fun splitDistance(s: String): Pair<String, String> {
        if (s.isEmpty()) return "" to ""
        for (u in arrayOf("公里", "千米", "km", "米", "m")) {
            if (s.endsWith(u)) return s.dropLast(u.length).trim() to u
        }
        return s to ""
    }

    private fun joinRemain(dis: String, time: String): String = when {
        dis.isEmpty() -> time
        time.isEmpty() -> dis
        else -> "$dis · $time"
    }

    /**
     * ★ 高德 `ETA_TEXT` 实机截图中就是「预计18:14到达」整句，不是纯时刻。
     * 6.0.53 之前显示层又套了「预计 … 到达」，于是屏幕上出现
     * 「预计 预计18:14到达 到达」——宽度凭空翻倍，正好把灯距挤成省略号。
     * 这里只做显示层规整：已是整句就原样用，纯时刻才补前后缀。
     */
    private fun etaLabel(s: String): String {
        val v = s.trim()
        if (v.isEmpty()) return ""
        return if (v.contains("预计") || v.contains("到达")) v else "预计 $v 到达"
    }

    /** 胶囊右侧的距离文案；高德没给距离就返回空串（保持 6.0.52 排版）。 */
    private fun distanceLabel(l: AmapNavi.Light): String =
        if (l.distanceText.isEmpty()) "" else "距灯 " + l.distanceText

    /** 超长文本截断加省略号（不依赖 TextView，只能手写）。 */
    private fun ellipsize(s: String, maxW: Float, p: Paint): String {
        if (maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        var end = s.length - 1
        while (end > 0) {
            val t = s.substring(0, end) + "…"
            if (p.measureText(t) <= maxW) return t
            end--
        }
        return ""
    }

    private fun sub(): Int = withAlpha(ink, 0xAA)

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    private companion object {
        /** 一条车道图标固定按 53:70 画（原图就是 53×70），避免被拉扁。 */
        const val LANE_ASPECT = 53f / 70f

        /** row3 空间不够时，从整句 ETA 里只摘出时刻。 */
        val ETA_CLOCK = Regex("\\d{1,2}:\\d{2}")

        /** 最多显示几条车道。再多这条信息区就装不下了，取靠左的前几条（就是靠左车道）。 */
        const val MAX_LANES = 6
    }
}
