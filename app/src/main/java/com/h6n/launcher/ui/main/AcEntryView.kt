package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.h6n.launcher.core.Hvac
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌面主页的「空调内 / 外循环」图标（6.0.100，自绘 View）。
 *
 * ## 为什么放这里
 * 用户要在主页一眼看到、一指点到。位置与车模左下角那枚「应用商店」对称 ——
 * 贴在胎压车模卡的**右下角**（[TireCard] 里 FrameLayout 的 `BOTTOM or END`），
 * 那块是车模右侧通道下方、胎压数字之外的空白。
 *
 * ## 显示什么
 * - 内循环：金色圆角底 + 深色循环箭头，下面写「内循环」
 * - 外循环：冷灰蓝底 + 白箭头，下面写「外循环」
 * - 读不到（车型不支持 / 车控没连）：半透明底 + 白箭头，写「空调」
 *
 * 状态来自 [CarSignal.hvacRecirc][com.h6n.launcher.core.CarSignal.hvacRecirc]，
 * 由 [TireCard.bind] 每秒推一次；点击后 [Hvac.toggleAsync] 会在回读确认时
 * 通过 `SignalHub.update` 立刻推一帧，图标不用等下一轮 3 秒刷新。
 *
 * 纯 Kotlin 手写绘制：不用 XML layout/drawable，也不用 setShadowLayer（红线）。
 */
@SuppressLint("ViewConstructor")
class AcEntryView(ctx: Context, onClick: () -> Unit) : View(ctx) {

    /** 1 内 / 0 外 / -1 未知。 */
    private var state = -1
    private var theme: Theme? = null
    private var busy = false

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val box = RectF()
    private val arrow = Path()

    init {
        isClickable = true
        contentDescription = "空调内外循环"
        setOnClickListener { fire() }
        // 按压反馈：按下半透明，抬起恢复。返回 false 让 OnClickListener 照常收到点击。
        setOnTouchListener { v, e ->
            v.alpha = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (busy) 0.55f else 0.62f
                else -> if (busy) 0.55f else 1f
            }
            false
        }
    }

    // 固定尺寸（FrameLayout 里 WRAP 不会自己量出大小，必须重写 onMeasure；与应用商店入口对齐）
    override fun onMeasure(wm: Int, hm: Int) {
        setMeasuredDimension(context.dp(72), context.dp(100))
    }

    fun applyTheme(t: Theme) {
        theme = t
        invalidate()
    }

    /** @param v 1 内循环 / 0 外循环 / 其它（-1）= 未知。 */
    fun setState(v: Int) {
        val s = if (v == 0 || v == 1) v else -1
        if (s == state) return
        state = s
        contentDescription = "空调 " + when (s) {
            1 -> "内循环"
            0 -> "外循环"
            else -> "状态未知"
        }
        invalidate()
    }

    private fun fire() {
        if (busy) return
        busy = true
        alpha = 0.55f
        Hvac.toggleAsync(context) { msg ->
            busy = false
            alpha = 1f
            setState(Hvac.state(context))
            runCatching {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val top = context.dp(2).toFloat()
        val s = context.dp(66).toFloat()
        val left = (w - s) / 2f
        val t = theme

        // 底色：内循环金、外循环冷灰蓝、未知半透明
        plate.style = Paint.Style.FILL
        plate.color = when (state) {
            1 -> if (busy) 0xFFC99F35.toInt() else 0xFFE0B44C.toInt()
            0 -> if (busy) 0xFF46536A.toInt() else 0xFF55637A.toInt()
            else -> 0x59FFFFFF
        }
        box.set(left, top, left + s, top + s)
        c.drawRoundRect(box, s * 0.24f, s * 0.24f, plate)
        // 未知态补一圈淡描边，深浅壁纸上都能看出「这里有个按钮」
        if (state < 0) {
            plate.style = Paint.Style.STROKE
            plate.strokeWidth = s * 0.012f
            plate.color = 0x66FFFFFF
            c.drawRoundRect(box, s * 0.24f, s * 0.24f, plate)
        }

        // 循环箭头：一段 260° 圆弧 + 端点一个三角箭头，颜色跟着底色反差走
        val fg = if (state == 1) 0xFF23180A.toInt() else Color.WHITE
        val cx = left + s / 2f
        val cy = top + s / 2f
        val r = s * 0.215f
        glyph.color = fg
        glyph.strokeWidth = s * 0.075f
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 118f, 258f, false, glyph)

        // 箭头画在 118+258=376°（≈16°）那一点，沿切线指向
        val end = Math.toRadians(16.0)
        val ex = cx + (r * cos(end)).toFloat()
        val ey = cy + (r * sin(end)).toFloat()
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val nx = cos(end).toFloat()
        val ny = sin(end).toFloat()
        val a = s * 0.115f
        val b = s * 0.055f
        arrow.reset()
        arrow.moveTo(ex + tx * a, ey + ty * a)
        arrow.lineTo(ex + nx * b, ey + ny * b)
        arrow.lineTo(ex - nx * b, ey - ny * b)
        arrow.close()
        solid.color = fg
        c.drawPath(arrow, solid)

        // 内循环再补一个「车里」的小圆点，和外循环一眼分得开
        if (state == 1) {
            solid.color = fg
            solid.alpha = 0xCC
            c.drawCircle(cx, cy, s * 0.045f, solid)
            solid.alpha = 255
        }

        // 图标下方文字：内循环 / 外循环 / 空调
        label.textSize = 14f * resources.displayMetrics.scaledDensity
        label.color = when {
            state == 1 -> 0xFFFFD98A.toInt()
            t != null -> t.color("p2TextMain", 0xFFFFFFFF.toInt())
            else -> Color.WHITE
        }
        val fm = label.fontMetrics
        val baseY = top + s + context.dp(3) - (fm.ascent + fm.descent) / 2f
        c.drawText(
            when (state) {
                1 -> "内循环"
                0 -> "外循环"
                else -> "空调"
            },
            cx, baseY, label
        )
    }
}
