package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf

/**
 * 停靠栏槽位选择器（对照旧版 `DockPicker`）。
 *
 * ## 为什么自己搭面板，不用 `setItems` + `setAdapter`
 * 车机上反馈"点「选择应用」是一个空列表，加不了应用"。`AlertDialog.setAdapter`
 * 用的是系统 ListView + 我们临时 new 的 TextView，行高在 240dpi 车机屏上被压得很小，
 * 而且没法一眼看出"到底是没查到应用，还是面板没渲染"。
 *
 * 现在改成自绘面板：标题、**读到的应用数量**、可滚动的应用行（48dp 图标 + 大字），
 * 底部两排「重试 / 主页 / 设置」+「息屏 / 清空 / 取消」（6.0.32 加「息屏」后一行放不下，
 * 1280×720 车机上会溢出面板宽度，所以拆成两行）。
 * ★ 列表高度用**固定 px**而不是 `height=0 + weight`：AlertDialog 的内容是 wrap_content，
 *   带权重的 0 高度子 View 在 wrap 父容器里会被量成 0 —— 那样面板会"看起来是空的"。
 *
 * ## 6.0.31：BadTokenException 修复（友盟车机实测）
 * `AlertDialog.show()` 需要宿主窗口的 app token。桌面/设置页传的是 Activity，没问题；
 * 但**悬浮层那份 DockView 用的是 `applicationContext.createConfigurationContext(...)`**
 * （NavOverlayService.uiCtx，为了对齐 720 设计稿密度），它没有任何 Activity token，
 * 点空格子必然抛
 * `WindowManager$BadTokenException: Unable to add window -- token null is not valid`。
 *
 * 因此这里分两条路：
 *  1. 能沿 ContextWrapper 链找到**活着且窗口仍 attach** 的 Activity → 照旧用 AlertDialog，
 *     并用 runCatching 兜住 show（防校验与 show 之间 Activity 被销毁的竞态）。
 *  2. 找不到 Activity（悬浮层）→ 不用 Dialog，改用宿主**当前真正挂载 dock 的那个
 *     WindowManager + 窗口 type** 叠一层自绘 overlay，同一个面板、同一个 dismiss 语义。
 *  3. 两者都不可用 → 只给一句 Toast，绝不把异常抛到主线程。
 */
object DockPicker {

    /**
     * 悬浮层宿主。用 lambda 是为了点击那一刻才取值：dock 的 WindowManager / type
     * 会随无障碍服务启停变化，构造 DockView 时缓存下来会拿到旧的（甚至已 remove 的）WM。
     */
    data class PickerHost(
        val wm: () -> WindowManager?,
        val type: () -> Int
    )

    fun show(
        ctx: Context,
        slot: Int,
        count: Int,
        overlay: PickerHost? = null,
        onPicked: (String) -> Unit
    ) {
        val act = findActivity(ctx)
        // isDestroyed 是 API 17+，本工程 minSdk=27。
        // decorView.windowToken 在 Activity 窗口 detach 后会变 null，正好挡住"界面已销毁但
        // 点击回调还在消息队列里"这种车机上常见的延迟点击。
        val alive = act != null && !act.isFinishing && !act.isDestroyed &&
                act.window?.decorView?.windowToken != null

        if (alive && act != null) {
            var dlg: AlertDialog? = null
            val panel = buildPanel(ctx, slot, count, onPicked) { dlg?.dismiss() }
            val d = AlertDialog.Builder(act).setView(panel).create()
            dlg = d
            val (wPx, hPx) = panelSize(ctx, d.window?.windowManager)
            d.window?.setLayout(wPx, hPx)
            if (runCatching { d.show() }.isSuccess) return
            // show 失败（token 在校验后才失效）→ 撤掉这个 Dialog，再试 overlay 兜底
            runCatching { d.dismiss() }
        }

        if (overlay != null && showAsOverlay(ctx, slot, count, onPicked, overlay)) return

        // 既没有可用 Activity、又没有悬浮层宿主：静默降级，不能崩。
        runCatching {
            Toast.makeText(ctx.applicationContext, "请回到桌面再选择应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 沿 ContextWrapper 链找真正的 Activity；applicationContext / createConfigurationContext 都找不到。 */
    private fun findActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        var guard = 0
        while (c != null && guard++ < 8) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /**
     * 悬浮层专用：不用 Dialog，直接往 dock 所在的 WindowManager 叠一个窗口。
     * 用同一个 WM 和同一个 type，才能继承 2032（无障碍宿主）/ 2038（应用悬浮窗）的权限。
     */
    private fun showAsOverlay(
        ctx: Context,
        slot: Int,
        count: Int,
        onPicked: (String) -> Unit,
        host: PickerHost
    ): Boolean {
        val wm = host.wm() ?: return false
        val type = host.type()
        if (type == 0) return false

        var container: FrameLayout? = null
        fun close() {
            val c = container ?: return
            container = null
            runCatching { wm.removeView(c) }
        }

        val panel = buildPanel(ctx, slot, count, onPicked) { close() }
        // Dialog 自带系统窗口背景；overlay 没有，得自己补一块不透明背板
        panel.background = GradientDrawable().apply {
            cornerRadius = ctx.dpf(22f)
            setColor(0xFF16202C.toInt())
            setStroke(ctx.dp(1), 0x33FFFFFF)
        }
        // 面板自身吃掉点击，否则点面板空白处会冒泡到遮罩把面板关掉
        panel.isClickable = true
        val (wPx, hPx) = panelSize(ctx, wm)

        val c = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusableInTouchMode = true
            setOnClickListener { close() }
            setOnKeyListener { _, code, ev ->
                if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                    close(); true
                } else false
            }
            addView(panel, FrameLayout.LayoutParams(wPx, hPx, Gravity.CENTER))
        }

        val lp = WindowManager.LayoutParams().apply {
            this.type = type
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        container = c
        return runCatching { wm.addView(c, lp) }
            .onFailure { container = null }
            .isSuccess
    }

    /** 自绘面板本体；两条显示路径共用，dismiss 由宿主决定是 dismiss Dialog 还是 removeView。 */
    private fun buildPanel(
        ctx: Context,
        slot: Int,
        count: Int,
        onPicked: (String) -> Unit,
        dismiss: () -> Unit
    ): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(26), ctx.dp(16), ctx.dp(26), ctx.dp(14))
            // ★ 必须 MATCH_PARENT：下面列表用 height=0 + weight 1 撑满剩余空间，
            //   父容器是 wrap_content 的话这一行会被量成 0（面板就"看着是空的"）。
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(text(ctx, "第 ${slot + 1} 格 / 共 $count 格", 26f, Color.WHITE, true))

        val sub = text(ctx, "", 18f, 0xFF9FB2C8.toInt(), false)
        sub.setPadding(0, ctx.dp(6), 0, ctx.dp(8))
        root.addView(sub)

        val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(listBox, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        // 列表吃满「标题以下、按钮以上」的全部高度：按钮永远在面板底部，不会被列表顶出去
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun fill(list: List<DockSlots.AppEntry>) {
            listBox.removeAllViews()
            sub.text = if (list.isEmpty()) "没读到可启动的应用（点「重试」再查一次）"
            else "读到的应用：${list.size} 个 · 点一行就放进这一格"
            if (list.isEmpty()) {
                listBox.addView(text(ctx, "（空）", 20f, 0xFF7E93AB.toInt(), false).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ctx.dp(40), 0, ctx.dp(40))
                })
                return
            }
            for (e in list) {
                listBox.addView(appRow(ctx, e) {
                    onPicked(DockSlots.key(e))
                    dismiss()
                })
            }
        }

        // ---- 底部动作 ----
        // ★ 6.0.32：加了「息屏」之后一行放不下（1280×720 车机上 7 个胶囊会溢出面板宽），
        //   改成分两行排：上行 重试/主页/设置，下行 息屏/清空/取消。
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, ctx.dp(12), 0, 0)
        }
        fun actionLine(): LinearLayout {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            actions.addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (actions.childCount > 0) topMargin = ctx.dp(8) })
            return line
        }
        fun pill(label: String, onClick: () -> Unit): TextView =
            text(ctx, label, 19f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(20), ctx.dp(11), ctx.dp(20), ctx.dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dpf(22f)
                    setColor(0x1FFFFFFF)
                    setStroke(ctx.dp(1), 0x33FFFFFF)
                }
                isClickable = true
                setOnClickListener { onClick() }
            }

        val line1 = actionLine()
        line1.addView(pill("重试") { fill(DockSlots.listApps(ctx)) })
        line1.addView(space(ctx, 8))
        line1.addView(pill("主页") { onPicked(DockSlots.HOME); dismiss() })
        line1.addView(space(ctx, 8))
        line1.addView(pill("设置") { onPicked(DockSlots.SETTINGS); dismiss() })

        val line2 = actionLine()
        line2.addView(pill("息屏") { onPicked(DockSlots.NIGHT); dismiss() })
        line2.addView(space(ctx, 8))
        line2.addView(pill("清空") { onPicked(""); dismiss() })
        line2.addView(space(ctx, 8))
        line2.addView(pill("取消") { dismiss() })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fill(DockSlots.listApps(ctx))
        return root
    }

    /**
     * 面板尺寸不能用 resources.displayMetrics：横屏 Activity 里它有时还是竖屏的
     * 2400×1080（heightPixels 给 2400），算出来的面板比实际屏幕高一倍，
     * 底部「主页/设置/清空/取消」这一排就被顶到屏幕外面。
     * 用 WindowManager 的 realSize 拿当前旋转下的真实分辨率。
     */
    private fun panelSize(ctx: Context, wm: WindowManager?): Pair<Int, Int> {
        val pt = Point()
        runCatching {
            val display = wm?.defaultDisplay
                ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            display?.getRealSize(pt)
        }
        val sw = if (pt.x > 0) pt.x else ctx.resources.displayMetrics.widthPixels
        val sh = if (pt.y > 0) pt.y else ctx.resources.displayMetrics.heightPixels
        val short = Math.min(sw, sh)
        val long = Math.max(sw, sh)
        val wPx = (long * 0.56f).toInt().coerceAtLeast(ctx.dp(560))
        val hPx = (short * 0.88f).toInt()
        return wPx to hPx
    }

    /** 一行应用：48dp 图标 + 名称 + 右侧「放入」。整行可点。 */
    private fun appRow(ctx: Context, e: DockSlots.AppEntry, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dpf(16f)
                setColor(0x14FFFFFF)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dp(8) }
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(e.icon ?: ctx.packageManager.defaultActivityIcon)
        }
        row.addView(iv, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(46)))

        row.addView(text(ctx, e.label, 21f, Color.WHITE, false).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = ctx.dp(16)
        })

        row.addView(text(ctx, "放入 ›", 19f, 0xFF22D3EE.toInt(), true).apply {
            setPadding(ctx.dp(10), ctx.dp(4), 0, ctx.dp(4))
        })

        return row
    }

    private fun space(ctx: Context, dp: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(dp), 1) }

    private fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
}
