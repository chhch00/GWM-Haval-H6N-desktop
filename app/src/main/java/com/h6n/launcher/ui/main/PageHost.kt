package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf
import kotlin.math.abs

/**
 * 桌面左右翻页容器：第 1 页车况（[SixGrid]）／第 2 页应用（[AppPageView]）。
 *
 * ## 交互
 * - 横向滑动超过 60dp 且明显比竖向大 → 翻页（和原版 `gesture.axisCheck` 一个意思：
 *   先在按下时记坐标，横向位移超过 touchSlop 才抢事件，所以卡片上的点击、长按都不受影响）；
 * - 底部两个小圆点：当前页高亮，点一下也能翻页。
 *
 * 没有做"跟手拖拽"—— 车机上手指行程长、卡片还要长按，跟手容易和长按打架，
 * 这里用滑动一下 → 整页平移动画，视觉上够用且不会误触。
 */
@SuppressLint("ViewConstructor")
class PageHost(ctx: Context) : FrameLayout(ctx) {

    private val pages = ArrayList<View>(2)
    private val dots = LinearLayout(ctx)
    private val dotViews = ArrayList<View>(2)

    private var index = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    /** 翻到某一页之后（MainActivity 用来刷新应用列表）。 */
    var onPageChanged: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        dots.background = GradientDrawable().apply {
            cornerRadius = ctx.dpf(12f)
            setColor(0x33000000)
        }
        dots.setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        addView(dots, LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = ctx.dp(8)
        })
    }

    fun setPages(vararg views: View) {
        for (v in views) {
            // 页面插在 dots 前面（dots 是第 0 个子 View，要始终盖在最上层）
            addView(v, pages.size + 1, LayoutParams(MATCH, MATCH))
            pages.add(v)
            v.visibility = if (pages.size == 1) VISIBLE else INVISIBLE
        }
        dots.bringToFront()
        buildDots()
    }

    fun currentPage(): Int = index

    /** @param animate 是否播平移动画（翻页按钮/圆点用 true，初始化用 false） */
    fun showPage(target: Int, animate: Boolean = true) {
        val to = target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (to == index && pages.getOrNull(to)?.visibility == VISIBLE) return
        val from = pages.getOrNull(index)
        val dest = pages.getOrNull(to) ?: return
        val w = width.toFloat().let { if (it > 0f) it else resources.displayMetrics.widthPixels.toFloat() }
        val dir = if (to > index) 1 else -1

        index = to
        paintDots()
        dest.visibility = VISIBLE
        if (!animate || from == null || from === dest) {
            dest.translationX = 0f
            dest.alpha = 1f
            for (p in pages) if (p !== dest) p.visibility = INVISIBLE
        } else {
            dest.translationX = dir * w * 0.28f
            dest.alpha = 0.2f
            dest.animate().translationX(0f).alpha(1f).setDuration(190L).start()
            from.animate().translationX(-dir * w * 0.28f).alpha(0f).setDuration(190L)
                .withEndAction {
                    from.visibility = INVISIBLE
                    from.translationX = 0f
                    from.alpha = 1f
                }.start()
        }
        onPageChanged?.invoke(index)
    }

    fun next() = showPage(index + 1)
    fun prev() = showPage(index - 1)

    private fun buildDots() {
        dots.removeAllViews()
        dotViews.clear()
        if (pages.size < 2) {
            dots.visibility = GONE
            return
        }
        dots.visibility = VISIBLE
        for (i in pages.indices) {
            val v = View(context)
            dots.addView(v, LinearLayout.LayoutParams(context.dp(9), context.dp(9)).apply {
                if (i > 0) leftMargin = context.dp(8)
            })
            dotViews.add(v)
            v.setOnClickListener { showPage(i) }
        }
        paintDots()
    }

    private fun paintDots() {
        for ((i, v) in dotViews.withIndex()) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) Color.WHITE else 0x66FFFFFF)
            }
        }
    }

    // ---------------- 滑动 ----------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                dragging = false
                if (abs(dx) > context.dp(60) && abs(dx) > abs(dy)) {
                    if (dx < 0) next() else prev()
                }
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private companion object {
        const val MATCH = -1
        const val WRAP = -2
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}
