package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AppHide
import com.h6n.launcher.core.AppIconCache
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.roundToInt

/**
 * 桌面第 2 页：**整页应用图标墙**（原版 `appPage` 的位置）。
 *
 * ```
 * 全部应用 · 18 个                      [编辑]
 * ┌────┬────┬────┬────┬────┬────┐
 * │icon│    │    │    │    │    │   6 列，行数按高度排
 * ├────┼────┼────┼────┼────┼────┤
 * ```
 * - 数据源与停靠栏共用 [DockSlots.listApps]（带兜底通道）；
 * - 显示哪些由 `appPage.sel` 决定（设置页「功能 → 桌面应用」勾选，空 = 全部）；
 * - **用户应用排在系统应用前面**（6.0.105），各自按名称；
 * - 点图标启动；**长按图标**弹自绘菜单：启动 / 放进停靠栏 / 卸载 / 隐藏；
 * - 隐藏只影响这一页（名单在 [AppHide]），页头会出现「已隐藏 N」一键恢复；
 * - 右上「编辑」直接跳设置页勾选应用。
 */
@SuppressLint("ViewConstructor")
class AppPageView(ctx: Context, var insetLeftDp: Int = 0) : LinearLayout(ctx) {

    private var theme: Theme? = null
    private var all: List<DockSlots.AppEntry> = emptyList()

    private val title = TextView(ctx)
    private val restore = TextView(ctx)
    private val edit = TextView(ctx)
    private val scroll = ScrollView(ctx)
    private val grid = LinearLayout(ctx)

    private var lastW = 0
    private var lastH = 0
    /** 上一次建页的「内容指纹」：列数/尺寸/应用清单都没变就不重建整页 View。 */
    private var lastRebuildKey = ""

    /**
     * 应用清单扫描线程（v6.0.43）。
     *
     * 单线程 + MIN_PRIORITY + daemon：与 [AppIconCache] 的补载线程同款配置，
     * 扫描完就闲置，不额外占 CPU，也不会阻住进程退出。
     */
    private val scanExec = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, "h6-app-scan").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    })

    /** 已有一轮扫描在跑（这期间再来的请求合并成一次补扫）。 */
    private var scanRunning = false
    /** 扫描期间又被请求了一次：本轮回来后立刻再跑一轮。 */
    private var scanAgain = false
    /** 首轮扫描是否已回 —— 只影响空态文案（还在读 / 真的没读到）。 */
    private var scanDone = false

    /** 右上「编辑」→ 设置页。 */
    var onEdit: (() -> Unit)? = null

    /** 长按应用加入停靠栏后，通知桌面刷新停靠栏。 */
    var onDockChanged: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false

        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.text = "全部应用"
        title.includeFontPadding = false
        title.isSingleLine = true
        head.addView(title, LayoutParams(0, WRAP, 1f))

        // 有隐藏应用时才出现的「已隐藏 N · 恢复」
        restore.text = ""
        restore.includeFontPadding = false
        restore.isSingleLine = true
        restore.gravity = Gravity.CENTER
        restore.isClickable = true
        restore.visibility = GONE
        restore.setOnClickListener { confirmRestore() }
        head.addView(restore, LayoutParams(WRAP, WRAP).apply { rightMargin = ctx.dp(10) })

        edit.text = "编辑"
        edit.includeFontPadding = false
        edit.isSingleLine = true
        edit.gravity = Gravity.CENTER
        edit.isClickable = true
        edit.setOnClickListener { onEdit?.invoke() }
        head.addView(edit, LayoutParams(WRAP, WRAP))
        addView(head, LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(12) })

        grid.orientation = VERTICAL
        scroll.isFillViewport = true
        scroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        addView(scroll, LayoutParams(MATCH, 0, 1f))

        addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != lastW || h != lastH) {
                lastW = w
                lastH = h
                rebuild()
            }
        }
    }

    /** 左内边距 = 停靠栏占掉的那一条（由 MainActivity 按 sidebar 尺寸算好喂进来）。 */
    fun setInset(leftDp: Int) {
        insetLeftDp = leftDp.coerceAtLeast(0)
        // 底部多留一点：#dots 圆点浮在卡片底边中间，别压住最后一行图标
        setPadding(
            context.dp(24 + insetLeftDp), context.dp(22),
            context.dp(24), context.dp(44)
        )
        lastW = 0
        lastH = 0
        lastRebuildKey = ""
        rebuild()
    }

    fun applyTheme(t: Theme) {
        theme = t
        // 图标本身走 AppIconCache；应用清单本身（枚举 + 名称 + 未命中图标的裁切）
        // 放到后台线程去查，主线程只负责换皮和用现有清单排版。
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.setTypeface(t.regular)
        title.setTextColor(t.color("p2TextTitle", Theme.LABEL))

        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        edit.setTypeface(t.regular)
        edit.setTextColor(t.color("accent", 0xFF2F7BF6.toInt()))
        edit.setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        edit.background = GradientDrawable().apply {
            cornerRadius = context.dpf(18f)
            setColor(if (t.night) 0x1FFFFFFF else 0x112F7BF6)
            setStroke(context.dp(1), if (t.night) 0x33FFFFFF else 0x332F7BF6)
        }

        restore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        restore.setTypeface(t.regular)
        restore.setTextColor(t.color("p2TextLabel", Theme.LABEL))
        restore.setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        restore.background = GradientDrawable().apply {
            cornerRadius = context.dpf(16f)
            setColor(if (t.night) 0x1AFFFFFF else 0x0D000000)
        }

        lastRebuildKey = ""
        rebuild()
        requestScan()
    }

    /**
     * 重新查一次应用（每次翻到第 2 页都会调）。
     *
     * ★ 原先只要翻页就无条件 `removeAllViews()` + 重建几十个格子，
     *   是切页时最大的一次对象分配（GC Churn）来源。现在只有**应用清单真的变了**
     *   才重建；没装/没卸应用时一次 View 都不动。
     * ★ v6.0.43 起清单查询本身也移出主线程：这里只投递一次后台扫描。
     */
    fun reload() {
        requestScan()
    }

    /**
     * 后台扫描应用清单（v6.0.43）。
     *
     * 之前 [applyTheme] / [reload] 都在**调用线程**（几乎总是主线程）里直接跑
     * `DockSlots.listApps()`：PackageManager 枚举 + 逐个 `loadLabel` + 对未命中缓存的
     * 自适应图标做裁切（新建 Bitmap），冷启动时这就是一次肉眼可见的主线程停顿。
     * 现在清单在后台算完再回主线程换，界面先按现有（或空）清单排版，回来自动补上。
     *
     * 并发策略：同一时刻只跑一轮；期间再来的请求只记一个「待补扫」，
     * 等这轮回来立刻再跑一次 —— 连续翻页/连点换肤不会堆出几十个任务。
     */
    private fun requestScan() {
        if (scanRunning) {
            scanAgain = true
            return
        }
        scanRunning = true
        // 只捕获 applicationContext：缓存里的 Drawable 绝不能顺着它抓住 Activity
        val app = context.applicationContext
        scanExec.execute {
            val fresh = try {
                DockSlots.listApps(app)
            } catch (t: Throwable) {
                emptyList()
            }
            post { onScanned(fresh) }
        }
    }

    /** 扫描结果回主线程：清单变了才重建（没装/没卸应用时一次 View 都不动）。 */
    private fun onScanned(fresh: List<DockSlots.AppEntry>) {
        scanRunning = false
        val first = !scanDone
        scanDone = true
        if (first || !sameApps(fresh, all)) {
            all = fresh
            lastRebuildKey = ""
            rebuild()
        }
        if (scanAgain) {
            scanAgain = false
            requestScan()
        }
    }

    /** 只比「有哪些应用 + 显示名」，图标对象不参与（换图标由缓存失效处理）。 */
    private fun sameApps(a: List<DockSlots.AppEntry>, b: List<DockSlots.AppEntry>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (DockSlots.key(a[i]) != DockSlots.key(b[i])) return false
            if (a[i].label != b[i].label) return false
        }
        return true
    }

    private fun selected(): List<DockSlots.AppEntry> {
        // 用户在这一页「隐藏」掉的包名，直接不进列表（不影响从别处启动）
        val hidden = AppHide.hidden(context)
        val sel = theme?.snap?.str("appPage.sel", "")?.trim().orEmpty()
        if (sel.isEmpty()) {
            return if (hidden.isEmpty()) all else all.filter { !hidden.contains(it.component.packageName) }
        }
        val byKey = HashMap<String, DockSlots.AppEntry>(all.size * 2 + 8)
        for (a in all) {
            byKey[DockSlots.key(a)] = a
            byKey[a.component.packageName] = a
        }
        val out = ArrayList<DockSlots.AppEntry>()
        for (part in sel.split(',')) {
            val key = part.trim()
            if (key.isEmpty()) continue
            val e = byKey[key] ?: continue
            if (hidden.contains(e.component.packageName)) continue
            if (!out.contains(e)) out.add(e)
        }
        return out
    }

    private fun rebuild() {
        val t = theme ?: return
        // 列宽要按"去掉左右内边距后的可用宽"算，不然右侧会被裁掉一截
        val w = width - paddingLeft - paddingRight
        if (w <= 0) return
        val cols = t.snap.int("appPage.perRow", 6).coerceIn(3, 8)
        val list = selected()
        // 首轮扫描还没回来时不写「· 0 个」：那会让刚开机的一瞬间显示成"一个应用都没有"
        title.text = if (list.isEmpty() && !scanDone) "全部应用" else "全部应用 · ${list.size} 个"

        // 页头的「已隐藏 N · 恢复」：没有隐藏项时收起，不占宽度
        val hiddenCount = AppHide.hidden(context).size
        restore.text = "已隐藏 $hiddenCount · 恢复"
        restore.visibility = if (hiddenCount > 0) VISIBLE else GONE

        // 尺寸/列数/应用清单都没变就别再拆一遍整页 View：
        // removeAllViews + 重建几十个 LinearLayout/ImageView/TextView 会连带触发
        // 一轮完整 measure/layout，是切页卡顿与内存抖动的主要来源。
        val key = buildString {
            append(w).append('|').append(cols).append('|').append(insetLeftDp).append('|')
            append(list.size).append('|').append(if (scanDone) 1 else 0)
            append('|').append(hiddenCount)
            for (e in list) append('|').append(DockSlots.key(e)).append(':').append(e.label)
        }
        if (key == lastRebuildKey) return
        lastRebuildKey = key

        grid.removeAllViews()
        if (list.isEmpty()) {
            grid.addView(TextView(context).apply {
                text = when {
                    !scanDone -> "正在读取应用…"
                    all.isEmpty() -> "没读到已安装的应用\n（点右上「编辑」里再试，或检查系统权限）"
                    else -> "还没勾选应用\n点右上「编辑」去勾选"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(t.color("p2TextLabel", Theme.LABEL))
                typeface = t.regular
                gravity = Gravity.CENTER
                setPadding(0, context.dp(80), 0, 0)
            }, LayoutParams(MATCH, WRAP))
            return
        }

        val cellW = w / cols
        val icon = (cellW * 0.44f).toInt().coerceIn(context.dp(48), context.dp(120))
        val cellH = icon + context.dp(46)

        var i = 0
        while (i < list.size) {
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            for (c in 0 until cols) {
                val idx = i + c
                val cell: View = if (idx < list.size) {
                    appCell(t, list[idx], icon)
                } else {
                    View(context)
                }
                line.addView(cell, LayoutParams(cellW, cellH))
            }
            grid.addView(line, LayoutParams(MATCH, cellH))
            i += cols
        }
    }

    private fun appCell(t: Theme, e: DockSlots.AppEntry, iconSize: Int): View {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            setOnClickListener { launch(e) }
            setOnLongClickListener {
                longPress(e)
                true
            }
        }
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bindAppIcon(iv, e)
        box.addView(iv, LayoutParams(iconSize, iconSize))
        box.addView(TextView(context).apply {
            text = e.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(t.color("p2TextLabel", Theme.LABEL))
            typeface = t.regular
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(10) })
        return box
    }

    /**
     * 图标绑定（对应优化方案里的 `bindAppIcon`）：
     * 1. 先查 [AppIconCache] —— 命中就是一次 O(1) 查表，不碰 PackageManager；
     * 2. 没命中则用 `AppEntry` 里已解析好的图标，并回填缓存；
     * 3. 两者都没有才放缺省图标 + 低优先级后台补载（弱引用 / attach 双检见缓存类）。
     *
     * 后台闭包只捕获 applicationContext 的 PackageManager 和 ComponentName，
     * 不捕获 Activity 或 View，避免缓存线程把界面拽住。
     */
    private fun bindAppIcon(iv: ImageView, e: DockSlots.AppEntry) {
        val key = AppIconCache.key(e.component.packageName)
        val hit = AppIconCache.get(key)
        if (hit != null) {
            iv.setImageDrawable(hit)
            return
        }
        val fallback = e.icon
        if (fallback != null) {
            AppIconCache.put(key, fallback)
            iv.setImageDrawable(fallback)
            return
        }
        iv.setImageDrawable(context.packageManager.defaultActivityIcon)
        val appPm = context.applicationContext.packageManager
        val cn = e.component
        AppIconCache.loadAsync(key, iv) {
            try {
                if (cn.className.isNullOrEmpty()) appPm.getApplicationIcon(cn.packageName)
                else appPm.getActivityIcon(cn)
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun launch(e: DockSlots.AppEntry) {
        if (!DockSlots.launch(context, DockSlots.key(e))) {
            Toast.makeText(context, "打不开「${e.label}」（可能被停用）", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 长按应用：启动 / 放进停靠栏 / 卸载 / 隐藏。
     *
     * ★ 6.0.105：这里原来是 `AlertDialog.setItems`。车机 ROM 对系统列表弹窗的
     *   实现不可靠（项目红线本来也写着「车机列表禁 setItems/setAdapter，一律自绘」），
     *   现在整块手写：深色圆角卡 + 图标 + 四行按钮，点哪行做哪件事。
     */
    private fun longPress(e: DockSlots.AppEntry) {
        val cnt = (theme?.snap?.int("sidebar.phCount", 6) ?: 6).coerceIn(1, 8)
        val act = context as? Activity ?: return
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(22f)
                setColor(0xF21B1A18.toInt())
                setStroke(context.dp(1), 0x33FFFFFF)
            }
        }

        // 头部：图标 + 名称（确认长按的是哪个应用）
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        bindAppIcon(iv, e)
        head.addView(iv, LinearLayout.LayoutParams(context.dp(52), context.dp(52)))
        val nameCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(menuText(e.label, 24f, 0xFFF5F6F8.toInt(), true))
        nameCol.addView(
            menuText(if (e.system) "系统应用" else "第三方应用", 16f, 0xFF9AA0A6.toInt())
                .apply { setPadding(0, context.dp(4), 0, 0) }
        )
        head.addView(nameCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(14) })
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(View(act), LinearLayout.LayoutParams(-1, context.dp(12)))

        card.addView(menuRow("启动应用", 0xFFF5F6F8.toInt()) {
            dlg.dismiss()
            launch(e)
        })
        card.addView(menuRow("放进停靠栏…", 0xFFF5F6F8.toInt()) {
            dlg.dismiss()
            pickSlot(e, cnt)
        })
        card.addView(menuRow("卸载这个应用", 0xFFFF8A80.toInt()) {
            dlg.dismiss()
            uninstall(e)
        })
        card.addView(menuRow("从这一页隐藏", 0xFFF5F6F8.toInt()) {
            dlg.dismiss()
            hide(e)
        })
        card.addView(menuRow("取消", 0xFF9AA0A6.toInt()) { dlg.dismiss() })

        dlg.setContentView(card)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            val lp = w.attributes
            lp.dimAmount = 0.6f
            w.attributes = lp
        }
        dlg.setOnShowListener { dlg.window?.setLayout(context.dp(560), -2) }
        dlg.show()
    }

    private fun menuText(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

    /** 菜单里的一行按钮：整行可点，按下有一层浅色底。 */
    private fun menuRow(label: String, color: Int, onClick: () -> Unit): TextView =
        menuText(label, 22f, color, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(14f)
                setColor(0x14FFFFFF)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }
        }

    /** 卸载：拉起系统卸载界面。系统应用 / 未装好会失败，如实提示。 */
    private fun uninstall(e: DockSlots.AppEntry) {
        val pkg = e.component.packageName
        if (e.system) {
            Toast.makeText(context, "「${e.label}」是系统应用，请用系统设置里的停用", Toast.LENGTH_LONG).show()
            return
        }
        val ok = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) Toast.makeText(context, "打不开卸载界面：权限不足或被车机限制", Toast.LENGTH_LONG).show()
    }

    /** 隐藏：只从这一页摘掉，别处照常能启动。 */
    private fun hide(e: DockSlots.AppEntry) {
        AppHide.hide(context, e.component.packageName)
        lastRebuildKey = ""
        rebuild()
        Toast.makeText(context, "已隐藏「${e.label}」· 点页头「已隐藏 N」可恢复", Toast.LENGTH_LONG).show()
    }

    /** 「已隐藏 N · 恢复」：二次确认后一次性全部恢复。 */
    private fun confirmRestore() {
        val act = context as? Activity ?: return
        val n = AppHide.hidden(context).size
        if (n <= 0) return
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(16))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(22f)
                setColor(0xF21B1A18.toInt())
                setStroke(context.dp(1), 0x33FFFFFF)
            }
        }
        card.addView(menuText("恢复隐藏的应用", 24f, 0xFFF5F6F8.toInt(), true))
        card.addView(
            menuText("这一页现在藏了 $n 个应用，恢复后它们会重新出现在列表里。", 18f, 0xFF9AA0A6.toInt())
                .apply {
                    isSingleLine = false
                    setPadding(0, context.dp(10), 0, 0)
                }
        )
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ok = menuText("全部恢复", 21f, 0xFFE0B44C.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(14f)
                setColor(0x2EFFC857)
            }
            isClickable = true
            setOnClickListener {
                AppHide.clear(context)
                lastRebuildKey = ""
                rebuild()
                dlg.dismiss()
                Toast.makeText(context, "已恢复全部隐藏", Toast.LENGTH_SHORT).show()
            }
        }
        val cancel = menuText("取消", 21f, 0xFF9AA0A6.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(14f)
                setColor(0x14FFFFFF)
            }
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ok, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(12) })
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(18) })

        dlg.setContentView(card)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            val lp = w.attributes
            lp.dimAmount = 0.6f
            w.attributes = lp
        }
        dlg.setOnShowListener { dlg.window?.setLayout(context.dp(560), -2) }
        dlg.show()
    }

    /** 选停靠栏槽位（同样自绘：车机上系统列表弹窗不可靠）。 */
    private fun pickSlot(e: DockSlots.AppEntry, cnt: Int) {
        val act = context as? Activity ?: return
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act).apply {
            orientation = VERTICAL
            setPadding(context.dp(20), context.dp(18), context.dp(20), context.dp(16))
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(22f)
                setColor(0xF21B1A18.toInt())
                setStroke(context.dp(1), 0x33FFFFFF)
            }
        }
        card.addView(menuText("把「${e.label}」放到", 23f, 0xFFF5F6F8.toInt(), true))
        for (i in 0 until cnt) {
            card.addView(menuRow("第 ${i + 1} 格 · ${describe(i + 1)}", 0xFFF5F6F8.toInt()) {
                dlg.dismiss()
                DockSlots.put(context, i, DockSlots.key(e))
                Toast.makeText(context, "已放到第 ${i + 1} 格", Toast.LENGTH_SHORT).show()
                onDockChanged?.invoke()
            })
        }
        card.addView(menuRow("取消", 0xFF9AA0A6.toInt()) { dlg.dismiss() })
        dlg.setContentView(card)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            val lp = w.attributes
            lp.dimAmount = 0.6f
            w.attributes = lp
        }
        dlg.setOnShowListener { dlg.window?.setLayout(context.dp(560), -2) }
        dlg.show()
    }

    /** 那一格现在是什么（给上面的选择框做提示：替换前能看见）。 */
    private fun describe(slot: Int): String {
        val v = DockSlots.raw(context, slot) ?: ""
        return when {
            v.isEmpty() -> "空"
            v == DockSlots.HOME -> "主页"
            v == DockSlots.SETTINGS -> "设置"
            else -> DockSlots.pkgOf(v)
        }
    }

    private companion object {
        const val MATCH = -1
        const val WRAP = -2
    }
}
