package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AmapNavi
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.EngineHours
import com.h6n.launcher.core.OilLife
import com.h6n.launcher.core.LocalPlayer
import com.h6n.launcher.core.MediaWatcher
import com.h6n.launcher.core.NowPlaying
import com.h6n.launcher.core.Storage
import com.h6n.launcher.core.WallpaperStore
import com.h6n.launcher.ui.common.GlassBg
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf

/**
 * 桌面第 1 页：车速｜高德导航信息｜机油寿命（左列）＋胎压车模（右列整列高）。
 *
 * ```
 * ┌──────────────────────┬────────────────────────┐
 * │ 车速  88 km/h         │                        │
 * ├──────────────────────┤  胎压 + 车模（整列高）   │
 * │ 导航 ● 12 ↰ XX大道    │  水温/变速油在车头机盖上 │
 * ├──────────────────────┤                        │
 * │ 200h  剩余时间        │                        │
 * └──────────────────────┴────────────────────────┘
 * 列1 : 列2 = p2Col1(500) : p2Col2(360)，列2 不小于 420dp（要放得下两侧胎压数字）
 * 左列：机油 30%、导航 39%、其余给车速（车速至少 180dp）
 * ```
 *
 * 应用网格已经搬到**桌面第 2 页**（[AppPageView]），这里不再放应用；
 * 「回家 / 公司」、驾驶模式卡、温度卡、油量卡、保养卡、音乐横条都已删除。
 */
@SuppressLint("ViewConstructor")
class SixGrid(ctx: Context, var insetLeftDp: Int = 0) : LinearLayout(ctx) {

    private var theme: Theme? = null
    private var signal = CarSignal.EMPTY

    /** 上一次喂给车速卡的值（v6.0.44：车速没变就不动那张卡）。 */
    private var lastSpeedKmh = Int.MIN_VALUE

    private val speedCard = SpeedCard(ctx)
    private val tireCard = TireCard(ctx)
    private val oilCard = OilCard(ctx)
    private val navCard = NavCard(ctx)

    private val col1 = LinearLayout(ctx)
    private val col2 = LinearLayout(ctx)

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false

        col1.orientation = VERTICAL
        col2.orientation = VERTICAL
        for (c in listOf(col1, col2)) {
            c.clipChildren = false
            c.clipToPadding = false
        }

        addView(col1)
        addView(col2)
    }

    /** 地图卡宽高（dp）：由 MainActivity 按 sizes 算好后喂进来，卡片尺寸由此固定。 */
    private var mapWidthDp = 0
    private var cardHDp = 0
    /** 上一次布局的几何指纹：没变就不拆装卡片树，省一轮 measure/layout。 */
    private var lastGeomKey = ""

    fun setGeometry(mapW: Int, cardH: Int) {
        if (mapW == mapWidthDp && cardH == cardHDp) return
        mapWidthDp = mapW
        cardHDp = cardH
        theme?.let { rebuild(it) }
    }

    /** 按配置重建各块。 */
    private fun rebuild(t: Theme) {
        if (mapWidthDp <= 0 || cardHDp <= 0) return
        val gap = t.size("p2Gap", 16)
        val pad = t.size("p2Pad", 20)
        val innerW = (mapWidthDp - 2 * pad - insetLeftDp).coerceAtLeast(120)
        val innerH = (cardHDp - 2 * pad).coerceAtLeast(200)
        val w1 = t.size("p2Col1", 500)
        val w2 = t.size("p2Col2", 360)
        val total = w1 + w2 + gap

        // ★ v6.0.46：矮屏 / 窄屏分支（800×480 仪表屏、小尺寸车机）。
        //   原先三个硬下限（列2 ≥ 420dp、列1 ≥ 240dp、车速 ≥ 180dp）只在 1600×720dp 这档成立；
        //   到 800×480（innerW≈612 / innerH≈352dp）上三块加起来必然溢出，车速卡会被算成
        //   负高度。判据直接用可用几何本身，不猜设备型号：
        //     innerH < 560 → 左列三块装不下大纲领比例
        //     innerW < 700 → 两列按 420+240 分不开
        val compact = innerH < 560 || innerW < 700

        val minCol2 = if (compact) innerW * 42 / 100 else 420
        val minCol1 = if (compact) 200 else 240
        var col2W = if (total > 0) {
            Math.max(minCol2, Math.round(innerW.toFloat() * w2 / total))
        } else {
            innerW * 2 / 5
        }
        // 列1 保不住 minCol1 就压缩列2：车模按宽度等比缩小（CarModelView 里 byWidth 那道），
        // 不会裁切；反过来把列1 挤窄，车速数字就会贴到卡片边缘。
        val maxCol2 = (innerW - minCol1 - gap).coerceAtLeast(innerW / 2)
        if (col2W > maxCol2) col2W = maxCol2
        val col1W = (innerW - col2W - gap).coerceAtLeast(minCol1)

        // 左列三块：车速 / 高德导航 / 机油。导航卡要装下 1.5 倍的「转向图标 + 距离 + 车道
        // + 红绿灯 + 进度条」（内容高约 250dp），所以先按比例给它，再逐级退让保住车速。
        val avail = (innerH - 2 * gap).coerceAtLeast(180)
        val oilPct = if (compact) 24 else 30
        val naviPct = if (compact) 34 else 39
        val minOil = if (compact) 96 else 150
        val minNavi = if (compact) 120 else 220
        val minSpeed = if (compact) 100 else 180
        var oilH = (avail * oilPct / 100).coerceAtLeast(minOil)
        var naviH = (avail * naviPct / 100).coerceAtLeast(minNavi)
        var speedH = avail - oilH - naviH
        if (speedH < minSpeed) {
            naviH = (avail - oilH - minSpeed).coerceAtLeast(minNavi)
            speedH = avail - oilH - naviH
        }
        if (speedH < minSpeed) {
            oilH = (avail - naviH - minSpeed).coerceAtLeast(minOil)
            speedH = avail - oilH - naviH
        }
        if (speedH < 1) speedH = 1

        // applyTheme 与 setGeometry 在启动时常常前后脚各调一次。几何完全一致时
        // 没必要把三张卡从父容器摘下再挂回（removeAllViews + addView 会触发整棵树重测）。
        // 卡片对象是复用的，颜色/字号由各自的 applyTheme() 单独刷新，不依赖这次重建。
        val geomKey = "$insetLeftDp|$mapWidthDp|$cardHDp|$pad|$gap|$col2W|$col1W|$oilH|$naviH|$speedH"
        if (geomKey == lastGeomKey) return
        lastGeomKey = geomKey

        setPadding(context.dp(pad + insetLeftDp), context.dp(pad), context.dp(pad), context.dp(pad))

        col1.layoutParams = LayoutParams(context.dp(col1W), MATCH)
        col2.layoutParams = LayoutParams(context.dp(col2W), MATCH).apply { leftMargin = context.dp(gap) }
        for (c in listOf(col1, col2)) c.removeAllViews()
        // 复用的卡片可能还挂在上一轮的父容器里，先摘下来（否则 addView 抛 "already has a parent"）
        for (v in listOf(speedCard, navCard, oilCard, tireCard)) {
            (v.parent as? ViewGroup)?.removeView(v)
        }

        col1.addView(speedCard, LayoutParams(context.dp(col1W), context.dp(speedH)))
        col1.addView(navCard, LayoutParams(context.dp(col1W), context.dp(naviH)).apply {
            topMargin = context.dp(gap)
        })
        col1.addView(oilCard, LayoutParams(context.dp(col1W), context.dp(oilH)).apply {
            topMargin = context.dp(gap)
        })
        col2.addView(tireCard, LayoutParams(context.dp(col2W), MATCH))
    }

    fun applyTheme(t: Theme) {
        theme = t
        rebuild(t)
        for (card in listOf(speedCard, navCard, tireCard, oilCard)) {
            card.applyTheme(t)
        }
        bind(signal)
    }

    fun bind(s: CarSignal) {
        signal = s
        // ★ v6.0.44：按字段分发，不再「一条信号刷三张卡」。
        //   车速卡只吃车速（内部还会再做一次文本比较）；胎压卡只看胎压/胎温/门窗
        //   （[CarModelView.bind] 自己判差异）；机油卡按「整分钟 + 运转状态」判。
        //   这样车速每秒跳一次时，只有那一个 TextView 会走 measure/layout。
        if (s.speedKmh != lastSpeedKmh) {
            lastSpeedKmh = s.speedKmh
            speedCard.bind(s)
        }
        tireCard.bind(s)
        oilCard.bind(s)
    }

    /**
     * 卡片自己的长按动作。
     *
     * 原版每张卡各有各的长按（胎压卡 `showTireSwapDialog`、保养卡 `EngineHours.onMaintLongClick`），
     * 没有"长按进设置"这种全局行为；设置入口一直是停靠栏的齿轮。
     * 保养计划删除后，机油卡长按 = 改「发动机累计运行时长」。
     */
    fun setCardLongPress(onTire: (() -> Unit)?, onHours: (() -> Unit)? = null) {
        tireCard.setOnLongClickListener { onTire?.invoke(); true }
        oilCard.onEditHours = onHours
    }

    /** 机油卡右侧「剩余储存」块：点一下直接弹清理选项。 */
    fun setStorageClick(cb: () -> Unit) {
        oilCard.onStorageClick = cb
    }

    /** 6.0.91：车模左下角「应用商店」入口点击回调。 */
    fun setStoreClick(cb: () -> Unit) {
        tireCard.onStoreClick = cb
    }

    /** Activity 销毁时释放车模位图（见 [CarModelView.release]）与导航卡动画。 */
    fun release() {
        tireCard.release()
        navCard.release()
    }

    private companion object {
        const val MATCH = -1
    }
}

/**
 * 六格通用卡片：22dp 内边距 + 超椭圆阴影底 + 标题行 + 内容区。
 * 对应原版 `p2Card(int index, String title, String chip, int chipFg, int chipBg)`。
 */
@SuppressLint("ViewConstructor")
abstract class P2Card(ctx: Context, titleText: String) : LinearLayout(ctx) {

    protected val title = TextView(ctx)
    protected val header = LinearLayout(ctx)
    protected val body = LinearLayout(ctx)
    protected var theme: Theme? = null

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(22))
        clipChildren = false
        clipToPadding = false
        // v6.0.26：GlassBg 已改成静态超椭圆阴影，不再依赖 setShadowLayer 的软件层模糊。
        // 卡片回到默认硬件加速层，整棵 View 树不必每帧离屏重绘。

        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = ctx.dp(34)
        title.text = titleText
        title.includeFontPadding = false
        title.isSingleLine = true
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.layoutParams = LayoutParams(0, WRAP, 1f)
        header.addView(title)
        addView(header, LayoutParams(MATCH, WRAP))

        body.orientation = VERTICAL
        body.gravity = Gravity.CENTER
        body.clipChildren = false
        body.clipToPadding = false
        addView(body, LayoutParams(MATCH, 0, 1f))
    }

    open fun applyTheme(t: Theme) {
        theme = t
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.setTypeface(t.regular)
        title.setTextColor(t.color("p2TextTitle", Theme.LABEL))
        background = cardBg(t)
    }

    open fun bind(s: CarSignal) {}

    private fun cardBg(t: Theme): GlassBg {
        // 原版设置页「卡片圆角」写的是 sizes.cardRadius；cellRadius 是早期自造键
        val radius = t.size("cardRadius", t.size("cellRadius", 48)).toFloat()
        val blur = t.size("cardShadow", 12).toFloat()
        val spread = t.size("p2CardInset", 5).toFloat()
        val shadow = if (t.night) 0x66000000 else 0x1F000000
        val pct = t.size("cardFillPct", 0).coerceIn(0, 100)
        val alpha = (pct * 255 / 100)
        // 白天是「白底 + 只有阴影」的玻璃卡（cardFillPct=0 即全透明）；
        // 夜间背景变深，如果还保持全透明，六格会糊成一整片、看不出卡片边界，
        // 所以夜间固定铺 colors.cardBgNight(#1D232C)，描边用 cardStrokeNight。
        val nightFill = t.color("cardBg", 0xFFFDFEFF.toInt())
        var top = when {
            t.night -> nightFill
            pct > 0 -> withAlpha(t.color("mediaTop", 0x8FFFFFFF.toInt()), alpha)
            else -> Color.TRANSPARENT
        }
        var bot = when {
            t.night -> nightFill
            pct > 0 -> withAlpha(t.color("mediaBot", 0x80FFFFFF.toInt()), alpha)
            else -> Color.TRANSPARENT
        }
        // 铺了壁纸后卡片若还是全透明，文字就压在照片上，明暗全看壁纸脸色。
        // 给一层很淡的玻璃底：浅色文字压深底、深色文字压浅底，图仍透得出来。
        if (WallpaperStore.isActive(context) &&
            Color.alpha(top) < 0x10 && Color.alpha(bot) < 0x10
        ) {
            val tint = if (t.lightText()) 0x2E000000 else 0x33FFFFFF
            top = tint
            bot = tint
        }
        val strokeOn = t.snap.bool("sizes.cardStrokeOn", true)
        val strokeW = if (strokeOn) t.size("cardStroke", 2).toFloat() else 0f
        val strokeC = t.color("cardStroke", 0x26000000)
        return GlassBg(
            radiusPx = context.dpf(radius), blurPx = context.dpf(blur), spreadPx = context.dpf(spread),
            shadowColor = shadow, fillTop = top, fillBottom = bot,
            strokePx = context.dpf(strokeW), strokeColor = strokeC, exponent = 5f
        )
    }

    protected fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    protected fun textLabel(size: Float, color: Int, tf: android.graphics.Typeface? = null): TextView =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (tf != null) typeface = tf
        }

    companion object {
        const val MATCH = -1
        const val WRAP = -2
    }
}

/**
 * 车速：大数字 + km/h，**靠左**，和卡片标题左边缘对齐。
 *
 * ```
 * ┌────────────────────────────────┐
 * │ 88 km/h                        │
 * └────────────────────────────────┘
 * ```
 *
 * 6.0.34 曾为给右侧导航信息让位把数字左推；6.0.35 导航信息独立成卡后一度恢复居中；
 * 6.0.36 按用户要求改成**贴内容区最左侧**（`Gravity.START`）—— 卡片标题本来就在左上，
 * 数字和它同一条竖线，比居中更稳，也不会随数字位数（1 位 / 3 位）左右跳动。
 *
 * 6.0.37：数字不再被压扁。上一版留在卡片里的可用高度只有 102dp（卡片 180 − 上下 22×2 −
 * 标题行 34），而 96sp 的数字连行高要 112dp，装不下 —— 字号视觉上被挤小、笔画发扁。
 * 两手一起改：① 上下内边距 22 → 12，先挣回 20dp；② [fitText] 按 body 的真实高度
 * 反算字号（上限仍是 96sp），高度不够时宁可小一号也**不裁不压**。
 */
@SuppressLint("ViewConstructor")
class SpeedCard(ctx: Context) : P2Card(ctx, "车速") {

    /** 设计稿字号：96sp 数字 / 26sp 单位。 */
    private val number = textLabel(NUM_SP, Theme.INK)
    private val unit = textLabel(UNIT_SP, Theme.INK)

    init {
        val row = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        number.text = "--"
        unit.text = "km/h"
        for (v in listOf(number, unit)) {
            v.isSingleLine = true
            // 单行 + 不限横向滚动：TextView 宁可把内容画到边界外，也不会把字形横向挤扁。
            // 这是 6.0.36 里「数字被压缩」的另一半保险。
            v.setHorizontallyScrolling(true)
            v.setTextScaleX(1f)
        }
        unit.layoutParams = LayoutParams(WRAP, WRAP).apply {
            leftMargin = ctx.dp(12)
            bottomMargin = ctx.dp(14)
        }
        row.addView(number)
        row.addView(unit)

        // 靠左：START。行宽撑满，数字就钉在内容区左边缘（= 标题左边缘），
        // 位数从 1 位变到 3 位也只往右长，不会整体左右挪。
        body.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        body.addView(row, LayoutParams(WRAP, WRAP))

        // 上下内边距 22 → 12：96sp 的数字需要约 112dp，而卡片只给得起这么多。
        // 左右仍是 22，保持和其它三张卡的标题同一条竖线。
        setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(12))

        // 6.0.42：点车速数字打开高德地图车机版。
        // 点击装在 body 而不是 number 上：两个 TextView 默认不可点击，触摸本来就会
        // 冒泡到 body，这样热区从「几个数字的字形」扩到整块内容区，车机上不用描准。
        // 按下给一点透明度反馈 —— 纯自绘 UI 没有 ripple，不反馈会以为没点上。
        body.setOnClickListener { openAmap() }
        body.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> body.alpha = 0.55f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> body.alpha = 1f
            }
            false
        }

        // body 的高度由 SixGrid 按比例算好后才确定，所以字号要在布局落定后再反算。
        body.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oR, oB ->
            if (b - t != oB - ot || r - l != oR - ol) fitText()
        }
        post { fitText() }
    }

    /**
     * 按 body 的可用高度反算数字字号，保证「放得下」优先于「按设计稿 96sp」。
     *
     * 不改字号就会出现上一版那种情况：TextView 在固定高度的父容器里被压扁/裁切，
     * 看起来像字形被横向压缩。反算之后再小的屏幕也是等比缩小，不会变形。
     */
    private fun fitText() {
        val avail = (body.height - body.paddingTop - body.paddingBottom).toFloat()
        if (avail <= 0f) return
        val dm = resources.displayMetrics
        // Roboto Bold 的行高约 1.17em，留 3% 余量避免下沿被切
        val maxPx = avail / 1.20f
        val wantPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, NUM_SP, dm)
        val floorPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 56f, dm)
        val px = Math.min(wantPx, maxPx).coerceAtLeast(floorPx)
        if (Math.abs(px - number.textSize) < 0.5f) return
        number.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        // 单位按同一比例走（26/96），数字缩小时比例不变
        unit.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * (UNIT_SP / NUM_SP))
    }

    override fun applyTheme(t: Theme) {
        super.applyTheme(t)
        number.typeface = t.bold
        number.setTextColor(t.color("p2TextMain", Theme.INK))
        unit.typeface = t.bold
        unit.setTextColor(t.color("p2TextMain", Theme.INK))
    }

    /** 上一次上屏的字（v6.0.44）：`TextView.setText` 不做内容比较，同值重设也会触发重排。 */
    private var lastTxt: String? = null

    override fun bind(s: CarSignal) {
        val txt = if (s.speedKmh >= 0) s.speedKmh.toString() else "--"
        if (txt == lastTxt) return
        lastTxt = txt
        number.text = txt
    }

    /**
     * 拉起高德地图车机版。
     *
     * 不写死 Activity 类名 —— 高德车机版各版本入口类换过（`MainMapActivity` 之外还有别的），
     * 所以先按包名问系统「这个包的 LAUNCHER 入口是哪个」，再退 `getLaunchIntentForPackage`。
     * 本工程 `targetSdk = 27`，不受 Android 11 包可见性限制，`queryIntentActivities` 查得到。
     *
     * 起不来只 Toast，不抛异常：桌面是常驻进程，一次启动失败不该留下崩溃。
     */
    private fun openAmap() {
        val pm = context.packageManager
        var ok = false
        try {
            val probe = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(AmapNavi.PKG)
            val ri = pm.queryIntentActivities(probe, 0).firstOrNull()
            val intent = if (ri != null) {
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
            } else {
                pm.getLaunchIntentForPackage(AmapNavi.PKG)
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                ok = true
            }
        } catch (t: Throwable) {
            ok = false
        }
        if (!ok) Toast.makeText(context, "未安装高德地图车机版", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val NUM_SP = 96f
        const val UNIT_SP = 26f
    }
}

/**
 * 高德导航信息卡（车速下、机油上）：红绿灯 + 车道 + 导航转向，整块 1.5 倍放大。
 *
 * 卡片本身不放标题 —— 内容高约 250dp，再加一行标题会把左列三块挤爆。
 * 没有导航数据时 [NavInfoView] 会显示一行「高德导航未开始」的淡字占位。
 */
@SuppressLint("ViewConstructor")
class NavCard(ctx: Context) : P2Card(ctx, "导航信息") {

    private val navi = NavInfoView(ctx).apply {
        // 用户要求「加大 1.5 倍」：灯胶囊 44dp → 66dp，转向图标 52 → 78，字号同比
        setScale(1.5f)
        placeholder = "高德导航未开始 · 打开高德车机版即可显示红绿灯与车道"
    }

    /** 数据源变化时刷一次；只在快照真的变了才会被调（[AmapNavi] 内部去重）。 */
    private val onNavi: () -> Unit = {
        val s = AmapNavi.snapshot()
        navi.setData(
            s.navi && AmapNavi.enabled(context),
            if (AmapNavi.lightOn(context)) s.light else null,
            if (AmapNavi.laneOn(context)) s.lanes else emptyList(),
            if (AmapNavi.enabled(context)) s.info else AmapNavi.Info()
        )
    }

    init {
        // 无标题：内容自己就是全部信息
        header.visibility = GONE
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
        body.gravity = Gravity.CENTER_VERTICAL
        body.addView(navi, LayoutParams(MATCH, MATCH))
    }

    override fun applyTheme(t: Theme) {
        super.applyTheme(t)
        navi.applyTheme(t)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 只有挂到窗口上才开始订阅：卡片被拆掉时不订阅，免得往一棵摘下来的树上刷
        AmapNavi.addListener(onNavi)
        onNavi()
    }

    override fun onDetachedFromWindow() {
        AmapNavi.removeListener(onNavi)
        navi.release()
        super.onDetachedFromWindow()
    }

    fun release() {
        navi.release()
    }
}

/**
 * 胎压：中间车模 + 四角数值（车模上叠车窗/车门，前机盖上写水温/变速箱油）。整列高。
 *
 * 这张卡吃掉右列整列高，车模拿到接近卡片全高（[CarModelView.setSlackDp] 给了 22-6dp
 * 的上下余量，可以画到卡边）。
 */
@SuppressLint("ViewConstructor")
class TireCard(ctx: Context) : P2Card(ctx, "胎压 · kPa") {

    val carView = CarModelView(ctx)

    /** 6.0.91：用户红圈标注的车模左下角位置放醒目「应用商店」入口。 */
    private val storeEntry = StoreEntryView(ctx) { onStoreClick?.invoke() }
    var onStoreClick: (() -> Unit)? = null

    /**
     * 6.0.100：车模**右下角**的空调内 / 外循环按钮，与左下角「应用商店」左右对称。
     * 点击后自己下发 + 回读（[com.h6n.launcher.core.Hvac.toggleAsync]），
     * 状态由 [bind] 每秒推一次，所以这里不需要回调。
     */
    private val acEntry = AcEntryView(ctx) { }

    /** 标题当前是否是告警红：只在翻转时 setTextColor，别每秒重设一次。 */
    private var titleWarn = false

    init {
        // P2Card 上下各 22dp 内边距；留 6dp 呼吸，其余都让车模用掉
        carView.setSlackDp(16)
        // 车模占满 body，商店入口作为浮层压在左下角空白（FrameLayout 自带叠加）。
        // 车模左右各留一条写胎压数字的通道，左下角那块是空的（用户红圈位置）。
        body.addView(FrameLayout(ctx).apply {
            addView(carView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(storeEntry, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = ctx.dp(2)
            })
            // 右下角对称放空调内外循环（6.0.100）：车模右侧通道下方那块同样是空的
            addView(acEntry, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = ctx.dp(2)
            })
        }, LayoutParams(MATCH, MATCH))
    }

    override fun applyTheme(t: Theme) {
        super.applyTheme(t)
        carView.applyTheme(t)
        acEntry.applyTheme(t)
        title.text = "胎压 · " + carView.unitLabel()
        // applyTheme 已把标题刷回 p2TextTitle，这里同步复位，下一帧 bind 会重新判定
        titleWarn = false
    }

    override fun bind(s: CarSignal) {
        carView.bind(s)
        acEntry.setState(s.hvacRecirc)
        // 6.0.67：只要有一轮越界，标题「胎压 · kPa」跟着变红 —— 贴片填红之外再给一处
        // 远景可见的信号，扫一眼卡片就知道要不要看四个轮。
        val warn = carView.tireWarn()
        if (warn != titleWarn) {
            titleWarn = warn
            val t = theme
            title.setTextColor(
                when {
                    !warn -> t?.color("p2TextTitle", Theme.LABEL) ?: Theme.LABEL
                    t != null && t.night -> 0xFFF0483A.toInt()
                    else -> 0xFFD91E10.toInt()
                }
            )
        }
    }

    /** 只在宿主 Activity 真正销毁时调用。 */
    fun release() {
        carView.release()
    }
}

/**
 * 机油寿命（左列下半）：**没有进度条、也不显示百分比**，大字直接报剩余时间。
 *
 * ```
 * 200h                     剩余时间
 *                       按 200h 一换
 * 发动机累计 126h 20m（运转中）
 * ```
 * 算法在 [OilLife]：`剩余h = 固定周期200h - 发动机累计`，保养计划已删除，
 * 百分比只留在内部做配色分级（≥50% 常规 / 25~50% 琥珀 / <25% 红），不再上屏。
 * 发动机累计可以长按本卡或进设置页「维护 → 机油寿命」改「发动机累计运行时长」，
 * 改完这里立刻重算。
 * 长按 → 发动机累计运行时长弹窗。
 */
@SuppressLint("ViewConstructor")
class OilCard(ctx: Context) : P2Card(ctx, "机油寿命") {

    /** 大字：剩余可用小时（"200h" / "9.5h"），取代原来的百分比。 */
    private val hoursText = textLabel(56f, Theme.INK)
    /** 大字右侧说明：正常"剩余时间"，到期"该换了"。 */
    private val capText = textLabel(25f, Theme.INK)
    private val cycleText = textLabel(18f, Theme.LABEL)
    private val engineText = textLabel(19f, Theme.LABEL)

    /** 右侧：剩余储存空间。低于 1.5G 整块变红并显示 ⚠️ 强警报。 */
    private val storageBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }

    /** 中间：正在播放的歌（封面 / 歌名 / 来源）。6.0.74 起常驻空态，6.0.77 起不显示歌手。 */
    private val music = MiniMusic(ctx)

    /** [MediaWatcher] 的回调（主线程）。 */
    private val mediaCb: (NowPlaying?) -> Unit = { np -> syncMusic(np) }
    private val storageValue = textLabel(30f, Theme.LABEL)
    private val storageCap = textLabel(15f, Theme.LABEL)
    private val storageWarn = textLabel(15f, Theme.WARN)
    private var storageAtMs = 0L
    private var storageLow = false

    /**
     * 上一次上屏的输入指纹（v6.0.44）。
     *
     * 机油卡的内容只随「整分钟数 / 是否运转 / 主题」变化，而 [bind] 是每秒被推一次的。
     * 原先每秒都重算一遍 [OilLife]、拼 4 个字符串、做 5 次 `setText` —— 全是白做，
     * 且每次 `setText` 都会走一轮 measure/layout。
     */
    private var lastMinute = Int.MIN_VALUE
    private var lastRunning: Boolean? = null
    private var lastCardTheme: Theme? = null

    /** 卡片实际尺寸（px）：决定上下内边距与引擎行字号。 */
    private var cardH = 0
    private var cardW = 0
    /** 正文缩放指纹：body 真实高度 + 空间警报 + 主题，变了才重算字号。 */
    private var fitRowKey = ""
    /** 当前引擎行字号，避免重复 setTextSize 触发无意义的重排。 */
    private var engineSp = -1f

    /**
     * 上一次看到的通知使用权状态（v6.0.72）。
     *
     * 用户在设置页刚勾完授权回到桌面时，桌面 Activity 只是 onResume，不会重新 attach，
     * 所以得靠每秒一次的 [bind] 发现「授权态变了」并重算一次音乐条。
     * 只在未授权时每 3 秒复查一次，授权后就不再读 setting（避免每秒一次 Binder）。
     */
    private var lastGranted: Boolean? = null
    private var lastGrantMs = 0L

    /**
     * 上次让数据层自检的时间（v6.0.72）。
     *
     * 逐会话回调（`MediaController.Callback`）是主路径，但它只能覆盖「播放器愿意发回调」
     * 的情况；有些车机播放器换了歌既不重设 metadata 也不回调，只能靠重读。
     * 这里借用每秒一次的 [bind] 当节拍器，4 秒一拍，限流在 [MediaWatcher.poll] 内部。
     */
    private var lastMediaTick = 0L

    /** 长按 → 发动机累计运行时长弹窗。 */
    var onEditHours: (() -> Unit)? = null
    /** 点右侧存储块 → 清理选项。 */
    var onStorageClick: (() -> Unit)? = null

    init {
        setOnLongClickListener { onEditHours?.invoke(); true }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 6.0.77：音乐条要比正文行高一点（上下各借 20dp）。标题行「机油寿命」与
            // 引擎行「发动机累计」都只占卡片左半边，右侧是空白，所以允许子 View 越界
            // 绘制到那两行的右侧；这一层不设的话溢出部分会被裁掉。
            // 链上其余容器（P2Card / body / col1 / SixGrid）本来就已放行 clipChildren。
            clipChildren = false
            clipToPadding = false
        }
        hoursText.text = "--"
        hoursText.isSingleLine = true
        row.addView(hoursText, LayoutParams(WRAP, WRAP))

        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        capText.text = "剩余时间"
        capText.isSingleLine = true
        capText.gravity = Gravity.START
        cycleText.text = ""
        cycleText.isSingleLine = true
        cycleText.gravity = Gravity.START
        right.addView(capText, LayoutParams(WRAP, WRAP))
        right.addView(cycleText, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(2) })
        // 说明文字紧跟在数字后面：卡片变宽时不会在中间空出一大块
        row.addView(right, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(14) })

        // ★ v6.0.71：机油块与储存块之间放「正在播放」。
        // ★ v6.0.74：用户要求「没放歌时音乐卡片不要隐藏」—— 本条改为**常驻**：
        //   weight=1 一直吃掉中间空白，储存块一直 WRAP 贴右。空态由 MiniMusic 自己画
        //   （音符块 + 两行提示），不再 GONE，也不再逐秒来回改 LayoutParams 让数字左右晃。
        row.addView(music, LayoutParams(0, MATCH, 1f).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(14)
        })

        // 最右侧吃掉剩余宽度，存储数字贴着卡片右边缘
        storageValue.isSingleLine = true
        storageValue.gravity = Gravity.END
        storageCap.isSingleLine = true
        storageCap.gravity = Gravity.END
        storageWarn.isSingleLine = true
        storageWarn.gravity = Gravity.END
        storageBox.addView(storageValue, LayoutParams(WRAP, WRAP))
        storageBox.addView(storageCap, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(2) })
        storageBox.addView(storageWarn, LayoutParams(WRAP, WRAP).apply { topMargin = ctx.dp(4) })
        storageBox.isClickable = true
        storageBox.setOnClickListener { onStorageClick?.invoke() }
        // 音乐条常驻占 weight=1，储存块固定贴右（6.0.74）
        row.addView(storageBox, LayoutParams(WRAP, WRAP, 0f).apply { leftMargin = ctx.dp(12) })

        // ★ v6.0.50：正文只留一行，且高度锁成 body 的实际高度（MATCH）。
        //   6.0.49 是「大字行 WRAP + 引擎行 WRAP」两行相加去凑一个估算出来的可用高度，
        //   车机上余量不到 1dp，标题行实际略高于 34dp 或系统字体被调大就溢出了。
        body.addView(row, LayoutParams(MATCH, MATCH))

        // ★ 引擎行不再是 body 的子项，而是 OilCard 根容器里最后一个「固定高度」子项。
        //   LinearLayout.measureVertical 先测 header 与 engineText 这两个 WRAP 子项，
        //   再把剩下的高度全分给 body(weight=1)。于是「发动机累计」永远先拿到完整行高，
        //   与字号估算误差、系统字体缩放、空间警报行是否出现都无关。
        engineText.text = ""
        engineText.isSingleLine = true
        engineText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        engineText.ellipsize = android.text.TextUtils.TruncateAt.END
        addView(engineText, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(6) })

        storageWarn.visibility = View.GONE
        body.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, oB ->
            if (b - t != oB - ot) fitRowToBody()
        }

        // 未授权时那条提示的点击：先试静默开（本应用有 WRITE_SECURE_SETTINGS），
        // 开不了才把用户送到系统设置页。不这么做的话，用户只会看到一块空白，
        // 根本不知道要去开什么 —— 6.0.71 就是这个坑。
        music.onHintClick = {
            when {
                MediaWatcher.ensureGrant(context) -> {
                    Toast.makeText(context, "音乐卡片已开启，放首歌试试", Toast.LENGTH_SHORT).show()
                }
                runCatching { context.startActivity(MediaWatcher.settingsIntent()) }.isSuccess -> {
                    Toast.makeText(
                        context, "请勾选「哈弗国潮桌面·音乐卡片」",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    Toast.makeText(
                        context, "请到 系统设置 → 通知 → 通知使用权 里勾选",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            lastGranted = null
            syncMusic(MediaWatcher.current())
        }

        post { fitRowToBody() }
    }

    override fun applyTheme(t: Theme) {
        super.applyTheme(t)
        hoursText.typeface = t.bold
        hoursText.setTextColor(t.color("p2TextMain", Theme.INK))
        capText.typeface = t.bold
        capText.setTextColor(t.color("p2TextMain", Theme.INK))
        cycleText.typeface = t.regular
        cycleText.setTextColor(t.color("p2TextLabel", Theme.LABEL))
        engineText.typeface = t.regular
        engineText.setTextColor(t.color("p2TextLabel", Theme.LABEL))
        storageValue.typeface = t.bold
        storageCap.typeface = t.regular
        storageWarn.typeface = t.bold
        // typeface 换了，Paint 实测的行高会变，两套自适应都要重算
        fitRowKey = ""
        engineSp = -1f
        fitEngineToHeight()
        refreshStorage(force = true)
        music.applyTheme(t)
    }

    /**
     * 音乐数据变了（6.0.74：**三种状态都常驻**，不再有 GONE 分支）。
     *
     * 1. 有歌 —— 封面 / 歌名 / 来源（6.0.77 起副行不再放歌手）；
     * 2. 没授权 —— 可点提示「点此开启音乐卡片」；
     * 3. 已授权但没在放 —— 空态：音符块 +「未在播放」或上次歌名 + 点此播放。
     *
     * 储存块的 LayoutParams 在 init 里一次性定死为 WRAP，这里不再逐秒改宽度 ——
     * 以前「有歌/没歌」翻转会重新分配整行宽度，储存数字会左右晃一下。
     */
    private fun syncMusic(np: NowPlaying?) {
        val granted = MediaWatcher.isGranted(context)
        lastGranted = granted
        when {
            np != null && np.title.isNotEmpty() -> music.refresh(np)
            !granted -> music.showHint("▶ 点此开启音乐卡片")
            else -> music.refresh(null)
        }
        applyPadV()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 本应用有 WRITE_SECURE_SETTINGS：能静默开通知使用权就直接开，用户不用去设置页。
        // 开不成时才由 syncMusic 回落到提示态。
        // 顺序很重要 —— 必须先授权再注册回调，否则 addListener 回灌的那一次会先画一下
        // 「未授权提示」，紧接着再被真实数据替换掉，视觉上会闪一下。
        lastGranted = null
        MediaWatcher.ensureGrant(context)
        // U 盘本地播放器只在桌面在的时候才需要上下文；它自己的状态变化会直接推给 MediaWatcher
        LocalPlayer.attach(context)
        // 6.0.74：冷启动自动随机播一首 U 盘音乐（内部只判一次，U 盘没插 / 没歌不动作）
        LocalPlayer.maybeAutoPlay()
        // addListener 会立刻回灌一次当前状态，所以挂上就能显示"现在在放什么"
        MediaWatcher.addListener(mediaCb)
        syncMusic(MediaWatcher.current())
    }

    override fun onDetachedFromWindow() {
        MediaWatcher.removeListener(mediaCb)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w == cardW && h == cardH) return
        cardW = w
        cardH = h
        applyPadV()
        fitEngineToHeight()
        fitRowKey = ""
        fitRowToBody()
    }

    /**
     * 上下内边距（6.0.74）。
     *
     * 音乐条现在常驻，空态也画 90dp 的音符块，所以统一 10dp：v6.0.72 把音乐条整体
     * 放大 1.5 倍（封面 60 → 90dp），机油卡总高只有 180dp、正文行原本 71dp，装不下
     * 90dp 的封面，这 24dp 只能从内边距里腾 —— 不能动标题行和引擎行，那是别的信息。
     * 6.0.73 之前「没音乐时 22dp」是为了与旧版逐像素一致，常驻后这个前提不再成立。
     */
    private fun applyPadV() {
        val v = context.dp(10)
        if (paddingTop != v || paddingBottom != v) {
            setPadding(paddingLeft, v, paddingRight, v)
        }
    }

    /** dp 转 Float，供按高度反算字号的浮点表达式用。 */
    private fun dpF(v: Int): Float = context.dp(v).toFloat()

    /**
     * 用 Paint 实测单行高度（px）。
     *
     * 6.0.49 用的是 `sp × scaledDensity × 1.18` 经验系数，且可用高度拿常量 34dp
     * 去减 —— 车机上算完只剩不到 1dp 余量。系统字体一调大（scaledDensity ≠ density）、
     * 或标题行实际量出来高于 34dp、或中文行高偏大，两行 WRAP 之和就会超过 body，
     * 最后一行被裁掉 —— 这就是「还是被折叠」的来源。
     */
    private fun linePx(sp: Float, bold: Boolean): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = if (bold) theme?.bold else theme?.regular
        p.textSize = sp * resources.displayMetrics.scaledDensity
        val fm = p.fontMetrics
        return fm.descent - fm.ascent
    }

    /** 引擎行字号只跟卡片总高有关，不参与正文缩放，避免两处互相触发重排。 */
    private fun fitEngineToHeight() {
        val h = cardH
        if (h <= 0) return
        val sp = when {
            h < context.dp(120) -> 13f
            h < context.dp(160) -> 16f
            else -> 19f
        }
        val gap = context.dp(if (h < context.dp(160)) 2 else 6)
        if (Math.abs(sp - engineSp) < 0.5f &&
            (engineText.layoutParams as? LayoutParams)?.topMargin == gap) return
        engineSp = sp
        engineText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        (engineText.layoutParams as? LayoutParams)?.topMargin = gap
    }

    /**
     * 按 body 的**真实**高度统一缩放正文这一行的所有字号。
     *
     * 引擎行已经不在这里了，正文只剩一行且高度锁成 MATCH；缩放后一定装得下，
     * 不再需要「搬到标题行右侧」这种降级排版，任何信息都不用隐藏。
     */
    private fun fitRowToBody() {
        val avail = (body.height - body.paddingTop - body.paddingBottom).toFloat()
        if (avail <= 0f) return
        val warn = storageWarn.visibility == View.VISIBLE
        val key = "${avail.toInt()}|$warn|${theme?.night}"
        if (key == fitRowKey) return
        fitRowKey = key

        val g2 = dpF(2)
        val g4 = dpF(4)
        val hBig = linePx(56f, true)
        val hRight = linePx(25f, true) + g2 + linePx(18f, false)
        val hStore = linePx(30f, true) + g2 + linePx(15f, false) +
            (if (warn) g4 + linePx(15f, true) else 0f)
        val design = maxOf(hBig, hRight, hStore)
        // 留 6% 余量：几个 topMargin 是固定 dp、不随字号缩，中文/数字混排的
        // 实际行高也会略大于 fontMetrics。下限 0.32 保证极矮卡还有 18sp 可读。
        val k = (avail * 0.94f / design).coerceIn(0.32f, 1f)

        hoursText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f * k)
        capText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * k)
        cycleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * k)
        storageValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f * k)
        storageCap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * k)
        storageWarn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * k)
        (cycleText.layoutParams as? LayoutParams)?.topMargin = (g2 * k).toInt()
        (storageCap.layoutParams as? LayoutParams)?.topMargin = (g2 * k).toInt()
        (storageWarn.layoutParams as? LayoutParams)?.topMargin = (g4 * k).toInt()
    }

    /** 60 秒缓存一次 StatFs；低空间时整块变红并给出强警报文字。 */
    private fun refreshStorage(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - storageAtMs < 60_000L) return
        storageAtMs = now
        val a = Storage.availBytes()
        val total = Storage.totalBytes()
        storageLow = a in 0 until Storage.LIMIT_BYTES
        storageValue.text = if (a < 0) "--" else Storage.gb(a)
        storageCap.text = if (a < 0) "剩余储存" else "剩余储存 / 共 ${Storage.gb(total)}"
        storageWarn.text = if (storageLow) "⚠️ 空间不足 · 请立即清理，否则将不能开机" else ""
        storageWarn.visibility = if (storageLow) View.VISIBLE else View.GONE
        val c = if (storageLow) 0xFFFF3B30.toInt()
        else theme?.color("p2TextLabel", Theme.LABEL) ?: Theme.LABEL
        storageValue.setTextColor(c)
        storageCap.setTextColor(c)
        storageWarn.setTextColor(c)
        storageBox.background = if (storageLow) {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(0x22FF453A)
                cornerRadius = context.dp(10).toFloat()
            }
        } else null
        // 空间警报行出现/消失会多占一行高度，重新按 body 真实高度收一次字号
        fitRowToBody()
    }

    override fun bind(s: CarSignal) {
        val t = theme ?: return
        // v6.0.72：用户可能在设置页刚勾完「通知使用权」再回到桌面 —— 那样不会重新 attach，
        // 只能靠这里发现授权态翻转。未授权时每 3 秒复查一次，授权后不再读 setting。
        val gateNow = SystemClock.elapsedRealtime()
        // 音乐数据自检。逐会话回调是主路径（切歌立刻到），但它只覆盖「播放器愿意发回调」
        // 的情况；4 秒一拍的重读兜住那些换歌不吭声的播放器（限流在 poll 内部）。
        if (gateNow - lastMediaTick > 4000L) {
            lastMediaTick = gateNow
            MediaWatcher.poll(context)
        }
        if (lastGranted != true && gateNow - lastGrantMs > 3000L) {
            lastGrantMs = gateNow
            val g = MediaWatcher.isGranted(context)
            if (g != lastGranted) {
                lastGranted = g
                syncMusic(MediaWatcher.current())
                if (g) MediaWatcher.ensureGrant(context)
            }
        }
        val minute = (EngineHours.totalSeconds() / 60L).toInt()
        val running = EngineHours.isRunning()
        if (minute == lastMinute && running == lastRunning && t === lastCardTheme) {
            // 存储读数自己带 60 秒缓存，这里只是让它有机会到期
            refreshStorage()
            return
        }
        lastMinute = minute
        lastRunning = running
        lastCardTheme = t
        val st = OilLife.compute()
        // 百分比不上屏，只用来定颜色（寿命越短越警示）
        val color = when {
            st.expired -> t.color("p2TextWarn", Theme.WARN)
            st.pct >= 50 -> t.color("p2TextMain", Theme.INK)
            st.pct >= 25 -> t.color("warm", 0xFFE08A00.toInt())
            else -> t.color("p2TextWarn", Theme.WARN)
        }
        hoursText.text = if (st.expired) "0h" else OilLife.hText(st.leftH)
        hoursText.setTextColor(color)
        capText.text = if (st.expired) "该换了" else "剩余时间"
        capText.setTextColor(color)
        cycleText.text = "按 ${st.cycleH}h 一换"
        engineText.text = "发动机累计 " + EngineHours.text() +
            if (running) "（运转中）" else ""
        refreshStorage()
    }
}
