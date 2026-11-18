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
import com.h6n.launcher.ui.common.dp

/**
 * 应用商店入口（6.0.91，自绘 View）：金色圆角底 + 白色购物袋 + 下方「应用商店」文字。
 *
 * 放在胎压车模卡左下角（用户红圈标注的位置）——比原先 dock 顶部那个 56dp 小图
 * 更醒目，而且**图标下方直接写「应用商店」**，一眼就知道点开会干什么。
 *
 * 纯 Kotlin 手写绘制：不用 XML，也不用 setShadowLayer（红线）。金色底比车模的
 * 白色车身、胎压红贴片都跳，文字用白底描边保证深浅壁纸上都看得清。
 */
@SuppressLint("ViewConstructor")
class StoreEntryView(ctx: Context, onClick: () -> Unit) : View(ctx) {

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        isClickable = true
        contentDescription = "应用商店"
        setOnClickListener { onClick() }
        // 按压反馈：半透明按下、抬起恢复。返回 false 让 OnClickListener 照常收到点击。
        setOnTouchListener { v, e ->
            v.alpha = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 0.62f
                else -> 1f
            }
            false
        }
    }

    // 固定尺寸（FrameLayout 里 WRAP 不会自己量出大小，必须重写 onMeasure）
    override fun onMeasure(wm: Int, hm: Int) {
        setMeasuredDimension(context.dp(72), context.dp(100))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val plateTop = context.dp(2).toFloat()
        val s = context.dp(66).toFloat()
        val left = (w - s) / 2f

        // 金色圆角底（按下时压暗一档）
        plate.color = if (alpha < 0.9f) 0xFFC99F35.toInt() else 0xFFE0B44C.toInt()
        c.drawRoundRect(RectF(left, plateTop, left + s, plateTop + s), s * 0.24f, s * 0.24f, plate)

        // 白色购物袋：上宽下窄的梯形 + 半圆提手
        val cx = left + s / 2f
        val bagTop = plateTop + s * 0.42f
        val bagBottom = plateTop + s * 0.74f
        val halfTop = s * 0.22f
        val halfBottom = s * 0.19f
        val bagPath = Path().apply {
            moveTo(cx - halfTop, bagTop)
            lineTo(cx + halfTop, bagTop)
            lineTo(cx + halfBottom, bagBottom)
            lineTo(cx - halfBottom, bagBottom)
            close()
        }
        c.drawPath(bagPath, bag)
        handle.strokeWidth = s * 0.055f
        val hr = s * 0.10f
        c.drawArc(
            cx - hr, bagTop - hr * 0.95f, cx + hr, bagTop + hr * 0.75f,
            180f, 180f, true, handle
        )

        // 图标下方文字「应用商店」
        // 6.0.105：14 → 17sp（宽 72dp 的入口里 4 个字最多放 17sp，21sp 会被裁掉）
        label.textSize = 17f * resources.displayMetrics.scaledDensity
        val fm = label.fontMetrics
        val baseY = plateTop + s + context.dp(3) - (fm.ascent + fm.descent) / 2f
        c.drawText("应用商店", cx, baseY, label)
    }
}
