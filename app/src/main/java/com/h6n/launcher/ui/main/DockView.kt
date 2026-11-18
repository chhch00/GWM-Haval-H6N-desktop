package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.h6n.launcher.R
import com.h6n.launcher.core.AppIconCache
import com.h6n.launcher.core.NightScreen
import com.h6n.launcher.ui.common.GlassBg
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 左侧竖排停靠栏（`ui_config.sidebar`）：最上面是时钟，下面是**槽位**（slot）。
 *
 * ## 槽位是旧版那套玩法，不是"写死的应用列表"
 * 旧版 `SidebarHomeService` + `DockSlots` + `DockPicker` 的结构：
 * - 停靠栏按 `sidebar.phCount`（默认 5，本车配置 6）切成 N 个格子；
 * - 第 1 格默认「主页」、最后一格默认「设置」，中间的空格子等用户自己放应用；
 * - **点**：空格子 → 弹选择器；主页 → 回桌面；设置 → 打开桌面设置；应用 → 启动；
 * - **长按**：任何格子都弹选择器（换应用 / 设为主页 / 设为设置 / 清空）；
 * - 绑定关系存在 `SharedPreferences("dock_slots")` 的 `slot_N`，与旧版同一份数据，
 *   从旧桌面升上来时自定义槽位不会丢（见 [DockSlots]）。
 *
 * 没设过的格子按 `rail` 配置回退（本车就是 主页 / CarPlay / CarLife / 高德 / QQ音乐 / 设置），
 * 所以刚装上和旧版长得一样，用户改过之后以用户为准。
 *
 * ## 为什么时钟在停靠栏而不是状态栏
 * 原版 `activity_main.xml` 里状态栏的 `tvTime` 是 `GONE`，而 `sidebar.showClock=true`、
 * `clockTop=35` —— 时间一直显示在左侧停靠栏顶部。重写版照这个来：
 * 停靠栏显示时钟时，状态栏的时间自动隐藏，不会出现两个时间。
 */
@SuppressLint("ViewConstructor")
class DockView(
    ctx: Context,
    private val onSettings: () -> Unit,
    private val onWallpaper: () -> Unit = {},
    /**
     * 只在「覆盖原厂导航栏」的悬浮层里传，桌面上传 null。
     * 传了就在时钟上方加一个「原车桌面」按钮 —— 因为覆盖层把原车左侧栏盖住了，
     * 用户得有个地方回到原厂桌面。
     */
    private val onFactory: (() -> Unit)? = null,
    /**
     * 6.0.31：只有「覆盖原厂导航栏」的悬浮层才传。那份 DockView 用的是
     * applicationContext.createConfigurationContext，没有 Activity token，
     * 选择器必须改用宿主的 WindowManager 叠自绘面板，否则 AlertDialog.show()
     * 抛 BadTokenException（友盟车机实测）。桌面上传 null，照旧走 Dialog。
     */
    private val pickerHost: DockPicker.PickerHost? = null
) : LinearLayout(ctx) {

    private val clock = TextView(ctx)
    private val factoryBtn = TextView(ctx).apply { visibility = GONE }
    private val slotHost = LinearLayout(ctx)
    private val slotViews = ArrayList<SlotView>(8)

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    private var theme: Theme? = null
    private var showClock = true
    private var slotCount = 6
    /** sidebar.clockText 配置值（sp）；fitClock 会按停靠栏实际宽度往下压。 */
    private var clockSp = 34

    /**
     * 背板明度（0 黑 ~ 1 白），null = 跟随主题。
     * 只有覆盖原厂竖栏的悬浮层会写它 —— 那时背板颜色是"壁纸取色"来的，
     * 文字该白该黑必须看背板，而不是看昼夜。
     */
    private var backingLum: Float? = null

    /** 一个格子：圆角底 + 图标（+ 空格子时的加号）。 */
    private inner class SlotView(context: Context) : FrameLayout(context) {
        val icon = ImageView(context)
        val plus = TextView(context)
        var index = 0

        init {
            clipChildren = false
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.visibility = GONE
            addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))

            plus.visibility = GONE
            plus.gravity = Gravity.CENTER
            plus.includeFontPadding = false
            plus.isSingleLine = true
            // ★ 之前只摆了 plus 的位置和字号，忘了写文案 —— 空格子/未安装的格子
            //   永远是空的，用户根本看不到"这里可以加应用"的入口。
            plus.text = "＋"
            // ★ 必须显式用 FrameLayout.LayoutParams：本类是 LinearLayout 的内部类，
            //   `LayoutParams` 会解析成 LinearLayout.LayoutParams，FrameLayout 不认它的 gravity，
            //   加号会跑到格子左上角去。
            addView(plus, FrameLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER })
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false

        // 覆盖层专用：停靠栏顶部一个「原车桌面」按钮（桌面上不显示，传 null 就没有）
        if (onFactory != null) {
            factoryBtn.apply {
                visibility = VISIBLE
                gravity = Gravity.CENTER
                maxLines = 1
                setHorizontallyScrolling(false)
                ellipsize = null
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(4), ctx.dp(8))
                text = "原车桌面"
                isClickable = true
                setOnClickListener { onFactory.invoke() }
            }
            addView(factoryBtn, LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) })
        }

        clock.gravity = Gravity.CENTER
        // ★ 不能用 isSingleLine/setSingleLine(true)：它在 TextView 内部会顺手打开
        //   "横向滚动"，配合自动缩字号时测量宽度差 1~2px，"07:54" 的末位 4 就被
        //   右侧边界直接切掉（实机截图 VC384 的 bug）。这里改成 maxLines=1 +
        //   显式关闭横向滚动，字号由 fitClock() 按实际宽度算，留足余量。
        clock.maxLines = 1
        clock.setHorizontallyScrolling(false)
        clock.ellipsize = null
        clock.includeFontPadding = true
        clock.setPadding(ctx.dp(2), 0, ctx.dp(2), 0)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        // 长按时钟 = 换壁纸（桌面上的快捷入口，和「设置 → 外观 → 更换壁纸」同一面板）
        clock.setOnLongClickListener { onWallpaper(); true }
        // 日期按要求整体取消：状态栏已有日期，dock 只留一行时间
        addView(clock, LayoutParams(MATCH, WRAP))

        slotHost.orientation = VERTICAL
        slotHost.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        addView(slotHost, LayoutParams(MATCH, 0, 1f).apply { topMargin = ctx.dp(14) })
    }

    fun applyTheme(t: Theme) {
        theme = t
        showClock = t.flag("sidebar.showClock", true)
        clock.visibility = if (showClock) VISIBLE else GONE
        clockSp = t.size("clockText", 34).coerceIn(12, 80)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSp.toFloat())
        // 不再用 setAutoSizeTextTypeUniformWithConfiguration：它在 API 27 上按
        // "刚好等于文字宽"取整，末位字形会被裁掉；改成自己算，见 fitClock()。
        clock.typeface = t.bold
        applyInk(t)
        rebuildSlots(t)
        fitClock()
        post { fitClock() }
    }

    /**
     * 覆盖原厂竖栏时，背板是 [NavOverlayService] 自己铺的（不是桌面壁纸），
     * 所以文字/描边不能只看昼夜，得看**背板实际明暗**：背板亮就换深字，
     * 背板暗才用白字。桌面上不调用（`backingLum == null` → 完全跟随主题，观感不变）。
     *
     * @param lum 0 黑 ~ 1 白；null = 恢复"跟随主题"
     */
    fun setBackingLum(lum: Float?) {
        if (backingLum == lum) return
        backingLum = lum
        theme?.let { t ->
            applyInk(t)
            refreshSlotBgs(t)
        }
    }

    /** 文字色：有背板就按背板明暗定，没有就照主题。 */
    private fun inkOn(t: Theme): Int {
        val l = backingLum ?: return t.color("textMain", Theme.INK)
        return if (l > LUM_SPLIT) Theme.INK else Color.WHITE
    }

    /** 高光/描边用哪一套：true = 暗底（白系），false = 亮底（黑系）。 */
    private fun darkPlate(t: Theme): Boolean =
        backingLum?.let { it <= LUM_SPLIT } ?: t.night

    /**
     * 文字与玻璃描边一起按背板明暗落色。
     *
     * 拆出来是因为背板色是**后到**的（`applyTheme` 先按主题画一版，`applyBacking`
     * 取完壁纸色才把明度回传），所以这里必须能被重复调用。
     */
    private fun applyInk(t: Theme) {
        clock.setTextColor(inkOn(t))

        val dark = darkPlate(t)
        if (onFactory != null) {
            factoryBtn.typeface = t.medium
            factoryBtn.setTextColor(inkOn(t))
            factoryBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(16).toFloat()
                setColor(if (dark) 0x33FFFFFF else 0x1F000000)
                setStroke(context.dp(1), if (dark) 0x4DFFFFFF else 0x26000000)
            }
        }

        if (t.flag("sidebar.dockCard", true)) {
            // sidebar.dockRadius = 0 表示"跟随卡片圆角"，与原版一致
            val dockR = t.snap.int("sidebar.dockRadius", 0)
            background = GlassBg(
                radiusPx = context.dpf(if (dockR > 0) dockR.toFloat() else t.size("cardRadius", 48).toFloat()),
                blurPx = context.dpf(10f),
                spreadPx = context.dpf(3f),
                shadowColor = if (dark) 0x55000000 else 0x26000000,
                fillTop = Color.TRANSPARENT,
                fillBottom = Color.TRANSPARENT,
                strokePx = context.dpf(1f),
                strokeColor = if (dark) 0x1FFFFFFF else 0x14000000,
                exponent = 5f
            )
            // v6.0.26：静态阴影可在硬件层直接绘制，不再强制 LAYER_TYPE_SOFTWARE
            setPadding(context.dp(8), context.dp(18), context.dp(8), context.dp(18))
        } else {
            background = null
        }
    }

    /** 只重刷格子底色（背板明暗变了，实底/虚线框那层也要跟着换黑系或白系）。 */
    private fun refreshSlotBgs(t: Theme) {
        for (sv in slotViews) {
            val side = if (sv.width > 0) sv.width else context.dp(72)
            val corner = cornerRadius(t, side)
            sv.background = if (sv.plus.visibility == VISIBLE) addBg(t, corner) else slotBg(t, corner)
        }
    }

    /** 停靠栏宽高/内边距确定后再算一次，保证旋转或改配置后仍然不裁字。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitClock()
    }

    /** 覆盖层被摘下 / Activity 结束时撤掉排队的布局回调，别把 Theme/Context 挂在主线程队列里。 */
    override fun onDetachedFromWindow() {
        pendingLayout?.let { slotHost.removeCallbacks(it) }
        pendingLayout = null
        super.onDetachedFromWindow()
    }

    /**
     * 按「可用宽度」反算字号：用 clock 自己的 Paint（含主题加粗字体）量 100px 下的
     * "00:00" 宽度，再线性缩放到可用宽度，最后留 2% 余量。
     * 这样任何 density / 任何 dockW 配置下，5 个字符都完整落在框内。
     */
    private fun fitClock() {
        val avail = width - paddingLeft - paddingRight - clock.paddingLeft - clock.paddingRight -
            context.dp(4)
        if (avail <= 0) return
        val maxPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, clockSp.toFloat(), resources.displayMetrics
        )
        val probe = Paint(clock.paint)
        probe.textSize = 100f
        val w100 = probe.measureText("00:00")
        if (w100 <= 0f) return
        val fitPx = avail * 100f / w100
        val use = Math.min(maxPx, fitPx * 0.97f)
        if (use > 0f) clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, use)
    }

    fun bindClock(now: Date) {
        clock.text = timeFmt.format(now)
    }

    // ---------------- 槽位 ----------------

    /** 防止连续 applyTheme 把好几个 layoutSlots 排进队列，重复触发测量。 */
    private var pendingLayout: Runnable? = null

    private fun rebuildSlots(t: Theme) {
        val count = t.snap.int("sidebar.phCount", 5).coerceIn(1, 8)
        val gap = Math.max(0, t.snap.int("sidebar.phGap", 16))
        val gapPx = context.dp(gap)

        // ★ 原先每次 applyTheme（含「把应用放进停靠栏」后的 onDockChanged）
        //   都 removeAllViews + new SlotView，整条栏闪一下还白建 6~8 个 FrameLayout。
        //   格子数量没变就完全复用，只由 layoutSlots 重设尺寸并重绑内容。
        while (slotViews.size > count) {
            val last = slotViews.removeAt(slotViews.size - 1)
            slotHost.removeView(last)
        }
        while (slotViews.size < count) {
            val i = slotViews.size
            val sv = SlotView(context)
            sv.index = i
            sv.setOnClickListener { onSlotTap(i) }
            // 长按任意格子都是"换这一格"（旧版 buildHitAreas 里就是 OnLongClickListener → openPicker）
            sv.setOnLongClickListener {
                openPicker(i)
                true
            }
            slotHost.addView(sv, LayoutParams(WRAP, WRAP).apply {
                topMargin = if (i == 0) 0 else gapPx
                gravity = Gravity.CENTER_HORIZONTAL
            })
            slotViews.add(sv)
        }
        slotCount = count

        // 尺寸要等 slotHost 量完高度才能算；先撤掉上一次尚未执行的那一个
        pendingLayout?.let { slotHost.removeCallbacks(it) }
        val job = Runnable {
            pendingLayout = null
            layoutSlots(t, gap)
        }
        pendingLayout = job
        slotHost.post(job)
    }

    private fun layoutSlots(t: Theme, gap: Int) {
        if (slotViews.isEmpty()) return
        val gapPx = context.dp(gap)
        val availH = slotHost.height - gapPx * (slotCount - 1)
        val fit = if (availH > 0) availH / slotCount else context.dp(72)
        // phSize 只当上限用：配置成 86 但纵向只够 79 时，按 79 走，不会挤到时钟上
        val configured = t.snap.int("sidebar.phSize", 0)
        val size = Math.min(
            if (configured > 0) context.dp(configured.coerceIn(32, 120)) else context.dp(96),
            fit
        )
        // 6.0.87：覆盖层窗口只有原车竖栏那 128px 宽，图标不能再按高度撑到 96dp，
        // 否则左右会被窗口裁掉。用实际可用宽度再压一道（桌面 dock 宽度够，不受影响）。
        // ★ 必须把结果写回 size —— 之前写成独立表达式，约束没生效（Kotlin 只警告）。
        val byWidth = slotHost.width - context.dp(6)
        val slotSize = if (byWidth > 0) Math.min(size, byWidth) else size

        val zoom = t.snap.int("sidebar.dockIconZoom", 100).coerceIn(50, 250) / 100f
        val useSize = slotSize.coerceIn(context.dp(36), context.dp(100))
        val dim = t.snap.int("sidebar.dockIconDim", 100).coerceIn(20, 100) / 100f
        val corner = cornerRadius(t, useSize)
        val iconSize = (useSize * 0.86f * zoom).toInt().coerceIn(context.dp(16), useSize)

        for ((i, sv) in slotViews.withIndex()) {
            sv.layoutParams = (sv.layoutParams as LayoutParams).apply {
                width = useSize
                height = useSize
                topMargin = if (i == 0) 0 else gapPx
                gravity = Gravity.CENTER_HORIZONTAL
            }
            sv.background = slotBg(t, corner)
            sv.alpha = dim
            sv.icon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            sv.icon.clipToOutline = true
            sv.icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.24f)
                }
            }
            // 空格子的 "+" 也跟着背板明暗走，否则浅色背板上灰字、深色背板上更看不清
            val plusInk = if (backingLum != null) inkOn(t) else t.color("textLabel", Theme.LABEL)
            sv.plus.setTextColor(withAlpha(plusInk, 0.70f))
            sv.plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, (useSize * 0.32f).toFloat())
            bindSlot(sv, i, t, iconSize)
        }
    }

    private fun bindSlot(sv: SlotView, i: Int, t: Theme, iconSize: Int) {
        val value = resolve(i, t)
        sv.icon.setColorFilter(null)
        when (value) {
            DockSlots.HOME -> {
                sv.icon.setImageResource(R.drawable.ic_app_home)
                sv.icon.visibility = VISIBLE
                sv.plus.visibility = GONE
            }
            DockSlots.SETTINGS -> {
                sv.icon.setImageDrawable(gearTile(t, iconSize))
                sv.icon.visibility = VISIBLE
                sv.plus.visibility = GONE
            }
            DockSlots.NIGHT -> {
                // 读 NightScreen 的内存值，不读主题快照：写配置是异步的，读快照会让
                // 「点一下切换」有一段时间显示旧状态。冷启动 / 配置回流由
                // MainActivity.bindAll 里的 NightScreen.syncFrom 保证一致。
                sv.icon.setImageDrawable(nightTile(t, iconSize, NightScreen.enabled()))
                sv.icon.visibility = VISIBLE
                sv.plus.visibility = GONE
            }
            "" -> {
                // 空格子画个淡淡的 "+"，提示"点这里放应用"
                sv.icon.setImageDrawable(null)
                sv.icon.visibility = GONE
                sv.plus.visibility = VISIBLE
            }
            else -> {
                // ★ 应用没装（旧配置里写着 CarPlay / CarLife 但车机上没有）时，
                //   以前画一个灰色"首字母色块"，用户看到的就是一排没用的 C ，
                //   还以为是写死的、加不了自己的应用。现在直接画 "+"，
                //   点一下/长按都进选择器，这一格立刻能换成任意应用。
                if (!DockSlots.exists(context, value)) {
                    sv.icon.setImageDrawable(null)
                    sv.icon.visibility = GONE
                    sv.plus.visibility = VISIBLE
                } else {
                    sv.icon.setImageDrawable(appIcon(value, t))
                    sv.icon.visibility = VISIBLE
                    sv.plus.visibility = GONE
                }
            }
        }
        // 空格子用虚线框（"可添加"），有内容的格子用实底 —— 用户一眼能看出哪几格能点
        val side = if (sv.width > 0) sv.width else context.dp(72)
        sv.background = if (sv.plus.visibility == VISIBLE) addBg(t, cornerRadius(t, side)) else slotBg(t, cornerRadius(t, side))
    }

    /** 默认布局：用户设过就听用户的；没设过按 rail 配置补，最后一格是设置。 */
    private fun resolve(slot: Int, t: Theme): String {
        DockSlots.raw(context, slot)?.let { return it }
        if (slot == slotCount - 1) return DockSlots.SETTINGS
        val rail = t.snap.list("rail")
        val item = rail?.getOrNull(slot) as? Map<*, *> ?: return ""
        val pkg = item["pkg"]?.toString().orEmpty()
        val icon = item["icon"]?.toString().orEmpty()
        val label = item["label"]?.toString().orEmpty()
        if (pkg.isNotEmpty()) return pkg
        // rail 里 pkg 为空的只有"主页"
        return if (icon == "ic_app_home" || label == "主页") DockSlots.HOME else ""
    }

    private fun onSlotTap(i: Int) {
        val t = theme ?: return
        when (val value = resolve(i, t)) {
            DockSlots.HOME -> goHome()
            DockSlots.SETTINGS -> onSettings()
            DockSlots.NIGHT -> {
                val on = NightScreen.toggle(context, t.snap)
                Toast.makeText(
                    context.applicationContext,
                    if (on) "夜间自动息屏已开启" else "夜间自动息屏已关闭",
                    Toast.LENGTH_SHORT
                ).show()
                // 只重画这一格，别重建整条停靠栏（会闪）
                runCatching {
                    val sv = slotViews.getOrNull(i) ?: return@runCatching
                    val iconSize = (sv.icon.layoutParams?.width ?: context.dp(64))
                    bindSlot(sv, i, t, iconSize)
                }
            }
            "" -> openPicker(i)
            // 应用没装：不弹"打不开"，直接进选择器让用户换掉这一格
            else -> if (!DockSlots.exists(context, value) || !DockSlots.launch(context, value)) {
                openPicker(i)
            }
        }
    }

    private fun openPicker(i: Int) {
        DockPicker.show(context, i, slotCount, pickerHost) { value ->
            DockSlots.put(context, i, value)
            theme?.let { t ->
                // 只重画这一格，别把整条停靠栏重建（会闪）
                slotViews.getOrNull(i)?.let { sv ->
                    val iconSize = (sv.icon.layoutParams?.width ?: context.dp(64))
                    bindSlot(sv, i, t, iconSize)
                }
            }
        }
    }

    /** 回桌面：发 HOME 意图（重写版本体就是桌面，系统会把栈顶的其他应用收掉）。 */
    private fun goHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (t: Throwable) {
            // 没有可用的 HOME：忽略
        }
    }

    // ---------------- 图标 ----------------

    private fun appIcon(value: String, t: Theme): Drawable? {
        val pkg = DockSlots.pkgOf(value)
        val key = AppIconCache.key(pkg)
        // 应用墙与停靠栏共用一份缓存：换格 / 换肤 / applyTheme 反复 bindSlot 时
        // 不再重复 getActivityIcon（每次都会解析一张 Bitmap）。
        // PM 取 applicationContext 的，保证缓存里的 Drawable 不抓 Activity。
        val pm = context.applicationContext.packageManager
        val cn = DockSlots.component(value)
        val icon = AppIconCache.load(key) {
            try {
                if (cn != null) pm.getActivityIcon(cn) else pm.getApplicationIcon(pkg)
            } catch (t2: Throwable) {
                null
            }
        }
        // 应用卸了：画一个"缺省"方块，比空白好认
        return icon ?: letterTile(t, value)
    }

    /** 「设置」格子：灰蓝圆角底 + 白色齿轮，做成应用图标的样子。 */
    private fun gearTile(t: Theme, iconSize: Int): Drawable {
        val size = if (iconSize > 0) iconSize else context.dp(64)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = size * 0.24f
            setColor(if (t.night) 0xFF5B6B80.toInt() else 0xFF5E6C7E.toInt())
        }
        val fg = ContextCompat.getDrawable(context, R.drawable.ic_settings)?.mutate()
        fg?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        val layer = LayerDrawable(arrayOf<Drawable>(bg, fg ?: bg))
        val inset = (size * 0.22f).toInt()
        layer.setLayerInset(1, inset, inset, inset, inset)
        return layer
    }

    /**
     * 「夜间自动息屏」格子：开 = 金色底 + 白色弯月，关 = 灰底 + 暗月。
     *
     * 旧版这个开关是悬浮按钮上的「月 / 暗」两个字；改成图形是为了跟停靠栏其余格子
     * （都是图标）统一，同时还能靠底色一眼看出开没开。
     */
    private fun nightTile(t: Theme, iconSize: Int, on: Boolean): Drawable {
        val size = if (iconSize > 0) iconSize else context.dp(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val plateColor = when {
            on -> 0xFFC9A227.toInt()
            t.night -> 0xFF39424F.toInt()
            else -> 0xFF9AA6B6.toInt()
        }
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plateColor }
        val r = size * 0.24f
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, plate)

        // 弯月 = 一个实心圆，再用同色（底板色）圆压掉右上一块
        val cx = size * 0.50f
        val cy = size * 0.47f
        val moonR = size * 0.21f
        val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (on) Color.WHITE else 0xCCFFFFFF.toInt()
        }
        c.drawCircle(cx - moonR * 0.18f, cy, moonR, moon)
        c.drawCircle(cx + moonR * 0.62f, cy - moonR * 0.38f, moonR * 0.92f, plate)
        return BitmapDrawable(resources, bmp)
    }

    /** 应用没装 / 取图标失败：圆角色块 + 包名首字母。 */
    private fun letterTile(t: Theme, value: String): Drawable {
        val size = Math.max(context.dp(48), context.dp(96))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (t.night) 0xFF39424F.toInt() else 0xFF9AA6B6.toInt()
        }
        val r = size * 0.24f
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, bg)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.46f
            textAlign = Paint.Align.CENTER
            typeface = t.bold
        }
        val ch = DockSlots.pkgOf(value).take(1).uppercase(Locale.CHINA)
        val baseY = size / 2f - (txt.ascent() + txt.descent()) / 2f
        c.drawText(ch, size / 2f, baseY, txt)
        return BitmapDrawable(resources, bmp)
    }

    /** 空格子 / 待添加格的底：透明填充 + 虚线圆角框，和实底的应用格区分开。 */
    private fun addBg(t: Theme, corner: Int): GradientDrawable {
        val c = withAlpha(t.color("textLabel", Theme.LABEL), if (darkPlate(t)) 0.36f else 0.28f)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner.toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(context.dp(2), c, context.dp(6).toFloat(), context.dp(5).toFloat())
        }
    }

    private fun slotBg(t: Theme, corner: Int): GradientDrawable {
        val dark = darkPlate(t)
        val fill = parseColor(
            t.snap.str(if (dark) "sidebar.phColorNight" else "sidebar.phColor", ""),
            if (dark) 0x40FFFFFF else 0x1F000000
        )
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner.toFloat()
            setColor(fill)
            val strokeW = t.snap.int("sidebar.phStroke", 1)
            if (strokeW >= 0) {
                val sc = parseColor(
                    t.snap.str("sidebar.phStrokeColor", ""),
                    if (dark) 0x33FFFFFF else 0x26000000
                )
                setStroke(context.dp(strokeW.coerceAtMost(6)), sc)
            }
        }
    }

    /** phCornerStyle: round(50%) / squircle(34%) / continuous(30%)；phRadius>0 直接给绝对圆角。 */
    private fun cornerRadius(t: Theme, size: Int): Int {
        val abs = t.snap.int("sidebar.phRadius", 0)
        if (abs > 0) return context.dp(abs).coerceAtMost(size / 2)
        val def = when (t.snap.str("sidebar.phCornerStyle", "continuous").lowercase(Locale.CHINA)) {
            "round" -> 50
            "squircle" -> 34
            else -> 30
        }
        val configured = t.snap.int("sidebar.phCornerPct", 0)
        val use = if (configured in 1..50) configured else def
        return (size * use / 100f).toInt()
    }

    private fun withAlpha(c: Int, ratio: Float): Int {
        val a = ((c ushr 24) * ratio).toInt().coerceIn(0, 255)
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    private fun parseColor(raw: String, def: Int): Int = try {
        if (raw.isNullOrBlank()) def else Color.parseColor(raw)
    } catch (t: Throwable) {
        def
    }

    companion object {
        private const val MATCH = -1
        private const val WRAP = -2
        private const val VISIBLE = 0
        private const val GONE = 8

        /** 背板明暗分界：亮过它用深字（和 [Theme.lightText] 同一档）。 */
        private const val LUM_SPLIT = 0.55f

        /** 停靠栏在屏幕上的位置与尺寸，取自 sidebar 配置的默认值（x16 y72 w104 h632）。 */
        fun layoutParams(ctx: Context, t: Theme): FrameLayout.LayoutParams {
            val w = t.snap.int("sidebar.dockW", 104).coerceIn(72, 160)
            val x = t.snap.int("sidebar.dockX", 16)
            val y = t.snap.int("sidebar.dockY", 72)
            return FrameLayout.LayoutParams(ctx.dp(w), MATCH).apply {
                leftMargin = ctx.dp(x)
                topMargin = ctx.dp(y)
                bottomMargin = ctx.dp(16)
            }
        }

        /**
         * 覆盖层专用布局（6.0.87）：窗口宽度已对齐原车竖栏的 [windowWpx] 物理像素，
         * DockView 必须整体落进这段宽度里 —— 旧的 [layoutParams] 是给桌面用的
         * （dockX 16dp + dockW 104dp = 120dp ≈ 180px），照搬到 128px 的覆盖层里
         * 图标右半边会被裁掉。这里左右只留 2dp，其余全部给内容。
         */
        fun overlayLayoutParams(ctx: Context, t: Theme, windowWpx: Int): FrameLayout.LayoutParams {
            val margin = ctx.dp(2)
            val w = (windowWpx - margin * 2).coerceAtLeast(1)
            val y = t.snap.int("sidebar.dockY", 72)
            return FrameLayout.LayoutParams(w, MATCH).apply {
                leftMargin = margin
                topMargin = ctx.dp(y)
                bottomMargin = ctx.dp(16)
            }
        }

        fun widthPx(ctx: Context, t: Theme): Int =
            ctx.dp(t.snap.int("sidebar.dockW", 104).coerceIn(72, 160))
    }
}
