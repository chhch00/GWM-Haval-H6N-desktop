package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.h6n.launcher.R
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.UpdateManager
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 原版 `res/layout/activity_main.xml` 里的 statusBar（72dp 高、左右 18dp、垂直居中）：
 *
 * ```
 * 13:34  11月9日 周一  ☁ 多云 18°        [P] │ ⬤油量 42L 水温 88℃ 变速油 76℃ ⬤电压 14.1V 电量 85% 寿命 12% │ ▂▄
 * ```
 *
 * 几何与字号照原布局：时间 34sp / 日期 25sp / 读数 28sp（`statusMetricText`），
 * 分隔线 1×30dp。可显示项由 `statusItems` 的 7 个开关控制。
 *
 * 6.0.64 的读数区结构（用户定稿）：
 * - **不用图标、不用小圆点**：组间靠 15dp 留白，两块之间靠两个独立胶囊；
 * - **电压 / 电量 / 电瓶寿命 单独一个胶囊**，和「油量 / 水温 / 变速油」分开，
 *   避免"电瓶三个数"和"车况三个数"糊成一排；
 * - 省下来的圆点宽度（约 95dp）全部还给字号：25sp → 28sp 起步，放不下才按 2.5% 往回收。
 */
@SuppressLint("ViewConstructor")
class StatusBarView(ctx: Context) : LinearLayout(ctx) {

    private lateinit var theme: Theme

    private val tvTime = TextView(ctx)
    private val tvDate = TextView(ctx)
    private val ivWxIcon = ImageView(ctx)
    private val tvWxText = TextView(ctx)
    private val tvWxTemp = TextView(ctx)
    private val wxGroup = LinearLayout(ctx)

    private val gearBadge = FrameLayout(ctx)
    private val ivGear = ImageView(ctx)
    /**
     * 档位取不到（`gear == "?"`，比如车机暂时没推 0x1A）时的兜底文字。
     * 之前 `gearRes == 0` 就 `setImageDrawable(null)`，徽标直接从界面上消失，
     * 看着就是"状态栏没有档位"。现在画一个中性圆底 + 「－」，位置始终在。
     */
    private val tvGear = TextView(ctx)
    private val modeChip = LinearLayout(ctx)
    private val ivMode = ImageView(ctx)
    private val tvMode = TextView(ctx)

    /**
     * 一组读数 = 标签 + 数值，挂在某个分组胶囊（车况 / 电瓶）里。
     *
     * 6.0.64 起**不用图标、也不用小圆点**：组与组之间靠 [itemGap] 的留白 + 两个独立胶囊
     * 来分隔。电压/电量/寿命被拎成第二个胶囊（用户要求"单独作为一个整体"），
     * 原来那 5 个 5dp 圆点连同两侧间距（约 95dp）全部还给字号。
     *
     * [cluster] 只是为了让 [applyItemGaps] 知道"谁是本组第一个可见项"——
     * 关掉某项后，新冒头的第一项左间距必须归零，否则胶囊左边会空一块。
     */
    private class MetricSlot(val cluster: LinearLayout, val label: TextView, val value: TextView)

    private val slots = ArrayList<MetricSlot>(7)

    private val sep1 = View(ctx)
    private val sep2 = View(ctx)
    /**
     * 右侧读数区：**一个透明容器装两个胶囊**。
     *
     * - [clusterCar] 车况：续航 / 油量 / 水温 / 变速油
     * - [clusterBat] 电瓶：电压 / 电量 / 寿命（单独成整体，和前三个隔开）
     *
     * 两个胶囊之间留 10dp，不加分隔线也不加圆点 —— 胶囊边界本身就是分隔。
     */
    private val metricsRow = LinearLayout(ctx)
    private val clusterCar = LinearLayout(ctx)
    private val clusterBat = LinearLayout(ctx)
    /** 同一胶囊内两组读数之间的留白（dp），随字号缩。 */
    private var itemGap = 14

    /** 数值区基准字号（默认 = 状态栏字号 + 3），收缩只在它基础上做。 */
    private var baseMetricSp = 28f
    private var metricScale = 1f
    private var fitTry = 0

    /**
     * 自动收缩是否已经**定案**。
     *
     * 之前 `autoFitMetrics()` 每次 `onLayout` 都跑一遍：电压从 14.1V 跳到 9.6V、续航从
     * 523km 跳到 1024km，实测总宽一变就可能再收 5%，于是"数字一动整条状态栏就跟着缩"，
     * 而且只会越缩越小（`metricScale` 只减不加）。
     *
     * 现在两条一起治：
     * 1. 每个读数按**最宽的占位串**钉死宽度（[lockValueWidth]），读数变化不再改变布局；
     * 2. 收缩只做一轮，找到能放下的字号就锁住（`fitLocked`），之后 `onLayout` 不再动字号。
     */
    private var fitLocked = false

    /** 读数的最宽占位串：宽度按它算，实际数字再窄也占同样宽度，界面因此不抖。 */
    private val valueTemplates by lazy {
        mapOf(
            vRange to "9999km",
            vFuel to "999L",
            vWater to "-88℃",
            vAtf to "-88℃",
            vVolt to "88.8V",
            vBatt to "100%",
            vSulfur to "100%"
        )
    }

    private val kRange = TextView(ctx); private val vRange = TextView(ctx)
    private val kFuel = TextView(ctx); private val vFuel = TextView(ctx)
    private val kWater = TextView(ctx); private val vWater = TextView(ctx)
    private val kAtf = TextView(ctx); private val vAtf = TextView(ctx)
    private val kVolt = TextView(ctx); private val vVolt = TextView(ctx)
    private val kBatt = TextView(ctx); private val vBatt = TextView(ctx)
    private val kSulfur = TextView(ctx); private val vSulfur = TextView(ctx)

    /**
     * 右端只留 4G 信号一格。
     *
     * WiFi / 蓝牙 / 热点三个图标按用户要求**不再显示**：车机上这三个状态由原厂状态栏
     * 和下拉开关管理，桌面上重复画一遍只是噪音；而且没有 ICarInfo 的机器上这几个值
     * 恒为 0，图标一直是灰的，看着像"坏了"。
     * 信号格（4G）保留，它是唯一有实际读数的网络指示。
     */
    private val ivSignal = ImageView(ctx)

    /**
     * 「有新版本」常驻徽标（移植自未重写版 `UpdateBadge`）。
     *
     * 待更新版本号存在 SP 里，所以它跨 Activity 重建、跨进程重启都还在，
     * 装完新版（versionCode 追上）自动消失。点击进更新面板。
     *
     * 位置很关键：必须插在**弹性空白之前**（紧跟天气组）。挂在弹性空白后面会把
     * 右侧六组读数整体往左推，宽度一超就触发 `autoFitMetrics` 再缩字号；
     * 插在空白前只是把空白吃掉，读数位置纹丝不动。
     */
    private val updateBadge = TextView(ctx)
    private var onUpdateClick: (() -> Unit)? = null

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateFmt = SimpleDateFormat("M月d日 EEE", Locale.CHINA)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        layoutParams = FrameLayout.LayoutParams(MATCH, ctx.dp(72)).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        buildChildren(ctx)
    }

    private fun buildChildren(ctx: Context) {
        // ---- 左：时间 / 日期 / 天气 ----
        tvTime.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            val lp = LayoutParams(WRAP, WRAP)
            lp.leftMargin = ctx.dp(2)
            lp.rightMargin = ctx.dp(10)
            layoutParams = lp
        }
        addView(tvTime)

        // 日期常显：原版状态栏左侧只有「日期」，时间是停靠栏时钟显示的。
        // 早期把 tvDate 塞进了天气组，一旦天气取不到（weather.key 为空）整组 GONE，
        // 连日期一起消失，左侧彻底空白 —— 这也是"状态栏像没显示"的一半原因。
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        addView(tvDate, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(6) })

        val wxGroup = this.wxGroup
        wxGroup.orientation = HORIZONTAL
        wxGroup.gravity = Gravity.CENTER_VERTICAL
        // 天气三件套紧贴日期：原来云朵左边距 22dp，加上锁宽撑出来的居中留白，
        // 日期和天气之间空出小半屏的一截，看着像"天气飘在中间"。
        ivWxIcon.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_wx_cloud_sun)
            layoutParams = LayoutParams(ctx.dp(30), ctx.dp(30)).apply { leftMargin = ctx.dp(10) }
        }
        tvWxText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        tvWxText.layoutParams = LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(8) }
        tvWxTemp.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            layoutParams = LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(8) }
        }
        wxGroup.addView(ivWxIcon)
        wxGroup.addView(tvWxText)
        wxGroup.addView(tvWxTemp)
        addView(wxGroup)

        // 有新版本时这里冒一个金色小药丸，没有就 GONE（不占位）
        updateBadge.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            text = "↑ 新版"
            setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(12), ctx.dp(5))
            visibility = GONE
            isClickable = true
            setOnClickListener { onUpdateClick?.invoke() }
        }
        addView(updateBadge, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(10) })

        // 弹性空白：把右侧信息推到屏幕右端
        addView(View(ctx), LayoutParams(0, 1, 1f))

        // ---- 右：挡位 / 驾驶模式 ----
        // ★ ivGear 必须挂到 gearBadge 里。写成 `ivGear.apply { addView(this, ...) }`
        //   会解析到 StatusBarView（LinearLayout）的 addView，把图标作为状态栏的直接子 View，
        //   宽度是 MATCH_PARENT → 在横向 LinearLayout 里它第一个量完整条屏宽，
        //   后面的驾驶模式 / 六组数值 / 分隔线 / 信号图标全部被挤成 0 宽，
        //   表现就是"状态栏只剩一条淡淡的挡位胶囊，其他什么都没有"。
        gearBadge.apply {
            layoutParams = LayoutParams(ctx.dp(38), ctx.dp(38))
            background = null
        }
        ivGear.scaleType = ImageView.ScaleType.FIT_CENTER
        gearBadge.addView(ivGear, FrameLayout.LayoutParams(MATCH, MATCH))
        tvGear.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            includeFontPadding = false
            visibility = GONE
        }
        gearBadge.addView(tvGear, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(gearBadge)

        modeChip.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            layoutParams = LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(12) }
            background = pill(ctx.dp(20), 0x0F000000)
        }
        ivMode.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ctx.dp(9), ctx.dp(9), ctx.dp(9), ctx.dp(9))
            setImageResource(R.drawable.ic_mode_standard)
            layoutParams = LayoutParams(ctx.dp(40), ctx.dp(40))
        }
        tvMode.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            text = "标准"
            layoutParams = LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(10) }
        }
        modeChip.addView(ivMode)
        modeChip.addView(tvMode)
        addView(modeChip)

        addView(separator(ctx, sep1))

        // ---- 右：读数区 = 两个胶囊（车况 / 电瓶）----
        // 车模机盖上的水温/变速油贴片取消后，变速油温搬到这里，紧挨着水温好对比。
        metricsRow.orientation = HORIZONTAL
        metricsRow.gravity = Gravity.CENTER_VERTICAL
        addView(metricsRow, LayoutParams(WRAP, WRAP).apply { leftMargin = ctx.dp(8) })

        for (c in listOf(clusterCar, clusterBat)) {
            c.orientation = HORIZONTAL
            c.gravity = Gravity.CENTER_VERTICAL
            c.setPadding(ctx.dp(10), ctx.dp(5), ctx.dp(10), ctx.dp(5))
            metricsRow.addView(c, LayoutParams(WRAP, WRAP))
        }
        // 车况 → 电瓶 之间 14dp：两个胶囊各自有边界，不需要再画分隔线。
        // 间距不能太小（原来试过 10dp）：胶囊同色时 10dp 在 1.5 密度下只有 15px，
        // 扫一眼会当成一条连续的白带，"电瓶单独成组"就没意义了。
        (clusterBat.layoutParams as LayoutParams).leftMargin = ctx.dp(14)

        addMetric(ctx, kRange to vRange, clusterCar)
        addMetric(ctx, kFuel to vFuel, clusterCar)
        addMetric(ctx, kWater to vWater, clusterCar)
        addMetric(ctx, kAtf to vAtf, clusterCar)
        addMetric(ctx, kVolt to vVolt, clusterBat)
        addMetric(ctx, kBatt to vBatt, clusterBat)
        addMetric(ctx, kSulfur to vSulfur, clusterBat)

        addView(separator(ctx, sep2))

        // ---- 右：4G 信号（WiFi / 蓝牙 / 热点图标已按要求移除）----
        ivSignal.setImageResource(R.drawable.ic_sig_4)
        addIcon(ctx, ivSignal, 6)
    }

    private fun addMetric(ctx: Context, pair: Pair<TextView, TextView>, cluster: LinearLayout) {
        val (k, v) = pair
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 29f)
        v.includeFontPadding = false

        val klp = LayoutParams(WRAP, WRAP)
        klp.leftMargin = if (cluster.childCount > 0) ctx.dp(itemGap) else 0
        cluster.addView(k, klp)
        cluster.addView(v, LayoutParams(WRAP, WRAP).apply { this.leftMargin = ctx.dp(5) })
        slots.add(MetricSlot(cluster, k, v))
    }

    private fun addIcon(ctx: Context, iv: ImageView, marginLeft: Int) {
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        // 原布局这几个图标是 32dp 固定尺寸；用 WRAP 会跟着 drawable 的固有宽高走，
        // 不同密度下大小不一致（信号图标偏大 / 蓝牙偏小）。
        addView(iv, LayoutParams(ctx.dp(32), ctx.dp(32)).apply { this.leftMargin = ctx.dp(marginLeft) })
    }

    private fun separator(ctx: Context, v: View): View {
        v.setBackgroundColor(0x1A000000)
        return v.apply {
            // 左右各 20dp（原版值）在只剩 6 组读数时显得太空，收到 14dp 把宽度让给字号
            layoutParams = LayoutParams(ctx.dp(1), ctx.dp(30)).apply {
                leftMargin = ctx.dp(14)
                rightMargin = ctx.dp(14)
            }
        }
    }

    private fun pill(r: Int, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = r.toFloat()
        setColor(fill)
    }

    // ---------------- 有新版本徽标 ----------------

    /** 点徽标的动作（桌面里接到更新面板）。 */
    fun setOnUpdateClick(f: () -> Unit) {
        onUpdateClick = f
    }

    /**
     * 按 SP 里的待更新版本刷新徽标。
     *
     * 不读网络、不查磁盘，纯 SP + PackageManager，随便调（onResume / 检查回调 /
     * applyTheme 各调一次也不心疼）。装完新版后 `pendingVer()` 会因为 versionCode
     * 已追上而返回 null，徽标自动消失。
     */
    fun refreshUpdate() {
        val p = runCatching { UpdateManager.pendingVer() }.getOrNull()
        val show = p != null
        val visible = updateBadge.visibility == VISIBLE
        if (show == visible) return
        updateBadge.visibility = if (show) VISIBLE else GONE
        // 徽标一出现就吃掉一块宽度，之前"已定案"的字号可能就放不下了（右侧信号图标会被
        // 顶出屏幕）。这里只解开定案、保留已收的 metricScale，让 autoFitMetrics 再收敛一轮。
        fitLocked = false
        fitTry = 0
        requestLayout()
    }

    // ---------------- 主题 ----------------

    fun applyTheme(t: Theme) {
        theme = t
        // 换肤后让下一次 bindSignal 全量重绑（字号/配色可能改变了取值分支）
        lastBind = null
        val label = t.color("textLabel", Theme.LABEL)
        val main = t.color("textMain", Theme.INK)
        val night = t.night

        // 时间用 clockText 字号（原布局 34sp，设置里可调）
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.size("clockText", 34).toFloat())
        tvTime.setTypeface(t.bold)
        tvTime.setTextColor(main)
        lockWidth(tvTime, "88:88")

        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.size("statusLabelText", 25).toFloat())
        tvDate.setTypeface(t.regular)
        tvDate.setTextColor(label)
        // ★ 日期不做「按最宽占位串钉宽」。原来是锁成 "11月88日 周一" 的宽度再居中，
        //   今天只有 "11月10日 周二"，多出来的宽度平分到左右两侧，日期和云朵之间
        //   因此永远空着二十几 dp —— 看起来就是"天气离日期很远"。
        //   日期一天只变一次（9日→10日），量变宽也只会让天气往右挪一点，
        //   后面的数值组被弹性空白钉在右端、不受影响，所以这里不锁反而更稳。
        tvDate.minWidth = 0

        tvWxText.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.size("statusLabelText", 25).toFloat())
        tvWxText.setTypeface(t.regular)
        tvWxText.setTextColor(label)
        // 同理：「阴」只有 1 个字，锁成 "雷阵雨"（3 字）的宽度会左右各空一个字，
        // 天气一换字数还会跳。天气本来就不参与数值组的右端对齐，不锁即可。
        tvWxText.minWidth = 0
        tvWxTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        tvWxTemp.setTypeface(t.bold)
        tvWxTemp.setTextColor(main)
        // 温度锁宽用 "88°"（和 "14°" 等宽）：原来的 "-88°" 多一个负号，
        // 居中后左右各多空半个字，又给天气这一簇添了一道缝
        lockWidth(tvWxTemp, "88°")

        // ui_config 里只有 textSubNight（夜间变体），昼间没有 textSub：
        // Theme.color 会先找 textSubNight，找不到再回退到这里给的 label 色。
        tvMode.setTextColor(t.color("textSub", label))
        tvMode.setTypeface(t.regular)
        // 挡位徽标自己带底色，不要再叠一层灰胶囊（否则彩圆外面多一圈灰环）
        gearBadge.background = null
        modeChip.background = pill(context.dp(20), if (night) 0x1FFFFFFF else 0x0F000000)

        val labelSize = t.size("statusLabelText", 25).toFloat()
        for (k in listOf(kRange, kFuel, kWater, kAtf, kVolt, kBatt, kSulfur)) {
            k.setTypeface(t.regular)
            k.setTextColor(label)
        }
        for (v in listOf(vRange, vFuel, vWater, vAtf, vVolt, vBatt, vSulfur)) {
            v.setTypeface(t.bold)
            v.setTextColor(main)
        }
        // 6.0.64 起读数基准字号独立成 `statusMetricText`（默认比状态栏字号大 3sp，即 28）。
        // 依据：去掉 5 个 5dp 小圆点（含两侧留白约 95dp）后宽度回来了，用户要求「适当调大」。
        // 仍然由 autoFitMetrics 兜底：真放不下会按 2.5% 一档往回收，收多少看实测。
        baseMetricSp = t.size("statusMetricText", labelSize.toInt() + 3).toFloat()
        metricScale = 1f
        fitTry = 0
        fitLocked = false
        applyMetricSize(baseMetricSp)
        // 原版布局里分隔线引用的是 color/sep = #FFD5DAE1（不透明浅灰蓝），
        // 不是半透明黑 —— 之前用 0x1A000000，夜间几乎看不见，整条状态栏看着"散了"。
        val sepColor = t.color("sep", if (night) 0x33FFFFFF else 0xFFD5DAE1.toInt())
        sep1.setBackgroundColor(sepColor)
        sep2.setBackgroundColor(sepColor)

        // 两个读数胶囊：白天半透明白玻璃，夜间一层淡白。
        // 电瓶那组用同一底但压深一档，让"这两块是两回事"一眼能看出来（不靠分隔线）。
        // ★ 0x99FFFFFF 超了 Int.MAX，Kotlin 会把它当 Long（编译报 type mismatch），
        //   必须显式 .toInt()；0x66FFFFFF / 0x1FFFFFFF / 0x2EFFFFFF 都在 Int 范围内。
        val capDay = 0x66FFFFFF
        val capNight = 0x1FFFFFFF
        val capBatDay = 0x99FFFFFF.toInt()
        val capBatNight = 0x2EFFFFFF
        clusterCar.background = pill(context.dp(22), if (night) capNight else capDay)
        clusterBat.background = pill(context.dp(22), if (night) capBatNight else capBatDay)

        // 状态栏左右内边距走 sizes.statusBarPad（原版 setupRailPanel 同样读这个键）
        val padX = context.dp(t.size("statusBarPad", 18))
        setPadding(padX, 0, padX, 0)

        // 图标（4G 信号 / 天气）走 statusIcon 上色
        ivSignal.setColorFilter(t.color("statusIcon", main))
        // ★ 挡位徽标不能上色：gear_*.png 本身就是"彩色圆 + 白色字母"的整张图，
        //   setColorFilter 会把白字和底色一起刷成同一个颜色 → 看起来就是一个实心黑点。
        //   （之前状态栏和挡位卡各有一处 tint，两处都成了黑点）。保持原色即可。
        ivGear.clearColorFilter()

        kRange.text = t.text("kRange", "续航")
        kFuel.text = t.text("kFuel", "油量")
        kWater.text = t.text("kWater", "水温")
        kAtf.text = t.text("kAtf", "变速油")
        kVolt.text = t.text("kVolt", "电压")
        kBatt.text = t.text("kBatt", "电量")
        // 2026-11-15：按用户要求改叫「电瓶寿命」。数值**仍是 ICarInfo 0x1B 的硫化率原值**
        // （用户明确选择只换标题不做 100-x 换算），所以数字越低电池越健康这一点没变。
        // 保留 `kSulfur` 这个键名和 `statusItems.sulfur` 开关名，避免已有配置失效。
        kSulfur.text = t.text("kSulfur", "电瓶寿命")

        applyItemVisibility(t)

        // 有新版本徽标：金色，白天用深金保证在浅底上读得清，夜间用亮金
        updateBadge.setTextColor(if (night) 0xFFFFC63A.toInt() else 0xFF8A6A12.toInt())
        updateBadge.background = pill(
            context.dp(16),
            if (night) 0x33FFC63A else 0x24C9A227
        )
        refreshUpdate()
    }

    /**
     * statusItems 的开关：关掉的项标签和数值一起收，然后把「每组的第一个可见项」左间距归零。
     *
     * 注意默认值：`range`（续航）与 `mode`（驾驶模式）默认关。用户 2026-11-10 明确要求
     * 状态栏不显示这两项，省下来的宽度全部让给剩下 6 组读数的字号。
     */
    private fun applyItemVisibility(t: Theme) {
        val on = booleanArrayOf(
            t.flag("statusItems.range", false),
            t.flag("statusItems.fuel", true),
            t.flag("statusItems.water", true),
            t.flag("statusItems.atf", true),
            t.flag("statusItems.volt", true),
            t.flag("statusItems.batt", true),
            t.flag("statusItems.sulfur", true)
        )
        for (i in slots.indices) {
            val v = if (on[i]) VISIBLE else GONE
            slots[i].label.visibility = v
            slots[i].value.visibility = v
        }
        applyItemGaps()
        modeChip.visibility = if (t.flag("statusItems.mode", false)) VISIBLE else GONE
    }

    /**
     * 重排组内左间距：每个胶囊里**第一个可见组**左间距为 0，其余为 [itemGap]。
     *
     * 关掉某一项后（比如关掉「油量」），新冒头的第一组不能还带着 14dp 左间距，
     * 否则胶囊左侧会空出一块；这条规则按 [MetricSlot.cluster] 分别统计。
     */
    private fun applyItemGaps() {
        val seen = HashSet<LinearLayout>()
        for (s in slots) {
            if (s.label.visibility != VISIBLE) continue
            val gapPx = context.dp(if (seen.add(s.cluster)) 0 else itemGap)
            val lp = s.label.layoutParams as? LayoutParams ?: continue
            if (lp.leftMargin == gapPx) continue
            lp.leftMargin = gapPx
            s.label.layoutParams = lp
        }
    }

    // ---------------- 数值区自动收缩 ----------------

    /** 按 sp 统一下发数值区字号；组间留白跟着收，否则字小了间距还占着。 */
    private fun applyMetricSize(sp: Float) {
        for (k in listOf(kRange, kFuel, kWater, kAtf, kVolt, kBatt, kSulfur)) {
            k.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
        for (v in listOf(vRange, vFuel, vWater, vAtf, vVolt, vBatt, vSulfur)) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp + 1f)
            // 宽度按最宽占位串钉死：读数天天在变，不能让宽度跟着变
            valueTemplates[v]?.let { lockWidth(v, it) }
        }
        // 组间留白跟字号走：28sp 时 15dp，收到 20sp 时 11dp —— 纯装饰，收字号时必须一起收，
        // 不然"字小了、缝还在"，白占宽度。
        itemGap = (sp * 0.55f).toInt().coerceIn(8, 18)
        applyItemGaps()
    }

    /**
     * 把 TextView 的宽度钉在「最宽占位串」上。
     *
     * 读数一变（14.1V → 9.6V、523km → 1020km）文本宽度就变，WRAP 宽度跟着变，
     * 横向 LinearLayout 重新分配剩余空间 → 看起来就是整条状态栏在抖。
     * 给 minWidth 一个最宽占位串的实测宽度后，内容再窄也占同样地方，布局不再变。
     *
     * 必须在 setTextSize 之后调用：paint 的 textSize 决定测量结果。
     */
    private fun lockWidth(v: TextView, template: String) {
        v.gravity = Gravity.CENTER
        v.minWidth = ceil(v.paint.measureText(template)).toInt() + context.dp(2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 字号一旦定案就不再重算，否则每次布局都可能再缩一档，形成"越看越小"的循环
        if (fitLocked) return
        autoFitMetrics()
    }

    /**
     * 状态栏项数多（加变速油后是 7 组）时 2400px 宽放不下，最右边的「硫化率」会被裁掉。
     *
     * 这里按**实测**宽度等比收字号：每次 5%，最多 10 次（约 0.6 倍），收到能放下就停，
     * 然后 `fitLocked = true` 定案。
     *
     * 因为每个读数的宽度已经按 [valueTemplates] 钉死了，这里量出来的总宽只跟字号、
     * 开了哪几项、标签文案有关，与实时读数无关 —— 所以只会收一次，不会来回抖。
     */
    private fun autoFitMetrics() {
        val avail = width - paddingLeft - paddingRight
        if (avail <= 0 || childCount == 0) return
        var total = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as? LayoutParams ?: continue
            total += c.measuredWidth + lp.leftMargin + lp.rightMargin
        }
        if (total <= avail) {
            fitLocked = true
            return
        }
        if (fitTry >= 16) {
            fitLocked = true
            return
        }
        fitTry++
        // 6.0.64：步长从 5% 收到 2.5%。基准字号提到 28 以后，5% 一档太粗 ——
        // 明明 26.6sp 就放得下，按 5% 会一路退到 25.2，白白小一圈。
        metricScale = (metricScale - 0.025f).coerceAtLeast(0.6f)
        applyMetricSize((baseMetricSp * metricScale).coerceAtLeast(14f))
        post { requestLayout() }
    }

    // ---------------- 数据 ----------------

    fun bindClock(now: Date) {
        tvTime.text = timeFmt.format(now)
        tvDate.text = dateFmt.format(now)
    }

    fun bindWeather(text: String?, tempC: Int?) {
        // ★ 不再"取不到就整组 GONE"：车机刚打火常常还没连上网，整组消失看起来像
        //   状态栏坏了/没有天气功能。现在天气区常驻，没数据时占位显示「天气 --°」，
        //   有数据再换成「晴 7°」。要彻底关掉天气用 setWeatherVisible(false)
        //   （设置里 weather.enabled 关掉时由 MainActivity 调）。
        wxGroup.visibility = VISIBLE
        if (text.isNullOrEmpty() || tempC == null) {
            tvWxText.text = "天气"
            tvWxTemp.text = "--°"
            ivWxIcon.setImageResource(R.drawable.ic_wx_cloud_sun)
            return
        }
        tvWxText.text = text
        tvWxTemp.text = "$tempC°"
        val res = iconFor(text)
        if (res != 0) ivWxIcon.setImageResource(res)
    }

    /** 配置里把天气关掉时才整组收起（和"暂时没数据"区分开）。 */
    fun setWeatherVisible(on: Boolean) {
        wxGroup.visibility = if (on) VISIBLE else GONE
    }

    /** 长按日期/天气区立即刷新（原版 setupWeather 里就是这么挂的）。 */
    fun setOnWeatherLongClick(action: () -> Unit) {
        wxGroup.setOnLongClickListener { action(); true }
        tvDate.setOnLongClickListener { action(); true }
        tvWxText.setOnLongClickListener { action(); true }
        tvWxTemp.setOnLongClickListener { action(); true }
    }

    /**
     * 天气文案 → 图标，判断顺序照搬原版 `MainActivity` 的 pickWxIcon（smali 54440-54760）。
     * 注意「雨夹雪 / 冻雨」要先于「雪」「雨」判断，否则会被拆到单一天气里。
     */
    private fun iconFor(state: String): Int = when {
        state.contains("雷") -> R.drawable.ic_wx_thunder
        state.contains("雨夹雪") || state.contains("冻雨") -> R.drawable.ic_wx_sleet
        state.contains("雪") -> when {
            state.contains("暴") -> R.drawable.ic_wx_blizzard
            state.contains("中") -> R.drawable.ic_wx_snow_mid
            state.contains("大") -> R.drawable.ic_wx_snow
            state.contains("小") -> R.drawable.ic_wx_snow_light
            else -> R.drawable.ic_wx_snow
        }
        state.contains("冰雹") || state.contains("雾") || state.contains("霾") ||
            state.contains("沙") || state.contains("尘") -> R.drawable.ic_wx_dust
        state.contains("雨") -> when {
            state.contains("暴") || state.contains("大") -> R.drawable.ic_wx_heavy_rain
            state.contains("中") -> R.drawable.ic_wx_rain_mid
            state.contains("阵") -> R.drawable.ic_wx_shower
            else -> R.drawable.ic_wx_rain
        }
        state.contains("晴") -> if (state.contains("云")) R.drawable.ic_wx_cloud_sun else R.drawable.ic_wx_sun
        state.contains("多云") -> R.drawable.ic_wx_cloud_sun
        state.contains("阴") -> R.drawable.ic_wx_cloud
        else -> 0
    }

    /** 由停靠栏时钟接管时间显示时，状态栏不再重复显示时间。 */
    fun setTimeVisible(on: Boolean) {
        tvTime.visibility = if (on) VISIBLE else GONE
    }

    /**
     * 上一次绑定用的信号（v6.0.44）。
     *
     * 状态栏只显示这 9 项：挡位 / 驾驶模式 / 续航 / 油量 / 水温 / 变速箱油 / 电压 /
     * 电量 / 硫化率（+ 4G 格数）。车速每秒都在跳，但这一整排数字几分钟都不动一次 ——
     * 原先每秒拼 8 个字符串 + 8 次 setText + 2 次 setImageResource，纯属白烧。
     */
    private var lastBind: CarSignal? = null

    fun bindSignal(s: CarSignal) {
        val p = lastBind
        if (p != null && p.gear == s.gear && p.driveMode == s.driveMode &&
            p.rangeKm == s.rangeKm && p.fuelLiters == s.fuelLiters &&
            p.coolantC == s.coolantC && p.atfC == s.atfC && p.volt == s.volt &&
            p.battPct == s.battPct && p.sulfurPct == s.sulfurPct &&
            p.signalLevel == s.signalLevel
        ) {
            return
        }
        lastBind = s
        // 挡位：字母 + 对应图标
        val gear = s.gear.uppercase(Locale.CHINA)
        val gearRes = when (gear) {
            "P" -> R.drawable.gear_p
            "R" -> R.drawable.gear_r
            "N" -> R.drawable.gear_n
            "D", "S", "M", "L" -> R.drawable.gear_d
            else -> 0
        }
        if (gearRes != 0) {
            ivGear.visibility = VISIBLE
            tvGear.visibility = GONE
            ivGear.setImageResource(gearRes)
        } else {
            // 未知档位：画个中性圆底 + 字母/「－」，保证这一格在界面上存在
            ivGear.visibility = GONE
            tvGear.visibility = VISIBLE
            tvGear.text = if (gear.isEmpty() || gear == "?") "－" else gear
            tvGear.setBackground(pill(context.dp(19), 0x1F000000))
            if (::theme.isInitialized) tvGear.setTextColor(theme.color("textSub", Theme.LABEL))
        }

        // 驾驶模式：这里拿到的是**语义值**（已由 CarBinderSource.semOf 转好）——
        // 1 标准 / 2 经济 / 3 运动（4 雪地 / 5 沙地 / 6 泥地 / 7 自动 / 8 纯电 / 9 节能 / 10 四驱）
        val (modeName, modeIcon) = when (s.driveMode) {
            1 -> "标准" to R.drawable.ic_mode_standard
            2 -> "经济" to R.drawable.ic_mode_eco
            3 -> "运动" to R.drawable.ic_mode_sport
            4 -> "雪地" to R.drawable.ic_mode_standard
            5 -> "沙地" to R.drawable.ic_mode_standard
            6 -> "泥地" to R.drawable.ic_mode_standard
            7 -> "自动" to R.drawable.ic_mode_standard
            8 -> "纯电" to R.drawable.ic_mode_eco
            9 -> "节能" to R.drawable.ic_mode_eco
            10 -> "四驱" to R.drawable.ic_mode_standard
            else -> "—" to R.drawable.ic_mode_standard
        }
        tvMode.text = modeName
        ivMode.setImageResource(modeIcon)

        // ★ 6.0.31 最后一道防线：数据层已按量程清洗，这里再兜一次，
        //   防止模拟数据 / 备份恢复 / 旧缓存把「65535km / 255L / 47℃」这类
        //   其它车型的 Invalid 标记带进状态栏。NaN 与越界一律显示「--」。
        vRange.text = if (s.rangeKm in 0..2000) "${s.rangeKm}km" else "--"
        vFuel.text = if (s.fuelLiters in 0f..150f) "${s.fuelLiters.toInt()}L" else "--"
        // ★ 6.0.60：水温再单独挡一次「显示出来正好是 -1℃」。
        //   两个来源同形：① 平台 key 无数据时回 -1；② raw 62/63 经 round(raw×0.75−48)
        //   得到 -1.5/−0.75，toInt() 也是 -1。两者都不是真实水温（2026-11-14 实机截图
        //   「油量 -- · 水温 -1℃ · 电压 --」），宁可显示「--」；-2℃ 及以下照常显示。
        vWater.text = when {
            s.coolantC !in -40f..130f -> "--"
            s.coolantC.toInt() == -1 -> "--"
            else -> "${s.coolantC.toInt()}℃"
        }
        vAtf.text = if (s.atfC in -40f..200f) "${s.atfC.toInt()}℃" else "--"
        vVolt.text = if (s.volt in 8f..20f) String.format(Locale.CHINA, "%.1fV", s.volt) else "--"
        // 百分比一律按 0..100 兜底：车控的 0xFF(255) 是 Invalid，
        // 就算别处（配置、模拟数据、旧缓存）混进来也不能显示成「255%」
        vBatt.text = if (s.battPct in 0..100) "${s.battPct}%" else "--"
        vSulfur.text = if (s.sulfurPct in 0..100) "${s.sulfurPct}%" else "--"

        // 4G 信号格数（WiFi / 蓝牙 / 热点不再画）
        ivSignal.setImageResource(signalIcon(s.signalLevel))
    }

    private fun signalIcon(level: Int): Int = when (level.coerceIn(0, 4)) {
        0 -> R.drawable.ic_sig_0
        1 -> R.drawable.ic_sig_1
        2 -> R.drawable.ic_sig_2
        3 -> R.drawable.ic_sig_3
        else -> R.drawable.ic_sig_4
    }

    companion object {
        private const val MATCH = -1
        private const val WRAP = -2
        private const val VISIBLE = 0
        private const val GONE = 8
    }
}
