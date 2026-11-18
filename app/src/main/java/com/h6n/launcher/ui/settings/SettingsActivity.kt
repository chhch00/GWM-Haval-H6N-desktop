package com.h6n.launcher.ui.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.core.AmapNightSync
import com.h6n.launcher.core.AmapNavi
import com.h6n.launcher.core.AutoBackup
import com.h6n.launcher.core.AutoClose
import com.h6n.launcher.core.BootTask
import com.h6n.launcher.core.AudioRoute
import com.h6n.launcher.core.CarApiFallback
import com.h6n.launcher.core.CarBinderSource
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.ConfigBackup
import com.h6n.launcher.core.DoorAvm
import com.h6n.launcher.core.EngineHours
import com.h6n.launcher.core.EdgeGesture
import com.h6n.launcher.core.FloatButton
import com.h6n.launcher.core.H6A11y
import com.h6n.launcher.core.Hvac
import com.h6n.launcher.core.H6Log
import com.h6n.launcher.core.LogExport
import com.h6n.launcher.core.LocPerm
import com.h6n.launcher.core.LocalPlayer
import com.h6n.launcher.core.MediaWatcher
import com.h6n.launcher.core.NavInfoOverlay
import com.h6n.launcher.core.NavOverlay
import com.h6n.launcher.core.NightScreen
import com.h6n.launcher.core.OilLife
import com.h6n.launcher.core.SceneAnnouncer
import com.h6n.launcher.core.SceneStore
import com.h6n.launcher.core.AppIconCache
import com.h6n.launcher.core.PermGuide
import com.h6n.launcher.core.PerfStats
import com.h6n.launcher.core.SignalHub
import com.h6n.launcher.core.SimSignals
import com.h6n.launcher.core.SteerKey
import com.h6n.launcher.core.SteerMode
import com.h6n.launcher.core.StartStop
import com.h6n.launcher.ui.main.AcProbeDialog
import com.h6n.launcher.ui.main.LogDialog
import com.h6n.launcher.core.Storage
import com.h6n.launcher.core.TireAlert
import com.h6n.launcher.core.TireRules
import com.h6n.launcher.core.UpdateManager
import com.h6n.launcher.core.WallpaperStore
import com.h6n.launcher.core.WeatherSource
import com.h6n.launcher.domain.ConfigSnapshot
import com.h6n.launcher.ui.common.NightMode
import com.h6n.launcher.ui.main.AutoPanels
import com.h6n.launcher.ui.main.BackupDialog
import com.h6n.launcher.ui.main.CardDialogs
import com.h6n.launcher.ui.main.FullCleanDialog
import com.h6n.launcher.ui.main.DockPicker
import com.h6n.launcher.ui.main.DockSlots
import com.h6n.launcher.ui.main.PermPanel
import com.h6n.launcher.ui.main.StorageAlert
import com.h6n.launcher.ui.main.SteerKeyPanel
import com.h6n.launcher.ui.main.UpdateDialog
import com.h6n.launcher.ui.main.VoicePanel
import com.h6n.launcher.ui.main.WallpaperDialog
import com.h6n.launcher.ui.common.ScaledActivity
import com.h6n.launcher.ui.common.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页 —— 按原版 `SettingsActivity` 的结构复刻：
 *
 * ```
 * 渐变底（sizes.cornerR 四角）
 *  └ 竖排
 *     ├ 顶栏：[← 完成]  桌面设置 / 改动即时保存 · 返回桌面自动生效
 *     └ 横排
 *        ├ 左侧导航 280dp：外观 · 功能 · 车机 · 维护 · 关于（每项 60dp，选中背景 = accent 20%）
 *        └ 右侧 5 个 ScrollView：卡片（圆角 24dp / 1px 描边 / 内边距 32·24 / elevation 6dp）
 * ```
 *
 * 每个可交互项都绑定 `ui_config.json` 里真实存在的键，改一项只写一个 key；
 * 原版有但重写版尚未实现的项（应用列表、场景助手、方控、悬浮歌词、在线更新）
 * 一律不出现 —— 不放"点了没反应"的假开关。
 */
class SettingsActivity : ScaledActivity() {

    private val repo by lazy { (application as H6NApplication).config }

    private var snap: ConfigSnapshot? = null
    private var theme: Theme? = null
    private var night = false

    // ---- 主题色（与原版 pick() 同一套：昼默认 / 夜默认）----
    private var cText = 0xFF1F2937.toInt()
    private var cSub = 0xFF5C6470.toInt()
    private var cAccent = 0xFF2F7BF6.toInt()
    private var cCard = 0xFFFDFEFF.toInt()
    private var cDiv = 0x121F2937
    private var cSegBg = 0xF1F2937.toInt()
    private var cDanger = 0xFFE5484D.toInt()
    private var bgTop = 0xFFDCE4F0.toInt()
    private var bgMid = 0xFFC6D3E6.toInt()
    private var bgBot = 0xFFAABAD4.toInt()

    private lateinit var root: FrameLayout
    private lateinit var bgView: View
    private val navs = arrayOfNulls<LinearLayout>(TABS.size)
    private val navIcons = arrayOfNulls<NavIcon>(TABS.size)
    private val navTexts = arrayOfNulls<TextView>(TABS.size)
    private val pages = arrayOfNulls<ScrollView>(TABS.size)
    private val bodies = arrayOfNulls<LinearLayout>(TABS.size)

    // 6.0.86：每个标签页顶部的「卡片快速跳转栏」——本页所有卡片名横排，点一下滚到对应卡片。
    // 每个 tab 各自记录卡片标题与对应 view，切页时只重建当前页的跳转栏。
    private val chipBars = arrayOfNulls<HorizontalScrollView>(TABS.size)
    private val chipBoxes = arrayOfNulls<LinearLayout>(TABS.size)
    private val cardAnchors = Array(TABS.size) { ArrayList<Pair<String, View>>() }
    private val chipViews = Array(TABS.size) { ArrayList<TextView>() }
    private var activeChip = -1

    private var tab = 0
    private var shellReady = false
    private var rowCount = 0

    /** 上次看到的无障碍服务开关状态，从系统页回来时用它判断要不要重建那一行。 */
    private var a11yWasOn = false

    /** 刚点下的驾驶模式（车控回报要 1~3 秒，先用它顶着，免得选中的段又跳回去）。 */
    private var pendingMode = -1
    private var pendingAt = 0L

    /** 配置变化后只跑这些闭包刷新数值，不重建控件。 */
    private val refreshers = ArrayList<() -> Unit>(32)
    private val sigRefreshers = ArrayList<() -> Unit>(16)
    /** 「车机」页模拟信号面板的重绘闭包（循环演示时会按节拍自己变）。 */
    private var simPaint: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从设置页直接启动进程时也要先把发动机计时读出来（机油寿命的输入）
        EngineHours.attach(this)
        snap = repo.state.value
        night = snap?.let { NightMode.isNight(it) } ?: false
        theme = snap?.let { Theme(this, it, night) }
        applyColors()

        buildShell()
        switchTo(intent.getIntExtra("tab", 0).coerceIn(0, TABS.size - 1))
        shellReady = true
        observeConfig()
        // 循环演示时开关状态会自己变，面板和信号读数每 400ms 跟一次节拍
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (tab == 2) {
                        simPaint?.invoke()
                        for (f in sigRefreshers) f()
                    }
                    delay(400L)
                }
            }
        }
    }

    private fun tr(): Typeface = theme?.regular ?: Typeface.DEFAULT
    private fun tb(): Typeface = theme?.bold ?: Typeface.DEFAULT_BOLD

    // ---------------- 配色 ----------------

    private fun pick(key: String, day: Int, nightValue: Int): Int =
        theme?.color(key, if (night) nightValue else day) ?: if (night) nightValue else day

    /** @return 是否发生了可见的颜色变化（变了就要整页重建） */
    private fun applyColors(): Boolean {
        val b = intArrayOf(cText, cSub, cAccent, cCard, cDiv, cSegBg, bgTop, bgMid, bgBot)
        cText = pick("textMain", 0xFF1F2937.toInt(), 0xFFFFFBF5.toInt())
        cSub = pick("textLabel", 0xFF5C6470.toInt(), 0xFFB8C2CE.toInt())
        cAccent = pick("accent", 0xFF2F7BF6.toInt(), 0xFF4E96FF.toInt())
        cCard = pick("cardBg", 0xFFFDFEFF.toInt(), 0xFF1D232C.toInt())
        cDiv = if (night) 0x1FFFFFFF else 0x121F2937
        cSegBg = if (night) 0x1FFFFFFF else 0xF1F2937.toInt()
        cDanger = if (night) 0xFFFF6B62.toInt() else 0xFFE5484D.toInt()
        bgTop = pick("screenTop", 0xFFDCE4F0.toInt(), 0xFF141A22.toInt())
        bgMid = pick("screenMid", 0xFFC6D3E6.toInt(), 0xFF1B222C.toInt())
        bgBot = pick("screenBot", 0xFFAABAD4.toInt(), 0xFF232C38.toInt())
        val a = intArrayOf(cText, cSub, cAccent, cCard, cDiv, cSegBg, bgTop, bgMid, bgBot)
        return !a.contentEquals(b)
    }

    // ---------------- 骨架 ----------------

    private fun buildShell() {
        // 外层纯黑：圆角渐变铺在里面，四角露出的就是黑（原版是 CornerMaskView 遮黑角）
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bgView = View(this).apply { background = screenBg() }
        root.addView(bgView, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(4), dp(56), dp(30))
        }

        col.addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))

        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(main, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(nav, LinearLayout.LayoutParams(dp(280), MATCH).apply { rightMargin = dp(24) })
        for (i in TABS.indices) {
            nav.addView(navItem(i), LinearLayout.LayoutParams(MATCH, dp(60)).apply {
                if (i > 0) topMargin = dp(4)
            })
        }

        // 右侧：本页卡片快速跳转栏（横排，常驻）+ 各标签页内容
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))

        val chipBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val chipBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipBar.addView(chipBox, FrameLayout.LayoutParams(WRAP, MATCH))
        right.addView(chipBar, LinearLayout.LayoutParams(MATCH, dp(64)).apply { bottomMargin = dp(10) })

        val host = FrameLayout(this)
        right.addView(host, LinearLayout.LayoutParams(MATCH, 0, 1f))
        for (i in TABS.indices) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val sv = ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(body)
                // 滚动时高亮当前所在卡片对应的跳转按钮
                setOnScrollChangeListener { _, _, scrollY, _, _ -> onPageScroll(i, scrollY) }
            }
            host.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            pages[i] = sv
            bodies[i] = body
            chipBars[i] = chipBar
            chipBoxes[i] = chipBox
        }

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun topBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
        }

        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(22), dp(12))
            background = round(alpha(cAccent, 0x1F), dp(20))
            isClickable = true
            setOnClickListener { finish() }
        }
        back.addView(
            NavIcon(this, 5).apply { color = cAccent },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(8) }
        )
        back.addView(text("完成", 26f, cAccent, false))
        bar.addView(back, LinearLayout.LayoutParams(WRAP, dp(64)).apply { rightMargin = dp(24) })

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titles.addView(text("桌面设置", 30f, cText, true))
        titles.addView(text("改动即时保存 · 返回桌面自动生效", 20f, cSub, false).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(18) }
        })
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        return bar
    }

    private fun navItem(i: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), 0, dp(18), 0)
            isClickable = true
            setOnClickListener { switchTo(i) }
        }
        val icon = NavIcon(this, i).apply { color = cSub }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(22) })
        val tv = text(TABS[i], 26f, cSub, false)
        row.addView(tv)
        navs[i] = row
        navIcons[i] = icon
        navTexts[i] = tv
        return row
    }

    private fun switchTo(index: Int) {
        if (index !in TABS.indices) return
        tab = index
        for (k in TABS.indices) {
            val on = k == index
            navs[k]?.background = if (on) round(alpha(cAccent, 0x1F), dp(20)) else null
            navTexts[k]?.apply {
                setTextColor(if (on) cAccent else cSub)
                typeface = if (on) tb() else tr()
            }
            navIcons[k]?.let {
                it.color = if (on) cAccent else cSub
                it.invalidate()
            }
            pages[k]?.visibility = if (on) View.VISIBLE else View.GONE
        }
        rebuild(index)
    }

    private fun rebuild(index: Int) {
        val body = bodies[index] ?: return
        val sv = pages[index] ?: return
        val keepY = sv.scrollY
        refreshers.clear()
        sigRefreshers.clear()
        simPaint = null
        body.removeAllViews()
        rowCount = 0
        cardAnchors[index].clear()
        when (index) {
            0 -> pageAppearance(body)
            1 -> pageFeature(body)
            2 -> pageVehicle(body)
            3 -> pageMaintain(body)
            else -> pageAbout(body)
        }
        buildChips(index)
        sv.post {
            sv.scrollTo(0, keepY)
            onPageScroll(index, keepY)
        }
    }

    /** 6.0.86：按本页登记到的卡片，重建横排跳转栏；点标题直接滚到那张卡片。 */
    private fun buildChips(index: Int) {
        val box = chipBoxes[index] ?: return
        box.removeAllViews()
        chipViews[index].clear()
        activeChip = -1
        val anchors = cardAnchors[index]
        anchors.forEachIndexed { i, (title, _) ->
            val chip = text(title, 22f, cSub, false).apply {
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isClickable = true
                setOnClickListener {
                    val sv = pages[index] ?: return@setOnClickListener
                    sv.post { sv.smoothScrollTo(0, (anchors[i].second.top - dp(6)).coerceAtLeast(0)) }
                    setActiveChip(index, i)
                }
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    if (i > 0) leftMargin = dp(10)
                }
            }
            box.addView(chip)
            chipViews[index].add(chip)
        }
        if (anchors.isNotEmpty()) setActiveChip(index, 0)
    }

    /** 高亮第 i 个跳转按钮，其余恢复普通色。 */
    private fun setActiveChip(index: Int, i: Int) {
        if (activeChip == i) return
        activeChip = i
        chipViews[index].forEachIndexed { k, tv ->
            val on = k == i
            tv.setTextColor(if (on) cAccent else cSub)
            tv.typeface = if (on) tb() else tr()
            tv.background = round(alpha(cAccent, if (on) 0x2A else 0x12), dp(16))
        }
        // 让选中的 chip 尽量滚到可视区
        chipBars[index]?.post {
            val bar = chipBars[index] ?: return@post
            val chip = chipViews[index].getOrNull(i) ?: return@post
            val target = chip.left - bar.width / 2 + chip.width / 2
            bar.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    /** 跟随内容滚动，高离顶部最近的那张卡片。 */
    private fun onPageScroll(index: Int, scrollY: Int) {
        if (index != tab) return
        val anchors = cardAnchors[index]
        if (anchors.isEmpty()) return
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in anchors.indices) {
            val d = kotlin.math.abs(anchors[i].second.top - dp(6) - scrollY)
            if (d < bestDist) { bestDist = d; best = i }
        }
        setActiveChip(index, best)
    }

    // ---------------- 页面 ----------------

    private fun pageAppearance(body: LinearLayout) {
        card(body, "主题模式") {
            segRow(
                this, "theme.mode", "外观跟随",
                "跟随灯光 = 开灯切夜间 / 关灯切白天（读车身大灯信号，读不到按时间兜底）；按时间 = 18:40–06:20",
                listOf(
                    "auto" to "跟随灯光", "time" to "按时间",
                    "day" to "浅色", "night" to "深色"
                )
            )
            switchRow(this, "theme.amapSync", "同步高德昼夜", "切换时给高德发昼夜 + 大灯状态广播")
        }
        card(body, "卡片高度") {
            segRow(
                this, "sizes.cardSize", "第 2 页卡片",
                "底部留白固定，高度往上长（过高会顶到状态栏）",
                listOf("650" to "高", "632" to "标准", "614" to "矮")
            )
        }
        card(body, "圆角") {
            segRow(
                this, "sizes.cardRadius", "卡片圆角", "第 2 页所有卡同步",
                listOf("32" to "小", "48" to "中", "64" to "大")
            )
        }
        card(body, "壁纸") {
            infoRow(this, "当前壁纸") { WallpaperStore.get(this@SettingsActivity).label() }
            actionRow(
                this, "更换壁纸",
                "在线图库需授权激活（服务器加了新图这里就有），也可从车机内部存储 / 相册 / 下载 / U 盘里选图；默认不铺图，长按桌面 dock 时钟也能打开",
                "选择"
            ) {
                // 设置页本身不铺桌面壁纸，返回桌面时 MainActivity.onResume 会按新配置重画
                WallpaperDialog.show(this@SettingsActivity)
            }
            actionRow(this, "清除壁纸", "桌面回到主题渐变（不再铺图），背景蒙层恢复为「中」", "清除") {
                WallpaperStore.reset(this@SettingsActivity)
                toast("已清除壁纸，返回桌面即生效")
            }
            val scrimNow = WallpaperStore.get(this@SettingsActivity).scrim
            actionRow(
                this, "背景蒙层",
                "当前「" + WallpaperStore.scrimLabel(scrimNow) + "」；白字自动压深底、深字自动压亮底，换壁纸后文字看不清就调高一档",
                WallpaperStore.scrimLabel(scrimNow)
            ) {
                val next = WallpaperStore.nextScrim(scrimNow)
                WallpaperStore.setScrim(this@SettingsActivity, next)
                toast("背景蒙层已设为「" + WallpaperStore.scrimLabel(next) + "」，返回桌面生效")
            }
        }
        card(body, "夜间自动息屏") {
            switchRow(
                this, NightScreen.KEY_ENABLED, "夜间自动息屏",
                "桌面在前台且车灯亮时，计时到点直接调原厂背光息屏（与下拉栏那颗「息屏」同一条指令）；" +
                    "关灯 / 离开桌面 / 回到桌面都会立刻恢复亮度。不做触摸检测，也不加悬浮层"
            )
            segRow(
                this, NightScreen.KEY_DELAY, "息屏延时",
                "从回到桌面开始计时；中途去导航再回来会重新计",
                listOf("15" to "15 秒", "30" to "30 秒", "60" to "1 分", "120" to "2 分")
            )
            actionRow(
                this, "试一次息屏",
                NightScreen.channelHint() + "；点一下立刻息屏、3 秒后自动恢复（车机上才有反应）",
                "试一下"
            ) {
                val ok = NightScreen.preview(this@SettingsActivity)
                toast(
                    if (ok) "已下发息屏，3 秒后自动恢复"
                    else "息屏下发失败：车控服务没连上（手机上没有这个服务，属正常）"
                )
            }
            sigRow(this, "当前状态") { s -> NightScreen.status(s, snap) }
        }
        card(body, "停靠栏") {
            switchRow(this, "sidebar.dockCard", "整条底板", "关 = 只有图标浮在桌面上")
            switchRow(this, "sidebar.showClock", "显示时钟", "停靠栏顶部显示时间；关闭后时间回到状态栏")
            switchRow(
                this, "sidebar.homeOn", "覆盖原厂导航栏",
                "打开后用悬浮层盖住原车左侧竖栏，换成 时钟 + 图标 + 「原车桌面」按钮。" +
                    "需要「显示在其他应用上层」权限 + 无障碍服务（压在最上层靠的就是无障碍的悬浮窗口）",
                onWrite = { on ->
                    // 用显式值起停，不回读配置：刚写进 DataStore 的快照还没回流，
                    // 回读会拿到旧的 false，结果就是"开关点开了、什么都没发生"。
                    if (!on) {
                        NavOverlay.apply(this@SettingsActivity, false)
                        toast("已关闭，桌面恢复自带停靠栏")
                    } else if (!NavOverlay.canDraw(this@SettingsActivity)) {
                        // 没权限就把开关退回关闭，别让界面显示"开"而实际什么都没有
                        write("sidebar.homeOn", "false")
                        toast("需要先授权「显示在其他应用上层」，授权后回来再打开")
                        NavOverlay.openPermissionPage(this@SettingsActivity)
                    } else {
                        NavOverlay.apply(this@SettingsActivity, true)
                        if (H6A11y.enabledInSystem(this@SettingsActivity)) {
                            toast("已开启：返回桌面即可看到覆盖层（桌面自带的停靠栏会隐藏）")
                        } else {
                            // 没有无障碍就挂不上 2032，覆盖层会被原厂竖栏压住 —— 必须说清楚
                            toast(
                                "已开启，但还差一步：请开启下面的「无障碍服务」，" +
                                    "否则悬浮层压不住原厂竖栏（只能用应用级窗口，会被原厂栏盖住）"
                            )
                        }
                    }
                }
            )
            // 覆盖层到底有没有真的挂上去，这一行直接给结论；出问题时它就是诊断结果
            actionRow(
                this, "覆盖层状态", NavOverlay.diagnose(this@SettingsActivity), "刷新"
            ) {
                NavOverlay.sync(this@SettingsActivity)
                toast(NavOverlay.describe(this@SettingsActivity))
                rebuild(0)
            }
            // 分不清"我们没挂上"还是"挂上了但被原厂竖栏压住"时，点这个挂一块红色测试层
            actionRow(
                this, "测试悬浮层",
                "点一下在左侧挂一块红色方块 5 秒：出现且盖住原厂竖栏＝悬浮层可用；被竖栏压住＝层级不够；不出现＝系统不给",
                "测试"
            ) {
                toast(NavOverlay.testOverlay(this@SettingsActivity))
            }
            actionRow(
                this, "悬浮窗权限",
                if (NavOverlay.canDraw(this@SettingsActivity))
                    "已授权，覆盖层可以显示在导航 / 音乐等应用上面"
                else
                    "未授权，覆盖层开不起来；点右侧去系统页打开「显示在其他应用上层」",
                if (NavOverlay.canDraw(this@SettingsActivity)) "已授权" else "去授权"
            ) {
                NavOverlay.openPermissionPage(this@SettingsActivity)
            }
            // 无障碍服务：覆盖层的 2032 窗口、前台判定、顶栏下滑、方向盘按键都靠它
            val a11yOn = H6A11y.enabledInSystem(this@SettingsActivity)
            a11yWasOn = a11yOn
            actionRow(
                this, "无障碍服务",
                if (a11yOn)
                    "已开启。四件事都靠它：① 覆盖原厂导航栏时用 2032 悬浮窗压在原厂竖栏之上" +
                        "（没开就只能用应用级窗口，会被竖栏盖住）；② 覆盖层准确判断前台应用；" +
                        "③ 顶部向下划时把原车状态栏一步拉下来；④ 方向盘按键映射在任意界面生效"
                else
                    "未开启。① 覆盖原厂导航栏的悬浮层只能挂应用级窗口，会被原厂竖栏压住 → " +
                        "这个必须开才能盖住原厂栏；② 覆盖层只能靠界面生命周期猜前后台，切换时会闪一下；" +
                        "③ 顶部下滑只能先露出系统栏、再拉第二次；④ 方向盘键只在桌面 / 设置页生效。" +
                        "去系统页勾选「哈弗国潮桌面·前台感知」——它不读窗口内容，" +
                        "只在学习模式和命中绑定键时接管按键",
                if (a11yOn) "已开启" else "去开启"
            ) {
                H6A11y.openSettings(this@SettingsActivity)
            }
            // 每一格都能在这里换：桌面上长按格子和这里改是同一份数据（SharedPreferences dock_slots）
            val cnt = (snap?.int("sidebar.phCount", 6) ?: 6).coerceIn(1, 8)
            for (i in 0 until cnt) {
                actionRow(this, "第 ${i + 1} 格", describeSlot(i), "更换") {
                    DockPicker.show(this@SettingsActivity, i, cnt) { value ->
                        DockSlots.put(this@SettingsActivity, i, value)
                        toast("已保存，返回桌面即生效")
                    }
                }
            }
        }
    }

    private fun pageFeature(body: LinearLayout) {
        card(body, "第 2 页") {
            segRow(
                this, "page2.tireUnit", "胎压单位", "kPa / bar / psi 三选一",
                listOf("0" to "kPa", "1" to "bar", "2" to "psi")
            )
            // 温度卡、油量卡、保养卡都删了，两个"互换"开关随之失效，不再展示
            segRow(
                this, "page2.tireStyle", "胎压呈现", "车模四轮标注 / 纯文字",
                listOf("car" to "车模", "text" to "文字")
            )
            segRow(
                this, "page2.carScalePct", "车模缩放", "100% 即铺满胎压卡（天花板约 120%）",
                listOf("90" to "90%", "100" to "100%", "110" to "110%", "120" to "120%")
            )
        }
        // 应用页（桌面第 2 页）：勾选哪些就显示哪些；一个都没勾 = 显示全部已安装应用
        val apps = DockSlots.listApps(this@SettingsActivity)
        val selNow = parseAppSel()
        card(body, "桌面应用（第 2 页）") {
            segRow(
                this, "appPage.perRow", "每行个数", "桌面第 2 页整页应用墙的列数；行数按高度自动排，多了可以上下滚",
                listOf("4" to "4 个", "5" to "5 个", "6" to "6 个", "7" to "7 个")
            )
            actionRow(
                this, "显示全部",
                "读到的已安装应用：${apps.size} 个（不含桌面自己）",
                "清空自选"
            ) {
                write("appPage.sel", "")
                toast("已设为显示全部应用")
            }
            if (apps.isEmpty()) {
                infoRow(this, "已安装应用") { "没读到（检查系统权限）" }
            } else {
                val cur = ArrayList<String>()
                cur.addAll(if (selNow.isEmpty()) apps.map { DockSlots.key(it) } else selNow)
                // 默认收起：128 个应用一行一个，全展开会把这一页撑到十几屏
                foldRow(
                    this, "应用列表",
                    "${apps.size} 个应用，默认收起；展开后逐个设置显示 / 隐藏",
                    "展开", "收起"
                ) { box ->
                    for (a in apps) appToggleRow(box, a, cur)
                }
            }
        }
        card(body, "车身") {
            segRow(
                this, "window.mode", "车窗判定", "自动 = 有车身信号走信号，否则读状态位",
                listOf("auto" to "自动", "car" to "车身信号", "status" to "状态位")
            )
            segRow(
                this, "window.sunroofOpenMin", "天窗开启阈值", "超过该开度才算打开",
                listOf("0" to "0%", "3" to "3%", "6" to "6%", "10" to "10%", "15" to "15%")
            )
            switchRow(this, "vehicle.enabled", "车辆数据读取", "关掉后卡片显示占位符（排查用）")
        }
        // 权限激活：照「应用管家」的 ActivityPermissions，一项一行、点一下去授权
        card(body, "权限激活") {
            actionRow(
                this, "特殊权限",
                "无障碍 / 悬浮窗 / 电池优化 / 安装应用 / 系统设置 / adb 项，一处看全；" +
                    "进去点「一键授权全部」，剩下的桌面自己走无线 adb 补齐", "去激活"
            ) {
                PermPanel.show(this@SettingsActivity)
            }
            infoRow(this, "已激活") { PermGuide.summary(this@SettingsActivity) }
            infoRow(this, "未激活项") { PermGuide.missingNames(this@SettingsActivity) }
        }
        // 自动化：悬浮球 / 侧边手势 / 按键映射 / 开机任务（6.0.47）
        card(body, "自动化") {
            switchRow(
                this, "auto.floatOn", "悬浮按钮",
                "单击 = 返回键，长按 = 主页键；可拖动换位置", { syncAuto() }
            )
            actionRow(this, "悬浮按钮位置", "跑到屏幕外或挡住内容时点这里复位", "复位") {
                FloatButton.resetPos(this@SettingsActivity)
                FloatButton.sync(this@SettingsActivity)
                toast("悬浮按钮已回到右下角")
            }
            actionRow(
                this, "平时不透明度",
                "静止时半透明、手指按住变实心；点一下在 35% / 45% / 60% / 80% / 100% 之间切换",
                "调整"
            ) {
                val cur = snap?.int("auto.floatAlpha", 45) ?: 45
                val next = when {
                    cur < 40 -> 45
                    cur < 52 -> 60
                    cur < 70 -> 80
                    cur < 90 -> 100
                    else -> 35
                }
                write("auto.floatAlpha", next.toString())
                syncAuto()
                toast("悬浮球平时不透明度 $next%")
            }
            actionRow(
                this, "悬浮按钮大小",
                "6.0.105 起默认放大到 112dp（原来 56dp）；点一下在 72 / 88 / 112 / 136 / 160dp 之间切换",
                "调整"
            ) {
                val cur = snap?.int("auto.floatSize", 112) ?: 112
                val next = when {
                    cur < 80 -> 88
                    cur < 100 -> 112
                    cur < 124 -> 136
                    cur < 148 -> 160
                    else -> 72
                }
                write("auto.floatSize", next.toString())
                syncAuto()
                toast("悬浮球直径已设为 ${next}dp")
            }
            switchRow(
                this, "auto.edgeOn", "侧边手势",
                "左/右缘横滑 = 返回键，左缘下滑 = 状态栏，底缘上滑 = 回主页", { syncAuto() }
            )
            switchRow(this, "auto.edgeLeft", "左缘右滑 → 返回键", "另：左缘下滑仍可拉状态栏", { syncAuto() })
            switchRow(this, "auto.edgeRight", "右缘左滑 → 返回键", null, { syncAuto() })
            switchRow(this, "auto.edgeBottom", "底缘上滑 → 回主页", null, { syncAuto() })
            actionRow(
                this, "滑动距离", "越大越不容易误触；点一下在 24 / 36 / 48 / 60dp 之间切换", "调整"
            ) {
                val cur = snap?.str("auto.swipeMin", "36")?.toIntOrNull() ?: 36
                val next = when {
                    cur < 30 -> 36
                    cur < 42 -> 48
                    cur < 54 -> 60
                    else -> 24
                }
                write("auto.swipeMin", next.toString())
                syncAuto()
                toast("滑动距离已设为 ${next}dp")
            }
            switchRow(
                this, "auto.keyOn", "按键映射",
                "把方向盘 / 面板上的键映射成返回、切歌、开应用等动作", { syncAuto() }
            )
            actionRow(this, "映射列表", "学习按键 → 选单击 / 长按 → 选动作", "编辑") {
                AutoPanels.keyMap(this@SettingsActivity) { syncAuto() }
            }
            switchRow(
                this, "auto.bootOn", "开机任务",
                "开机后按设定延迟自动打开应用；延迟可设 0 ~ 3600 秒"
            )
            actionRow(this, "任务列表", "点已有任务可改延迟；支持「立即试跑」", "编辑") {
                AutoPanels.bootTask(this@SettingsActivity) { syncAuto() }
            }
            infoRow(this, "条目统计") { AutoPanels.summary(this@SettingsActivity) }
            infoRow(this, "悬浮按钮") { FloatButton.describe(this@SettingsActivity) }
            infoRow(this, "侧边手势") { EdgeGesture.describe(this@SettingsActivity) }
            infoRow(this, "上次开机任务") {
                BootTask.lastResult(this@SettingsActivity).ifEmpty { "还没跑过" }
            }
        }
    }

    /**
     * 自动化开关改完之后重新挂一次悬浮球 / 手势条。
     *
     * 配置写入是异步落盘的（写完还要回流到 `snap`），所以延一拍再读；
     * 不延的话会读到旧值，表现为「开关动了但没反应」。
     */
    private fun syncAuto(delayMs: Long = 300L) {
        lifecycleScope.launch {
            delay(delayMs)
            runCatching {
                FloatButton.sync(this@SettingsActivity)
                EdgeGesture.sync(this@SettingsActivity)
            }
        }
    }

    private fun pageVehicle(body: LinearLayout) {
        // 没有 ICarInfo 的手机上验证车窗琥珀色 / 车门开合动画的调试入口
        simCard(body)
        card(body, "车身信号") {
            sigRow(this, "数据源") { s ->
                when (s.source) {
                    "binder" -> "ICarInfo ✔"
                    "binder-down" -> "ICarInfo 已断开"
                    "gps" -> "GPS 兜底"
                    "mock" -> "配置静态模拟"
                    "sim" -> "全车模拟数据 ✔"
                    else -> "未连接"
                }
            }
            sigRow(this, "模拟范围") { s ->
                when {
                    !s.simActive -> "关 · 全部走真实车身信号"
                    s.simAll -> "整车数据 + 门窗（关闭后原样交还真实信号）"
                    else -> "仅门窗"
                }
            }
            sigRow(this, "四窗 / 天窗") { s ->
                if (s.windowPos.isEmpty()) "--"
                else s.windowPos.take(4).joinToString("/") {
                    if (it in 2..98) "开" else "关"
                } + " · 顶 " + if (s.sunroof in 6..94) "开" else "关"
            }
            sigRow(this, "车速") { s -> "${s.speedKmh} km/h" }
            sigRow(this, "挡位 / 驾驶模式") { s -> "${s.gear} / ${modeName(s.driveMode)}" }
            sigRow(this, "大灯 / 昼夜") { s ->
                when (s.night) {
                    1 -> "夜间（大灯已开）"
                    0 -> "白天（大灯已关）"
                    else -> "未读到 · 夜间主题按时间兜底"
                }
            }
            sigRow(this, "高德昼夜同步") { AmapNightSync.lastReport }
            sigRow(this, "水温 / 变速箱油温") { s -> "${fmtC(s.coolantC)}℃ · ${fmtC(s.atfC)}℃" }
            sigRow(this, "胎压") { s ->
                if (s.tireKpa.isEmpty() || s.tireKpa.all { it <= 0 }) "--"
                else s.tireKpa.joinToString(" / ") { if (it > 0) "$it" else "-" } + " kPa"
            }
            sigRow(this, "胎温") { s ->
                // 有效判据与卡片一致：> -40℃ 才算读到（-1 = 该轮属性没值）
                if (s.tireTempC.isEmpty() || s.tireTempC.all { it <= -40 }) "--"
                else s.tireTempC.joinToString(" / ") { if (it > -40) "$it" else "-" } + " ℃"
            }
            sigRow(this, "胎压属性原始值") { CarBinderSource.tireDebug() }
            sigRow(this, "电压 / 电量 / 硫化率") { s ->
                join(
                    if (s.volt in 8f..20f) String.format(Locale.CHINA, "%.1fV", s.volt) else "--",
                    if (s.battPct in 0..100) "${s.battPct}%" else "--",
                    if (s.sulfurPct in 0..100) "${s.sulfurPct}%" else "--"
                )
            }
            sigRow(this, "硫化率原始值") { CarBinderSource.sulfurDebug() }
            // 6.0.31：其它车型 CAN 量纲不同，把这行原始值截图发回来即可针对性适配
            sigRow(this, "状态栏原始值（车型适配）") { CarBinderSource.signalDebug() }
            // 6.0.31：ICarInfo 读不到时依次走 平台car.* / 车厂SPI / 标准android.car
            sigRow(this, "Car API 兜底") { CarApiFallback.debug() }
            sigRow(this, "数据来源") { s -> s.source }
            // v6.0.45：长时间运行的自检入口。静态读「堆 / 线程 / 运行时长 / 图标缓存」，
            // 帧率按需采样 3 秒（不常驻，静止桌面不该为此每帧被唤醒）。
            actionRow(
                this, "运行时状态",
                PerfStats.summary() + " \u00b7 图标 " + AppIconCache.stats(),
                "测帧率"
            ) {
                PerfStats.probeFrames(3000L) { fps ->
                    val head = if (fps <= 0) "3 秒内没有出帧（桌面静止）" else "实测 ${fps}fps"
                    android.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("运行时状态")
                        .setMessage(
                            head + "\n\n" + PerfStats.summary() +
                                "\n图标缓存 " + AppIconCache.stats() +
                                "\n\n注：帧率采样期间请保持桌面有动画（滑动翻页或看车模开门）"
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            }
            sigRow(this, "续航 / 油量") { s ->
                join(
                    if (s.rangeKm in 0..2000) "${s.rangeKm}km" else "--",
                    if (s.fuelLiters in 0f..150f) "${s.fuelLiters.toInt()}L" else "--"
                )
            }
            sigRow(this, "总里程") { s -> if (s.mileageKm in 0..999999) "${s.mileageKm} km" else "--" }
            sigRow(this, "车门 前左/前右/后左/后右/尾门") { s ->
                if (s.doors.isEmpty()) "--"
                else s.doors.joinToString("/") { when (it) { 1 -> "开"; 0 -> "关"; else -> "-" } }
            }
            sigRow(this, "4G / WiFi") { s -> "${s.signalLevel} / ${s.wifiLevel}" }
            actionRow(this, "刷新以上读数", "实车装完先看这一页，哪条通道没通一眼可见", "刷新") {
                refreshSignals()
            }
        }
        // 6.0.62：车灯延时。
        // 原厂「伴我回家」在车辆设置里最低只给 30 秒、界面上根本没有"关"这一档，
        // 所以这里绕过原厂界面，直接往车设项（CARSETTINGS 0x1A）写 0 秒，写完回读 ——
        // 能不能真的关掉由 MCU 值域决定，回读结果才是唯一可信的答案。
        // 读值在进页面时取一次（infoRow 的 lambda 会被 refresh 反复调用，不能在里面做 Binder 往返）。
        card(body, "车灯延时") {
            val fh = CarBinderSource.followHomeDelay()
            val roof = CarBinderSource.roofLightDelay()
            infoRow(this, "伴我回家") { delayLabel(fh) }
            infoRow(this, "顶灯延时") { delayLabel(roof) }
            actionRow(
                this, "关闭伴我回家延时",
                "原厂最低只给一档、没有「关」。这里直接写 0 再回读确认：车机不接受就显示实际值",
                "写 0"
            ) { writeFollowHome(0) }
            // 恢复写的是**进这页时读到的原值**，不硬编码 30 ——
            // 这个 item 到底按"秒"还是按"档位索引"存，静态看不出，照原值写回最安全。
            if (fh != null && fh > 0) {
                actionRow(
                    this, "恢复原值（$fh）",
                    "进这页时读到的原值。想改回去点这里，写入同样回读确认",
                    "恢复"
                ) { writeFollowHome(fh) }
            } else {
                infoRow(this, "恢复原值") { "进页面时没读到原值，改回去只能走原厂车辆设置" }
            }
        }
        card(body, "驾驶模式") {
            sigRow(this, "当前模式") { s -> modeName(s.driveMode) }
            modeRow(this)
            modeMapRow(this)
        }
        // 自动启停默认值：冷启动 9 秒后按此设定下发一次，已是目标状态就不动。
        card(body, "自动启停") {
            // infoRow 的 lambda 会被 refresh 反复调用，Binder 往返只在进页面时做一次
            val ssState = StartStop.stateText(this@SettingsActivity)
            infoRow(this, "当前启停状态") { ssState }
            // 模式三选一（用自己的 SP，不走配置树）：未解锁时点击先弹验证
            val r = row(this, "启动后默认", "每次启动 9 秒后按此设定下发一次；已是该状态就不动")
            val host = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = round(cSegBg, dp(20))
            }
            val opts = listOf(
                StartStop.MODE_NONE to "保持原样",
                StartStop.MODE_OFF to "自动关闭",
                StartStop.MODE_ON to "自动开启"
            )
            val segs = ArrayList<TextView>(opts.size)
            for (o in opts) {
                val tvSeg = text(o.second, 22f, cSub, false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    isClickable = true
                    setOnClickListener {
                        StartStop.setMode(this@SettingsActivity, o.first)
                        paintStartStopSegs(segs, opts.map { it.first }, StartStop.mode(this@SettingsActivity), cAccent, cSub, cSegBg)
                    }
                }
                segs += tvSeg
                host.addView(tvSeg)
            }
            r.addView(host)
            paintStartStopSegs(segs, opts.map { it.first }, StartStop.mode(this@SettingsActivity), cAccent, cSub, cSegBg)
            actionRow(
                this, "立即下发一次",
                "按当前模式马上执行一次（跳过「已是目标状态」判断），用来确认车控写通道通不通",
                "执行"
            ) {
                StartStop.applyMode(this@SettingsActivity, force = true) { ok, msg ->
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                    if (ok) rebuild(2)
                }
            }
        }
        // 6.0.103：熄火自动关窗。车窗只有原厂语音那条请求通道，没有读入口也没有返回值，
        // 所以默认关、先「试关窗」标定值域，执行后一律回读开度裁决。
        card(body, "熄火自动关窗") {
            switchRow(
                this, AutoClose.K_ON, "总开关",
                "熄火（可要求同时已锁车）后自动关四窗。默认关：这是原厂语音那条请求通道，" +
                    "先点下面「试关窗」确认这台车认，再打开总开关"
            )
            switchRow(
                this, AutoClose.K_ROOF, "天窗一起关",
                "打开后连天窗一起下发；遮阳帘不动（那是另一条条目，暂不碰）。" +
                    "★ 天窗的编号表可能和车窗不是同一张，第一次打开这个开关请站在车边看 —— " +
                    "天窗若反而打开，先把它关掉"
            )
            segRow(
                this, AutoClose.K_TRIGGER, "触发条件",
                "「熄火 + 锁车」两种先后都认。注意：熄火后车机常会断电，锁车信号那时可能已经读不到，" +
                    "所以「仅熄火」是最跑得通的一档；「仅锁车」要求车速为 0 且发动机已停",
                listOf(
                    AutoClose.TRIGGER_BOTH to "熄火+锁车",
                    AutoClose.TRIGGER_ENGINE to "仅熄火",
                    AutoClose.TRIGGER_LOCK to "仅锁车"
                )
            )
            segRow(
                this, AutoClose.K_DELAY, "关窗前延时",
                "6.0.105 起默认改成立即：熄火后车机往往几秒内就断电，等 15 秒等于等不到。" +
                    "如果发现「熄火但人还在车里想开窗」被误关，再调回 15 秒" +
                    "（选「熄火+锁车」时延时太短可能锁车信号还没上来）",
                listOf("0" to "立即", "15" to "15秒", "30" to "30秒", "60" to "60秒")
            )
            infoRow(this, "车窗关窗值") { s ->
                val v = AutoClose.closeValue(s)
                if (v < 0) "未标定 —— 点下面「自动找关窗值」" else "已标定：value = $v（实车试出来的）"
            }
            actionRow(
                this, "自动找关窗值",
                "★ 6.0.107 实车发现：这条通道的入参是「动作编号」不是开关 —— " +
                    "0 = 四窗全降、1 = 只有主驾降，所以以前不管选 0 还是 1，窗户都只会往下走。" +
                    "点这里让程序自己试（四窗先降下来再点），最多一分钟，窗会自己动，别碰门上的开关；" +
                    "找到「上升」那个编号会记下来，熄火自动关窗就用它",
                "开始"
            ) {
                Toast.makeText(this@SettingsActivity, "开始试，最多一分钟，窗会自己动", Toast.LENGTH_LONG).show()
                AutoClose.calibrate(this@SettingsActivity) { msg ->
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    rebuild(2)
                }
            }
            switchRow(
                this, AutoClose.K_LOCK_INV, "锁车读数反相",
                "默认「大于 0 = 已锁」。如果下面那行显示「原始值 0 → 未锁」但车其实锁着，就把这个打开"
            )
            sigRow(this, "锁车读数") { s -> AutoClose.lockText(s) }
            infoRow(this, "最近一次判定") { AutoClose.lastLog() }
            actionRow(
                this, "试关窗",
                "立刻按上面设置下发一次（不等熄火），站在车边看四窗动不动；" +
                    "5 秒后回读开度，结果写进「最近一次判定」",
                "执行"
            ) {
                Toast.makeText(this@SettingsActivity, "已下发，5 秒后看结果", Toast.LENGTH_SHORT).show()
                AutoClose.testClose(this@SettingsActivity) { msg ->
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    rebuild(2)
                }
            }
        }
        card(body, "高德导航信息") {
            switchRow(
                this, "navi.enabled", "总开关",
                "读取高德地图车机版发出的红绿灯 / 车道广播，显示在车速卡下方的独立卡片里；" +
                    "其他应用在前台时改成可拖动的悬浮窗。高德自己在前台时不显示（看高德界面）。" +
                    "★ 6.0.34 新增，默认开。没装高德车机版就一直空着，不影响其它功能",
                onWrite = { NavInfoOverlay.sync(this@SettingsActivity) }
            )
            switchRow(
                this, "navi.light", "红绿灯",
                "倒计时 + 方向箭头；剩余 5 秒以内整块闪烁。导航态和巡航态高德用的是两套编号，已分别处理。" +
                    "★ 6.0.105 修掉了「巡航态一直不显示红绿灯」—— 原来「路口静默」在巡航态会误触发且退不出来",
                onWrite = { NavInfoOverlay.sync(this@SettingsActivity) }
            )
            segRow(
                this, "navi.lightMaxDis", "红绿灯距离门控",
                "★ 6.0.55：默认 300 米。距离超过档位就判为「下一个路口」的灯、直接不显示；" +
                    "车正在通过路口时（高德提示「现在」）整段静默，直到驶离才重新收灯。" +
                    "★ 6.0.105：这条「路口静默」只在**导航态**生效 —— 巡航态高德不给分段距离，" +
                    "以前一进静默就再也退不出来，巡航红绿灯因此永远不显示。" +
                    "「距灯 XXX米」只认带单位的米数，不会再出现「距灯 现在」。巡航态没给距离时不门控。" +
                    "★ 选「关闭」= 距离门控和路口静默一起关，完全退回 6.0.52 的行为；" +
                    "觉得当前红灯被挡多了就调大一档或关闭",
                listOf("0" to "关闭", "100" to "100米", "300" to "300米", "800" to "800米")
            )
            switchRow(
                this, "navi.lane", "车道",
                "车道箭头按高德给的编号取图（共 55 种）；高德建议走的车道会半透明呼吸",
                onWrite = { NavInfoOverlay.sync(this@SettingsActivity) }
            )
            switchRow(
                this, "navi.overlay", "其他应用前台时悬浮显示",
                "★ 要盖在导航 / 音乐上面：开了无障碍服务就直接用 2032 无障碍窗口，不需要额外权限；" +
                    "没开无障碍则要有「显示在其他应用上层」权限，否则挂不上。" +
                    "★ 6.0.35 起这块小窗可以用手指直接拖到任意位置，位置会被记住。" +
                    "★ 6.0.105 修掉「切应用闪一下就消失」：我们自己挂的悬浮窗也算「前台=本应用」，" +
                    "以前会把自己判成切回桌面、立刻摘窗；现在改为延迟 1.2 秒复查再决定",
                onWrite = { NavInfoOverlay.sync(this@SettingsActivity) }
            )
            actionRow(
                this, "悬浮窗位置",
                "悬浮窗被拖到屏幕边上、或者想让它回到右上角默认位置，点右边按钮复位",
                "复位"
            ) {
                NavInfoOverlay.resetPos(this@SettingsActivity)
            }
            infoRow(this, "广播状态") { AmapNavi.describe() }
            infoRow(this, "悬浮窗") { NavInfoOverlay.describe(this@SettingsActivity) }
            infoRow(this, "原始诊断") { AmapNavi.diagnose(this@SettingsActivity) }
            actionRow(
                this, "刷新以上三行",
                "先在后台把高德车机版开起来开始导航，再回来看这里：一直是「0 条」就是没收到广播",
                "刷新"
            ) {
                rebuild(2)
            }
        }
        card(body, "方向盘切换驾驶模式") {
            val a11yOn = H6A11y.enabledInSystem(this@SettingsActivity)
            val bound = SteerKey.keyCode(this@SettingsActivity)
            actionRow(
                this, "启用开关",
                "★ 改成长按触发：按住学过的方向盘键够下面设定的时长，才在「标准 ↔ 运动」之间切换；" +
                    "短按不切换，避免误触。一次长按只切一次，长按连发不会来回横跳。" +
                    "当前是经济 / 读不到车况时，第一次长按先切到运动。" +
                    "★ 绑定后该键的短按原车功能也会被接管（不再生效），不想要就关掉或清除绑定。",
                if (SteerKey.enabled(this@SettingsActivity)) "开" else "关"
            ) {
                SteerKey.setEnabled(this@SettingsActivity, !SteerKey.enabled(this@SettingsActivity))
                rebuild(2)
            }
            actionRow(
                this, "长按时长",
                "当前 " + SteerKey.holdLabel(this@SettingsActivity) +
                    "。点「切换」在 0.5 / 0.8 / 1.2 秒之间循环；车机按键手感不一样，觉得要按太久或太容易误触就换一档",
                "切换"
            ) {
                val v = SteerKey.cycleHoldMs(this@SettingsActivity)
                toast("长按判定改为 " + "%.1f 秒".format(v / 1000f))
                rebuild(2)
            }
            actionRow(
                this, "按键学习",
                if (bound >= 0)
                    "已绑定 ${SteerKey.keyLabel(bound)}。点「重新学」再长按一次方向盘键即可换键；" +
                        "键值由应用自己检测，不用提前知道键码（参考应用管家的按键映射）"
                else
                    "还没绑定。点「开始学」后长按方向盘键约 1 秒，应用会读出并显示 keyCode，确认后保存。",
                if (bound >= 0) "重新学" else "开始学"
            ) {
                SteerKeyPanel.show(this@SettingsActivity) { rebuild(2) }
            }
            actionRow(
                this, "无障碍服务（全局生效）",
                if (a11yOn)
                    "已开启：在导航 / 音乐 / 桌面等任意界面长按方向盘键都能切换。" +
                        "★ 从旧版升级上来的，请把系统无障碍开关关一次再打开，按键过滤才会重新生效。" +
                        "服务只在学习模式和命中绑定键时接管按键，音量 / 返回 / 切歌照旧"
                else
                    "未开启：目前只有桌面与设置页在前台时长按有效。" +
                        "想去任意界面都能按，请到系统无障碍页勾选「哈弗国潮桌面·前台感知」",
                if (a11yOn) "已开启" else "去开启"
            ) {
                H6A11y.openSettings(this@SettingsActivity)
            }
            actionRow(
                this, "试切一次",
                "不用按方向盘键，直接下发一次标准 ↔ 运动，用来确认车控写通道通不通（手机上会提示没连上）",
                "切换"
            ) {
                toast(SteerMode.toggle(this@SettingsActivity))
                rebuild(2)
            }
            actionRow(this, "上次按键结果", SteerKey.lastResult(this@SettingsActivity), "刷新") {
                rebuild(2)
            }
        }
        // ---- 空调内外循环（6.0.100）----
        card(body, "空调内外循环") {
            infoRow(this, "当前状态") { Hvac.stateText(this@SettingsActivity) }
            infoRow(this, "车控读数") {
                runCatching { CarBinderSource.hvacDebug() }.getOrElse { "读取失败" }
            }
            infoRow(this, "可用通道") { Hvac.channelText(this@SettingsActivity) }
            actionRow(
                this, "通道自检",
                "点了没反应时用这里：逐个通道下发并回读，哪个能让车上循环灯变就是那条。" +
                    "状态真变了会自动记住，之后图标和方向盘按键直接用它。",
                "自检"
            ) {
                AcProbeDialog.show(this@SettingsActivity)
            }
            actionRow(
                this, "切一次",
                "直接对着空调模块下发一次内 ↔ 外循环，用来确认这条通道通不通。" +
                    "下发后会回读 400ms，以实车回报为准；手机上会提示没连上。",
                "切一次"
            ) {
                Hvac.toggleAsync(this@SettingsActivity) { msg -> toast(msg) }
            }
            actionRow(
                this, "方向盘按键",
                "想用方向盘按键切循环：到「自动化 → 按键映射」里添加一条，" +
                    "长按学一颗键（建议选**长按**，短按不占原车功能），动作选「空调内外循环」。" +
                    "主页车模右下角那枚图标也是同一个动作。",
                "去按键映射"
            ) {
                AutoPanels.keyMap(this@SettingsActivity) { rebuild(2) }
            }
            actionRow(
                this, "数值反相",
                if (Hvac.inverted(this@SettingsActivity))
                    "当前：反相（发 0 = 内循环）。点一下恢复默认（发 1 = 内循环）"
                else
                    "当前：默认（发 1 = 内循环）。如果车上切反了（点「内循环」反而变外循环），点一下翻过来",
                if (Hvac.inverted(this@SettingsActivity)) "已反相" else "默认"
            ) {
                val v = Hvac.toggleInverted(this@SettingsActivity)
                toast(if (v) "内循环值域已反相" else "已恢复默认值域")
                rebuild(2)
            }
        }
        card(body, "状态栏显示") {
            // 续航 / 驾驶模式默认关（用户 2026-11-10 要求），点开也还能加回来
            switchRow(this, "statusItems.range", "续航", null)
            switchRow(this, "statusItems.fuel", "油量", null)
            switchRow(this, "statusItems.water", "水温", null)
            switchRow(this, "statusItems.atf", "变速油", null)
            switchRow(this, "statusItems.volt", "电压", null)
            switchRow(this, "statusItems.batt", "电量", null)
            // 显示名跟状态栏一致（2026-11-15 改叫「电瓶寿命」）；key 名不动，老配置照样生效
            switchRow(this, "statusItems.sulfur", "电瓶寿命", null)
            switchRow(
                this, "statusItems.mode", "驾驶模式",
                "关掉后状态栏不显示「标准 / 运动 / 经济」，腾出的位置给右侧车况读数放大字号"
            )
        }
        // ---- 音乐卡片（6.0.71）----
        // 位置在机油卡中间那条（机油块与储存块之间）。数据来自 MediaSession，
        // 因此必须先拿到「通知使用权」：MEDIA_CONTENT_CONTROL 是 signature|privileged，
        // pm grant 授不了，只有"已启用的通知监听器"这一条放行路径（嘟嘟桌面同样做法）。
        card(body, "音乐卡片") {
            sigRow(this, "通知使用权") {
                if (MediaWatcher.isGranted(this@SettingsActivity)) "已开启" else "未开启"
            }
            sigRow(this, "当前播放") {
                val np = MediaWatcher.current()
                when {
                    np == null || np.title.isEmpty() -> "没有在播放"
                    np.artist.isEmpty() -> np.title
                    else -> "${np.title} — ${np.artist}"
                }
            }
            // v6.0.72：把「授权 / 服务 / 会话 / 上次拉取」四个环节摊在一行里。
            // 用户反馈"看不到卡片"时，这一行能直接指出卡在第几步，不用连 adb 抓日志。
            sigRow(this, "自检") {
                MediaWatcher.diagnose(this@SettingsActivity).lineSequence().first()
            }
            actionRow(
                this, "列出播放会话",
                "看系统里有哪些播放器上报了播放状态。state=3 才是正在播放；" +
                    "如果这里列不出来，问题在授权或播放器本身没走系统媒体会话",
                "查看"
            ) {
                val lines = MediaWatcher.sessionsDump(this@SettingsActivity)
                toast(lines.joinToString("\n"))
            }
            actionRow(
                this, "开启通知使用权",
                "安卓不允许普通应用直接读播放器。开启后桌面只取当前播放器上报的歌曲名、歌手与封面，" +
                    "不读取任何通知内容；点一下卡片会打开对应的音乐应用",
                "开启"
            ) {
                if (MediaWatcher.tryAutoGrant(this@SettingsActivity)) {
                    toast("已开启，几秒内生效")
                } else {
                    runCatching { startActivity(MediaWatcher.settingsIntent()) }
                        .onFailure { toast("请到 系统设置 → 通知 → 通知使用权 里勾选「哈弗国潮桌面·音乐卡片」") }
                }
            }
        }

        // ---- U 盘音乐（6.0.73）----
        // 车机自带的 U 盘播放器（BeanMediaPlayer / com.beantechs.mediaplayerservice）**不走
        // MediaSession**：三个包的 dex 里 MediaSession / MediaController / setPlaybackState /
        // METADATA_KEY 全为 0，对外只有私有 AIDL。所以插 U 盘放歌时上面那张卡永远是空的 ——
        // 想让它显示，只能自己放。播放引擎从旧版桌面整体移植，见 LocalPlayer。
        card(body, "U 盘音乐") {
            LocalPlayer.attach(this@SettingsActivity)
            // 6.0.74：桌面冷启动自动随机来一首。设置页可关，改完下次冷启动生效。
            sceneSwitch(
                this, "冷启动自动随机播放",
                "桌面开机起来自动从 U 盘随机挑一首开播；没插 U 盘、盘里没歌、" +
                    "或已有别的播放器在放都不动作。关掉后只在设置页或点桌面音乐条手动播放。" +
                    "改完下次冷启动生效",
                { LocalPlayer.autoPlayEnabled(this@SettingsActivity) }
            ) { on ->
                LocalPlayer.setAutoPlay(this@SettingsActivity, on)
                toast(if (on) "已开启，下次冷启动自动播放" else "已关闭自动播放")
            }
            // 6.0.74：导航播报 / 提示音 / 电话等别的通道出声时自动压低；
            // 6.0.80：播完延时 2 秒再恢复，避免一句句播报时音量反复上下跳
            // 6.0.81：延时改由 400ms 轮询持续写回 20% 驱动，对抗系统级 duck 撤销
            sigRow(this, "自动压低") { LocalPlayer.duckState() }
            sigRow(this, "扫描状态") {
                when {
                    LocalPlayer.scanningHint().isNotEmpty() -> LocalPlayer.scanningHint()
                    LocalPlayer.size() > 0 -> "已找到 ${LocalPlayer.size()} 首"
                    LocalPlayer.lastError() != null -> LocalPlayer.lastError()!!
                    else -> "还没扫描"
                }
            }
            sigRow(this, "音乐目录") {
                LocalPlayer.root().ifEmpty { "未检测到 U 盘" }
            }
            sigRow(this, "当前曲目") {
                val t = LocalPlayer.current()
                when {
                    t == null -> "没有在播放"
                    t.artist.isEmpty() -> t.title
                    else -> "${t.title} — ${t.artist}"
                }
            }
            // 6.0.104：封面读不到时先看这一行 —— 是「读取中 / 确实没有 / 已显示」一眼分明，
            // 别再靠猜。重读会清掉这首的缓存立刻再来一次（插盘那会儿没读到也能补上）。
            sigRow(this, "封面") { LocalPlayer.artText() }
            actionRow(
                this, "重读封面",
                "U 盘里的歌按「内嵌图 → 同目录同名图 → cover/folder.jpg」顺序找封面。" +
                    "插盘那一刻没读到、或者换了 U 盘，点这里重读当前这首",
                "重读"
            ) {
                toast(LocalPlayer.reloadArt())
                rebuild(2)
            }
            actionRow(
                this, "扫描 U 盘", "递归找 mp3 / flac / wav / m4a / aac / ogg 等音频，" +
                    "深度 5 层、最多 3000 首；扫完自动定位到上次听的那首", "扫描"
            ) {
                // 正常车机上 AdbGrant 已经 pm grant 过存储权限，这里只是没走那条通道时的兜底。
                // 不静默失败：弹一次系统授权，用户授权后再点扫描即可（申请是异步的）。
                val granted = checkSelfPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    runCatching {
                        this@SettingsActivity.requestPermissions(
                            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1903
                        )
                    }
                    toast("已申请存储权限，授权后再点一次扫描")
                } else {
                    toast("开始扫描…")
                    LocalPlayer.scan { list, root, err ->
                        toast(
                            when {
                                err != null -> err
                                list.isEmpty() -> "U 盘里没有音乐文件"
                                else -> "找到 ${list.size} 首 · $root"
                            }
                        )
                    }
                }
            }
            actionRow(
                this, "播放 / 暂停", "桌面机油卡中间那条也会跟着显示歌名；点那条也能暂停", "切换"
            ) {
                LocalPlayer.toggle()
                val t = LocalPlayer.current()
                toast(if (t == null) "U 盘里还没有曲目，先点上面的扫描" else t.title)
            }
            actionRow(this, "上一首 / 下一首", null, "上一首") { LocalPlayer.prev() }
            actionRow(this, "顺序往下听", "按文件名顺序切歌；想跳着听用下面那个", "下一首") {
                LocalPlayer.next()
            }
            actionRow(this, "随便听一首", "旧版桌面的下一首就是这个行为：随机挑一首", "随机") {
                LocalPlayer.shuffle()
            }
        }

        // ---- 开门自动 360（6.0.69）----
        // 用户要求：检测到车门打开 → 自动打开 360 的倒车（后视）画面 → 5 秒后自动关闭。
        // 主驾门刻意不触发（下车必开，每次都弹太吵），后备箱同样不算；
        // 开关独立于上面的「播报提醒」总开关 —— 那是声音，这是画面。
        card(body, "开门自动 360") {
            sceneSwitch(
                this, "开门自动看四周",
                "右前 / 左后 / 右后任一车门打开时自动弹出 360 后视画面，5 秒后自动关闭；" +
                    "主驾门、后备箱、R 挡和行驶中不触发",
                { SceneStore.doorAvmOn() }
            ) { on ->
                SceneStore.setDoorAvmOn(on)
                DoorAvm.note(if (on) "开门自动 360 已开启" else "开门自动 360 已关闭")
            }
            sigRow(this, "最近一次判定") { DoorAvm.lastLog() }
            actionRow(
                this, "立即试一次",
                "直接发一次「打开 360 后视」广播并在 5 秒后关闭；车机上才会真的出画面", "触发"
            ) {
                DoorAvm.testTrigger()
                toast("已发送，5 秒后自动关闭")
            }
        }
        card(body, "场景播报提醒") {
            actionRow(
                this, "播报设置",
                "车门 / 档位 / 熄火车窗提醒 · 播报音频通道 · 提示音量（默认 25%）", "打开"
            ) { VoicePanel.show(this@SettingsActivity) }
            sigRow(this, "播报通道 / 音量") { "${AudioRoute.name()} · ${SceneStore.volume()}%" }
            sigRow(this, "最近一次判定") { SceneAnnouncer.lastLog() }

            // ---- 胎压提醒（6.0.65）----
            // 放在这个卡片而不是「车辆」页：用户要求「和场景播报逻辑一样」，
            // 所以同样受上面的总开关管、同样走 wav 优先 / TTS 兜底的播报路径。
            // 阈值（6.0.68 起可改）：默认 240 / 15，判据仍只在 TireRules 一处，
            // 车模贴片、弹窗文案、播报引擎同时跟着变，不需要重启。
            sceneSwitch(
                this, "胎压过低",
                "任一轮低于阈值时弹窗 + 语音：10 秒无操作自动关闭，一次异常只提醒一次",
                { SceneStore.tireLowOn() }
            ) { on ->
                SceneStore.setTireLowOn(on)
                SceneAnnouncer.note(if (on) "胎压过低提醒已开启" else "胎压过低提醒已关闭")
            }
            sceneNumRow(
                this, "过低阈值",
                "默认 ${TireRules.DEFAULT_LOW_KPA} kPa · 回升 ${TireRules.LOW_GAP_KPA} kPa 之后" +
                    "才重新计时，防止在阈值上抖来抖去反复播报",
                TireRules.LOW_MIN_KPA, TireRules.LOW_MAX_KPA, 5,
                { TireRules.lowKpa() }, { SceneStore.setTireLowKpa(it) }
            )
            sceneSwitch(
                this, "胎压差值过大",
                "四轮最高与最低之差达到阈值时弹窗 + 语音",
                { SceneStore.tireDiffOn() }
            ) { on ->
                SceneStore.setTireDiffOn(on)
                SceneAnnouncer.note(if (on) "胎压差值提醒已开启" else "胎压差值提醒已关闭")
            }
            sceneNumRow(
                this, "差值阈值",
                "默认 ${TireRules.DEFAULT_DIFF_KPA} kPa · 回落到阈值以下 ${TireRules.DIFF_GAP_KPA} kPa 才复位",
                TireRules.DIFF_MIN_KPA, TireRules.DIFF_MAX_KPA, 5,
                { TireRules.diffKpa() }, { SceneStore.setTireDiffKpa(it) }
            )
            sigRow(this, "当前四轮") { s ->
                val v = s.tireKpa
                if (v.isEmpty()) "无读数"
                else v.joinToString(" / ") { if (it > 0) "$it" else "—" } + " kPa"
            }
            sigRow(this, "告警状态") { TireAlert.stateText() }
            actionRow(
                this, "模拟胎压告警",
                "没接车机时验证完整链路：弹窗 + 语音（10 秒自动关闭）", "触发"
            ) { TireAlert.testLow() }

            actionRow(this, "模拟熄火检查", "没接车机时也能验证熄火车窗提醒（需先开模拟信号并打开车窗）", "触发") {
                SimSignals.pulsePowerOff()
                toast("已模拟一次熄火，1.5 秒后检查车窗")
            }
        }
    }

    /**
     * 落在 SharedPreferences 上的通用开关行（读写由调用方的 get/set 决定）。
     *
     * 不能直接用 [switchRow]：那个读写的是 DataStore 里的配置树（`snap`），
     * 而场景播报的开关在 `h6_scene`、U 盘音乐的开关在 `h6_local_music` ——
     * 混用会出现「设置页显示开着，引擎读到的却是关」这种两份真相。
     * 6.0.74 起 U 盘音乐的「冷启动自动随机播放」也复用这一行。
     */
    private fun sceneSwitch(
        c: LinearLayout,
        title: String,
        desc: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        val r = row(c, title, desc)
        lateinit var tv: TextView
        fun paint() {
            val on = get()
            tv.text = if (on) "开" else "关"
            tv.setTextColor(if (on) cAccent else cSub)
        }
        tv = text("", 22f, cSub, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            isClickable = true
            setOnClickListener {
                set(!get())
                paint()
            }
        }
        r.addView(tv)
        paint()
    }

    /**
     * 「门窗模拟信号」面板。
     *
     * 车模的琥珀车窗 / 车门动画只看 `windowPos / sunroof / doors` 三个字段，
     * 手机上这三套车身服务都不存在，读数恒为空 —— 看着就像功能没做。
     * 这里直接改 [SimSignals] 的状态，信号总线每 200ms 把它盖到当前帧上，
     * 回桌面第 1 页就能看到效果；关掉后真实信号立即接管。
     */
    private fun simCard(body: LinearLayout) {
        card(body, "全车数据模拟") {
            val swOn = text("", 22f, cSub, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(8), dp(24), dp(8))
                isClickable = true
                setOnClickListener { SimSignals.toggleEnabled() }
            }
            row(
                this, "模拟信号",
                "默认关闭。开 = 用模拟数据盖住车身读数（手机无车机信号时验收整页效果）；关 = 把开启前那帧真实数据原样还回去。装车后请保持关闭"
            ).addView(swOn)

            val swAll = text("", 22f, cSub, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(8), dp(24), dp(8))
                isClickable = true
                setOnClickListener { SimSignals.toggleAll() }
            }
            row(
                this, "整车数据",
                "默认关闭。开 = 连车速 / 挡位 / 驾驶模式 / 水温 / 变速油温 / 胎压 / 电压电量 / 硫化率 / 续航油量 / 里程 / 信号一起模拟；关 = 只模拟门窗，其它读数照常走真实信号"
            ).addView(swAll)

            val swAuto = text("", 22f, cSub, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(8), dp(24), dp(8))
                isClickable = true
                setOnClickListener { SimSignals.toggleAuto() }
            }
            row(
                this, "循环演示",
                "每 2.2 秒换一组工况：驻车→起步→巡航→加速→减速→停车→倒车，数字连续爬升、车窗变琥珀、车门逐扇打开；手动点下面任意开关会退出演示"
            ).addView(swAuto)

            val grid = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            val winBtns = arrayOfNulls<TextView>(4)
            val doorBtns = arrayOfNulls<TextView>(5)
            val roofBtn: TextView

            fun cell(label: String, onClick: () -> Unit): TextView =
                text(label, 18f, cSub, false).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { onClick() }
                }

            fun gridLine(): LinearLayout {
                val line = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                grid.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                return line
            }

            fun addCell(line: LinearLayout, tv: TextView) {
                line.addView(tv, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    val m = dp(5)
                    setMargins(m, m, m, m)
                })
            }

            val line1 = gridLine()
            for (i in 0..3) {
                val b = cell(SimSignals.WINDOW_LABELS[i]) { SimSignals.toggleWindow(i) }
                winBtns[i] = b
                addCell(line1, b)
            }
            roofBtn = cell(SimSignals.ROOF_LABEL) { SimSignals.toggleRoof() }
            addCell(line1, roofBtn)

            val line2 = gridLine()
            for (i in 0..4) {
                val b = cell(SimSignals.DOOR_LABELS[i]) { SimSignals.toggleDoor(i) }
                doorBtns[i] = b
                addCell(line2, b)
            }
            this.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))

            val act = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (open in arrayOf(true, false)) {
                val b = text(if (open) "全部打开" else "全部关闭", 22f, cAccent, false).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(22), dp(8), dp(22), dp(8))
                    background = round(0, dp(18)).apply { setStroke(dp(2), cAccent) }
                    isClickable = true
                    setOnClickListener { SimSignals.setAll(open) }
                }
                act.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    rightMargin = dp(12)
                })
            }
            row(this, "一键", "手动改任意开关会自动开启模拟信号并退出循环演示").addView(act)

            val stateTv = text("", 22f, cSub, false).apply { gravity = Gravity.END }
            row(this, "状态", null).addView(stateTv, LinearLayout.LayoutParams(0, WRAP, 1f))
            sigRow(this, "当前帧") { s -> SimSignals.describe(s) }

            fun paint() {
                val st = SimSignals.panelState()
                val amber = 0xFFE08A00.toInt()
                fun tag(v: TextView, on: Boolean) {
                    v.text = if (on) "开" else "关"
                    v.setTextColor(if (on) Color.WHITE else cSub)
                    v.background = round(if (on) cAccent else cSegBg, dp(16))
                }
                tag(swOn, st.enabled)
                tag(swAuto, st.auto)
                tag(swAll, st.all)
                stateTv.text = when {
                    !st.enabled -> "未启用 · 全部走真实车身信号"
                    st.all -> "模拟中 · 整车数据 + 门窗 · ${st.label}"
                    else -> "模拟中 · 仅门窗 · ${st.label}"
                }
                stateTv.setTextColor(if (st.enabled) amber else cSub)

                fun cellPaint(v: TextView?, label: String, open: Boolean) {
                    v ?: return
                    v.text = "$label\n${if (open) "开" else "关"}"
                    v.setTextColor(if (open) Color.WHITE else cSub)
                    v.background = round(if (open) amber else cSegBg, dp(14))
                    v.alpha = if (st.enabled) 1f else 0.7f
                }
                for (i in 0..3) cellPaint(winBtns[i], SimSignals.WINDOW_LABELS[i], st.win[i])
                cellPaint(roofBtn, SimSignals.ROOF_LABEL, st.roof)
                for (i in 0..4) cellPaint(doorBtns[i], SimSignals.DOOR_LABELS[i], st.door[i])
            }
            simPaint = { paint() }
            paint()
        }
    }

    private fun pageMaintain(body: LinearLayout) {
        // 保养计划（里程 / 上次保养累计 / 机油周期）已整体删除：
        // 这里只剩「机油寿命读数 + 发动机累计运行时长」，和桌面长按机油卡同一个入口。
        card(body, "机油寿命") {
            // 只看发动机累计小时：剩余h = 固定周期 200h - 发动机累计
            sigRow(this, "剩余时间") { s -> oilLifeText(s) }
            actionRow(
                this, "发动机累计运行时长",
                "目前 ${EngineHours.text()}${if (EngineHours.isRunning()) "（运转中）" else ""} · 对齐仪表读数；桌面长按机油卡也能改",
                "修改"
            ) {
                CardDialogs.showEngineHours(this@SettingsActivity) { h, m ->
                    EngineHours.setTotal(h, m)
                    toast("已保存，桌面机油寿命已重新计算")
                    rebuild(3)
                }
            }
            infoRow(this, "更换周期") { "固定 ${OilLife.CYCLE_HOURS} 小时" }
        }
        card(body, "天气") {
            switchRow(this, "weather.enabled", "状态栏天气", "关闭后状态栏不显示天气")
            // ★ 数据来源优先原厂：直接读原车状态栏那份天气
            //   （content://com.beantechs.operatorcenterservice/weather），
            //   和原车显示完全一致；读不到才回退 GPS + 在线天气。
            infoRow(this, "数据来源") {
                WeatherSource.lastSource.ifEmpty { "还没取到，等首次同步" }
            }
            infoRow(this, "天气城市") {
                val c = snap?.str("weather.city", "") ?: ""
                if (c.isEmpty()) "自动（GPS 判到最近的区县）" else c
            }
            // ★ 天气按 GPS 定位，但定位是危险权限：没授权的话 LocationManager 直接抛异常，
            //   只能退回 IP 定位（经常落到运营商出口城市 → 报到"隔壁市"的天气）。
            //   桌面首次启动会自动问一次，这里给之后补授权 / 查看状态用的入口。
            actionRow(
                this, "定位权限",
                if (LocPerm.granted(this@SettingsActivity))
                    "已授权。注意：原厂天气读得到时根本不用定位，这条只在读不到时兜底判城市"
                else
                    "未授权。原厂天气读得到时不需要它；读不到才按配置 / IP 判城市，可能报到隔壁市",
                if (LocPerm.granted(this@SettingsActivity)) "已授权" else "去授权"
            ) {
                if (LocPerm.granted(this@SettingsActivity)) {
                    toast("已授权。天气下一轮刷新会按 GPS 重判城市")
                } else {
                    LocPerm.request(this@SettingsActivity)
                }
            }
        }
        card(body, "日志") {
            infoRow(this, "已记录") {
                val f = H6Log.file()
                if (f == null) "日志未初始化（请重启桌面）"
                else H6Log.humanBytes(H6Log.totalBytes()) + " · 自动滚动，最多 3 份共约 6MB"
            }
            infoRow(this, "存放位置") {
                H6Log.dir()?.absolutePath ?: "-"
            }
            actionRow(
                this, "导出诊断日志",
                "把运行日志 + 权限 / 车况 / 配置 / 加壳日志打成一个 txt：插着 U 盘就写 U 盘根目录，" +
                    "否则写 /sdcard/Download，导完直接发我即可（车架号自动打码）",
                "导出"
            ) {
                toast("正在打包日志…")
                Thread {
                    val r = runCatching { LogExport.export(this@SettingsActivity) }
                    runOnUiThread {
                        r.onSuccess { f ->
                            LogDialog.show(
                                this@SettingsActivity,
                                LogExport.shown(f),
                                H6Log.humanBytes(f.length())
                            )
                        }
                        r.onFailure { t ->
                            LogDialog.showFail(
                                this@SettingsActivity,
                                (t.message ?: t.javaClass.simpleName)
                            )
                        }
                    }
                }.start()
            }
            actionRow(this, "清空日志", "删掉已落盘的日志重新记；导出排查前别点", "清空") {
                Thread {
                    runCatching { H6Log.clear() }
                    runOnUiThread {
                        toast("日志已清空")
                        rebuild(3)
                    }
                }.start()
            }
        }
        card(body, "配置") {
            infoRow(this, "配置项总数") { (snap?.flat?.size ?: 0).toString() }
            infoRow(this, "用户覆盖项") { (snap?.overrides?.size ?: 0).toString() }
            infoRow(this, "配置时间戳") { s ->
                if (s.stamp <= 0L) "-"
                else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(s.stamp))
            }
            actionRow(
                this, "冷启动自动备份",
                "每次冷启动打开桌面自动存一份配置，最多保留 ${ConfigBackup.MAX_VERSIONS} 个版本（超出删最旧）；" +
                    "当前 ${ConfigBackup.list(this@SettingsActivity).size} 份 · ${AutoBackup.lastInfo(this@SettingsActivity)}",
                if (AutoBackup.enabled(this@SettingsActivity)) "开" else "关"
            ) {
                val next = !AutoBackup.enabled(this@SettingsActivity)
                AutoBackup.setEnabled(this@SettingsActivity, next)
                toast(if (next) "已开启：下次冷启动自动备份一次" else "已关闭冷启动自动备份")
                rebuild(3)
            }
            actionRow(
                this, "配置备份与恢复",
                "导出 / 导入 JSON 备份：界面全部覆盖项 + 播报、发动机累计、dock 栏、模拟信号、更新源、壁纸、方向盘按键，以及悬浮球位置、按键映射、开机任务、导航悬浮窗位置等 18 组偏好；兼容旧版备份",
                "打开"
            ) {
                BackupDialog.show(this@SettingsActivity)
            }
            actionRow(this, "快速导出配置", "直接存一份到 /sdcard/Download，不打开面板", "导出") {
                Thread {
                    val r = runCatching { ConfigBackup.export(this@SettingsActivity) }
                    runOnUiThread {
                        r.onSuccess { f -> toast("已导出到 " + ConfigBackup.shown(f)) }
                        r.onFailure { t -> toast("导出失败：${t.message}") }
                    }
                }.start()
            }
            actionRow(this, "恢复出厂配置", "清掉本页改过的全部设置，回到打包内置默认值", "恢复") {
                lifecycleScope.launch {
                    repo.resetToDefault()
                    toast("已恢复出厂配置")
                }
            }
        }
        card(body, "运行环境") {
            infoRow(this, "屏幕") {
                val dm = resources.displayMetrics
                "${dm.widthPixels}×${dm.heightPixels} @ ${String.format(Locale.CHINA, "%.2f", dm.density)}"
            }
            infoRow(this, "设计稿缩放") {
                String.format(Locale.CHINA, "k=%.2f（屏高/720）", resources.displayMetrics.density)
            }
            infoRow(this, "版本") { "${versionName()} · VC${versionCode()}" }
        }
    }

    private fun pageAbout(body: LinearLayout) {
        card(body, "版本") {
            infoRow(this, "版本名") { versionName() }
            infoRow(this, "versionCode") { versionCode().toString() }
            infoRow(this, "包名") { packageName }
        }
        card(body, "在线更新") {
            infoRow(this, "当前版本") { "${UpdateManager.curName()} · VC${UpdateManager.curCode()}" }
            infoRow(this, "更新源") { UpdateManager.baseUrl() }
            infoRow(this, "待更新") {
                val c = UpdateManager.pendingCode()
                if (c > UpdateManager.curCode()) "v${UpdateManager.pendingName()}（$c）" else "无"
            }
            actionRow(this, "检查更新", "读 version.json → 下载 APK → 交给应用管家 / 系统安装", "检查") {
                UpdateDialog.show(this@SettingsActivity)
            }
            actionRow(this, "自动检查", "桌面启动 5 秒后自动检查，有新版本会弹窗并在状态栏挂提示", if (UpdateManager.auto()) "开" else "关") {
                UpdateManager.setAuto(!UpdateManager.auto())
                rebuild(4)
            }
            infoRow(this, "安装包清理") { UpdateManager.cleanNote() }
            actionRow(this, "清理下载的安装包", "更新装完自动删掉 h6-N.apk；这里可手动清掉残留（含下载碎片）", "清理") {
                Thread {
                    val freed = runCatching { UpdateManager.cleanAllDownloads() }.getOrDefault(0L)
                    runOnUiThread {
                        toast("已清理，释放 ${Storage.fmt(freed)}")
                        rebuild(4)
                    }
                }.start()
            }
        }
        card(body, "存储清理") {
            infoRow(this, "剩余储存") { Storage.statusText() }
            infoRow(this, "安全线") {
                if (Storage.isLow()) "⚠️ 低于 1.5G，必须清理，否则将不能开机" else "1.5G · 当前充足"
            }
            actionRow(this, "立即清理", "扫描本机所有路径（内置存储 / U 盘 / 外置卡 / 系统可写区），可逐级浏览并删除任意文件", "清理") {
                FullCleanDialog.show(this@SettingsActivity) { rebuild(4) }
            }
            actionRow(this, "空间警报", "预览低于 1.5G 时的红色强警报与清理选项", "预览") {
                StorageAlert.show(this@SettingsActivity)
            }
            actionRow(this, "自动清理", "低于 1.5G 时后台自动清一次（10 分钟节流）", if (Storage.autoEnabled()) "开" else "关") {
                Storage.setAuto(!Storage.autoEnabled())
                rebuild(4)
            }
        }
        card(body, "素材与授权") {
            infoRow(this, "字体") { "MiSans 小米 · 免费商用" }
            infoRow(this, "天气图标") { "Twemoji（CC-BY 4.0）" }
            infoRow(this, "代码与素材") { "全部为本包自写 / 自绘" }
        }
        card(body, "免责声明") {
            infoRow(this, "授权") { "个人非商业使用；应用商店下载需授权码激活，禁止转售" }
        }
    }

    // ---------------- 卡片 / 行 ----------------

    private fun card(body: LinearLayout, title: String, build: LinearLayout.() -> Unit) {
        val first = body.childCount == 0
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(32), dp(24), dp(32), dp(24))
            elevation = dp(6).toFloat()
        }
        body.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            if (!first) topMargin = dp(24)
        })
        rowCount = 0
        if (title.isNotEmpty()) {
            c.addView(text(title, 26f, cText, true).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(2) }
            })
        }
        c.build()
        // 6.0.86：登记为本页的一个跳转锚点（标题非空才算——少数无标题卡片不占跳转位）
        if (title.isNotEmpty()) cardAnchors[tab].add(title to c)
    }

    /** 一行：左侧标题（+灰色说明），右侧由调用方继续 addView。 */
    private fun row(c: LinearLayout, title: String, desc: String?): LinearLayout {
        if (rowCount > 0) c.addView(divider(), LinearLayout.LayoutParams(MATCH, 1))
        rowCount++
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 30f, cText, false))
        if (!desc.isNullOrEmpty()) {
            col.addView(text(desc, 22f, cSub, false).apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) }
            })
        }
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        return r
    }

    private fun segRow(
        c: LinearLayout,
        key: String,
        title: String,
        desc: String?,
        options: List<Pair<String, String>>
    ) {
        val r = row(c, title, desc)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = round(cSegBg, dp(20))
        }
        val segs = ArrayList<TextView>(options.size)
        for (o in options) {
            val tv = text(o.second, 22f, cSub, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), dp(8))
                isClickable = true
                setOnClickListener { write(key, o.first) }
            }
            segs += tv
            host.addView(tv)
        }
        r.addView(host)
        val paint = {
            val cur = snap?.str(key, "") ?: ""
            for (i in options.indices) {
                val on = options[i].first == cur
                val tv = segs[i]
                tv.background = if (on) round(cAccent, dp(16)) else null
                tv.setTextColor(if (on) Color.WHITE else cSub)
                tv.typeface = if (on) tb() else tr()
            }
        }
        paint()
        refreshers += paint
    }

    private fun switchRow(
        c: LinearLayout,
        key: String,
        title: String,
        desc: String?,
        onWrite: ((Boolean) -> Unit)? = null
    ) {
        val r = row(c, title, desc)
        val tv = text("", 22f, cSub, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            isClickable = true
            setOnClickListener {
                val next = snap?.bool(key, false) != true
                write(key, if (next) "true" else "false")
                // 回调带上新值：调用方可以直接按新值起停服务，不必等配置回流再回读
                onWrite?.invoke(next)
            }
        }
        r.addView(tv)
        val paint = {
            val on = snap?.bool(key, false) == true
            tv.text = if (on) "开" else "关"
            tv.setTextColor(if (on) Color.WHITE else cSub)
            tv.background = round(if (on) cAccent else cSegBg, dp(16))
        }
        paint()
        refreshers += paint
    }

    /** 自动启停三段按钮的着色（模式存在自己的 SP，不走配置树，故单独抽出来）。 */
    private fun paintStartStopSegs(
        segs: List<TextView>, keys: List<String>, cur: String,
        accent: Int, sub: Int, segBg: Int
    ) {
        for (i in segs.indices) {
            val on = keys[i] == cur
            segs[i].background = if (on) round(accent, dp(16)) else round(segBg, dp(16))
            segs[i].setTextColor(if (on) Color.WHITE else sub)
            segs[i].typeface = if (on) tb() else tr()
        }
    }

    /**
     * 落在 [SceneStore] 上的整数调节行：[−] 数值 [+]，一次一步。
     *
     * 胎压阈值要「改一个数」，不是「开或关」，所以既不能复用 [switchRow]
     * （读写 DataStore 配置树），也不能复用 [sceneSwitch]（只处理布尔）。
     * 值域在这里夹一次、在 [SceneStore] 里再夹一次 —— 这里是为了**按钮**点到底
     * 之后不再写无意义的重复值，那里是防止别的路径写坏。
     */
    private fun sceneNumRow(
        c: LinearLayout,
        title: String,
        desc: String?,
        min: Int,
        max: Int,
        step: Int,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val r = row(c, title, desc)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(cSegBg, dp(20))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        lateinit var valueTv: TextView

        fun commit(delta: Int) {
            val nv = (get() + delta).coerceIn(min, max)
            set(nv)
            valueTv.text = "$nv"
        }

        fun button(label: String, delta: Int): TextView = text(label, 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(6), dp(22), dp(6))
            background = round(alpha(Color.WHITE, 0x26), dp(16))
            isClickable = true
            setOnClickListener { commit(delta) }
        }

        host.addView(button("−", -step))
        valueTv = text("", 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(92), WRAP)
        }
        host.addView(valueTv)
        host.addView(button("+", step))
        r.addView(host)

        val paint = { valueTv.text = "${get()}" }
        paint()
        refreshers += paint
    }

    private fun actionRow(c: LinearLayout, title: String, desc: String?, label: String, onClick: () -> Unit) {
        val r = row(c, title, desc)
        val tv = text(label, 24f, cAccent, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(8), dp(26), dp(8))
            background = round(0, dp(22)).apply { setStroke(dp(2), cAccent) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(tv)
    }

    /**
     * 可折叠区块：标题行右侧是「展开 / 收起」按钮，内容默认收起。
     *
     * [build] 只在**第一次展开**时才调用。桌面应用列表有 128 行（每行一个图标 + 两个 TextView），
     * 全部预先建出来会让设置页进「功能」页时明显卡一下，而绝大多数人根本不会展开它；
     * 折叠后同一屏还能多放两个卡片。
     */
    private fun foldRow(
        c: LinearLayout,
        title: String,
        desc: String?,
        label: String,
        labelOpen: String,
        build: (LinearLayout) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val r = row(c, title, desc)
        val tv = text(label, 24f, cAccent, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(8), dp(26), dp(8))
            background = round(0, dp(22)).apply { setStroke(dp(2), cAccent) }
            isClickable = true
        }
        r.addView(tv)
        c.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))

        var open = false
        fun paint() {
            tv.text = if (open) labelOpen else label
            box.visibility = if (open) View.VISIBLE else View.GONE
        }
        tv.setOnClickListener {
            open = !open
            if (open && box.childCount == 0) runCatching { build(box) }
            paint()
        }
        paint()
    }

    private fun infoRow(c: LinearLayout, title: String, read: (ConfigSnapshot) -> String) {
        val r = row(c, title, null)
        val tv = TextView(this).apply {
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = tr()
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        r.addView(tv)
        val paint = { tv.text = snap?.let(read) ?: "-" }
        paint()
        refreshers += paint
    }

    private fun sigRow(c: LinearLayout, title: String, read: (CarSignal) -> String) {
        val r = row(c, title, null)
        val tv = TextView(this).apply {
            setTextColor(cSub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = tr()
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        r.addView(tv)
        val paint = { tv.text = read(SignalHub.current()) }
        paint()
        sigRefreshers += paint
    }

    private fun refreshSignals() {
        for (f in sigRefreshers) f()
        toast("已刷新车身信号")
    }

    /**
     * 延时秒数 → 人话。
     *
     * `null` 必须和 `0` 分开：`null` 是"读不到"，`0` 是"读到且确实是 0（已关闭）"。
     * 6.0.60 的水温 -1℃ 就是把这个区分丢了 —— 这里不再用任何数字当哨兵。
     */
    private fun delayLabel(v: Int?): String = when {
        v == null -> if (CarBinderSource.canControl()) "读不到这一项" else "车控通道未连接"
        v <= 0 -> "0 秒 · 已关闭"
        else -> "$v 秒"
    }

    /**
     * 6.0.62：写「伴我回家」延时并回读。
     *
     * 走后台线程 —— [CarBinderSource.setFollowHomeDelay] 内部要等 400ms 让车机处理完再回读，
     * 放主线程会把这一页的点击反馈一起卡住。回读结果才是结论，Binder 写成功不代表车控执行。
     */
    private fun writeFollowHome(sec: Int) {
        if (!CarBinderSource.canControl()) {
            toast("车控通道没连上：先回桌面等 ICarInfo 连接（车身信号页能看状态）")
            return
        }
        toast("正在写入 ${sec} 秒…")
        Thread {
            val got = CarBinderSource.setFollowHomeDelay(sec)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val msg = when {
                    got == null -> "写不进去、也读不回来。原厂可能只允许在车辆设置里改这一项"
                    got == sec && sec == 0 -> "已关闭：车机回读 0 秒"
                    got == sec -> "已生效：车机回读 $got 秒"
                    else -> "车机没接受 ${sec} 秒，回读实际是 $got 秒"
                }
                toast(msg)
                rebuild(2)
            }
        }.start()
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(cDiv) }

    // ---------------- 绘制工具 ----------------

    private fun screenBg(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(bgTop, bgMid, bgBot)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(snap?.int("sizes.cornerR", 110) ?: 110).toFloat()
        }

    private fun cardBg(): GradientDrawable = round(cCard, dp(24)).apply {
        setStroke(dp(1), cDiv)
    }

    private fun round(fill: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(fill)
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (bold) tb() else tr()
            includeFontPadding = false
        }

    private fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

    /** 设置页在前台时压住覆盖层：这是整屏面板，悬浮窗盖上去会挡住左侧选项。 */
    override fun onResume() {
        super.onResume()
        NavOverlay.setSuppressed(this, true)
        // 设置页是整屏面板，导航信息悬浮窗压上去会挡住右上角的选项
        NavInfoOverlay.setSuppressed(this, true)
        // 从系统授权页回来：还开着的权限面板按最新状态重画
        PermPanel.onHostResume()
        // 从系统无障碍页回来：开关可能刚被勾上，重建当前页让那一行显示正确答案
        val on = H6A11y.enabledInSystem(this)
        if (shellReady && on != a11yWasOn) {
            a11yWasOn = on
            rebuild(tab)
        }
    }

    override fun onPause() {
        NavOverlay.setSuppressed(this, false)
        NavInfoOverlay.setSuppressed(this, false)
        super.onPause()
    }

    private fun write(key: String, value: String) {
        lifecycleScope.launch { repo.set(key, value) }
    }

    // ---------------- 数据 ----------------

    private fun observeConfig() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.state.collect { s ->
                    snap = s
                    night = NightMode.isNight(s)
                    theme = Theme(this@SettingsActivity, s, night)
                    val changed = applyColors()
                    if (!shellReady) return@collect
                    if (changed) {
                        bgView.background = screenBg()
                        switchTo(tab)
                    } else if (s.isReady) {
                        for (f in refreshers) f()
                        for (f in sigRefreshers) f()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (t: Throwable) {
        "-"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
    } catch (t: Throwable) {
        -1L
    }

    /** 值 → 中文名：统一走 [CarBinderSource.driveModeName]，避免两套表各写各的。 */
    private fun modeName(m: Int): String = CarBinderSource.driveModeName(m)

    /**
     * 驾驶模式三选：点一下直接下发到车控（两条写通道一起发，与原桌面那张卡同一套逻辑）。
     *
     * 桌面上的「驾驶模式」卡已经删掉，这里是切模式的唯一入口，所以不做成配置项，
     * 而是即时下发 —— 与车控状态保持一致，读的是 `CarSignal.driveMode`。
     *
     * ★ 界面用的是**语义值**（[CarBinderSource.MODE_STANDARD] 等），
     *   真正下发给车机的值由 [CarBinderSource.wireOf] 换算；车型不同值域可能不同，
     *   见下面 [modeMapRow] 的「车型值域」切换。
     */
    private fun modeRow(c: LinearLayout) {
        val r = row(c, "切换模式", "即时下发到车控，车控回报后这里的选中态会跟着走")
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = round(cSegBg, dp(20))
        }
        // 界面顺序「标准 / 运动 / 经济」，下发值由 wireOf 换算，别在这里写死数字
        val items = listOf(
            CarBinderSource.MODE_STANDARD to "标准",
            CarBinderSource.MODE_SPORT to "运动",
            CarBinderSource.MODE_ECO to "经济"
        )
        val segs = ArrayList<TextView>(items.size)

        fun paint() {
            val fresh = android.os.SystemClock.elapsedRealtime() - pendingAt < 6000L
            val cur = if (fresh && pendingMode > 0) pendingMode else SignalHub.current().driveMode
            for (i in items.indices) {
                val on = items[i].first == cur
                segs[i].background = if (on) round(cAccent, dp(16)) else null
                segs[i].setTextColor(if (on) Color.WHITE else cSub)
                segs[i].typeface = if (on) tb() else tr()
            }
        }

        for ((mode, label) in items) {
            val tv = text(label, 22f, cSub, false).apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), dp(8))
                isClickable = true
                setOnClickListener {
                    val ok = CarBinderSource.setDriveMode(mode)
                    toast(if (ok) "已切换到「$label」" else "驾驶模式通道没连上，稍后再试")
                    pendingMode = mode
                    pendingAt = android.os.SystemClock.elapsedRealtime()
                    paint()
                }
            }
            segs += tv
            host.addView(tv)
        }
        r.addView(host)
        paint()
        sigRefreshers.add { paint() }
    }

    /**
     * 车型值域切换 —— 6.0.33 加。
     *
     * 同一个「驾驶模式」域，不同车型/固件的编号不一样：实车这台是 `1 经济 / 2 运动 / 3 标准`，
     * 而原厂 `BeanDriveMode` 常量写的是 `1 标准 / 2 经济 / 3 运动`。值域搞反的表现很典型：
     * 点标准进经济、点运动进标准、点经济进运动 —— 三个按钮整体「跳一格」。
     *
     * 默认已按实车校准，这里留一个一键切换，别的车如果反过来就点一下，
     * 不用再发版。切换后立即生效（下一次点击就走新值域）。
     */
    private fun modeMapRow(c: LinearLayout) {
        val r = row(
            c, "车型值域",
            "同一模式域在不同车型/固件上的编号不同。判据：点「标准」车机进标准就对了；" +
                "进的是经济就是值域反了，点右侧切一下（立即生效，下次点击走新值域，不用重启）"
        )
        val tv = text("", 22f, cAccent, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(8), dp(22), dp(8))
            background = round(0, dp(22)).apply { setStroke(dp(2), cAccent) }
            isClickable = true
        }
        fun paint() {
            tv.text = if (CarBinderSource.modeReversed()) "实车" else "原厂"
            tv.setTextColor(cAccent)
        }
        tv.setOnClickListener {
            CarBinderSource.setModeReversed(!CarBinderSource.modeReversed())
            paint()
            toast(
                if (CarBinderSource.modeReversed()) "值域：1 经济 / 2 运动 / 3 标准"
                else "值域：1 标准 / 2 经济 / 3 运动"
            )
        }
        paint()
        r.addView(tv)
    }

    /**
     * 机油剩余文字：**按发动机小时**算（和桌面机油卡同一份 [OilLife]）。
     *
     * 保养计划删除后不再读任何 `maint.*` 配置：`剩余h = 固定200h - 发动机累计`。
     * 不显示百分比，累计小时可以在本卡「发动机累计运行时长」里改。
     */
    private fun oilLifeText(@Suppress("UNUSED_PARAMETER") s: CarSignal): String {
        val st = OilLife.compute()
        val engine = "发动机累计 ${EngineHours.text()}" + if (EngineHours.isRunning()) "（运转中）" else ""
        return "${OilLife.summary(st)} · $engine"
    }

    /** `appPage.sel`：空 = 显示全部；否则是逗号分隔的 `pkg/.Act`。 */
    private fun parseAppSel(): List<String> {
        val v = snap?.str("appPage.sel", "")?.trim().orEmpty()
        if (v.isEmpty()) return emptyList()
        return v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 应用勾选行：图标 + 名称 + 「显示 / 隐藏」。
     *
     * 点一下就把整份选择写回 `appPage.sel`（逗号分隔），桌面 `AppCard` 下次刷新就按它铺格子。
     * 隐藏到只剩一个时会拦住 —— 空列表在语义上是"显示全部"，把最后一个也删掉会全冒出来。
     */
    private fun appToggleRow(c: LinearLayout, e: DockSlots.AppEntry, sel: MutableList<String>) {
        if (rowCount > 0) c.addView(divider(), LinearLayout.LayoutParams(MATCH, 1))
        rowCount++
        // 兜底通道来的条目没有具体 Activity，key 会是纯包名 —— 统一走 DockSlots.key
        val flat = DockSlots.key(e)

        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(e.icon ?: packageManager.defaultActivityIcon)
        }
        r.addView(iv, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(18) })
        r.addView(text(e.label, 26f, cText, false), LinearLayout.LayoutParams(0, WRAP, 1f))

        val tag = text("", 22f, cSub, false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(7), dp(22), dp(7))
        }
        r.addView(tag)

        fun paint() {
            val on = sel.contains(flat)
            tag.text = if (on) "显示" else "隐藏"
            tag.setTextColor(if (on) Color.WHITE else cSub)
            tag.background = round(if (on) cAccent else cSegBg, dp(16))
        }
        paint()

        r.setOnClickListener {
            if (sel.contains(flat)) {
                if (sel.size <= 1) {
                    toast("至少保留一个应用")
                    return@setOnClickListener
                }
                sel.remove(flat)
            } else {
                sel.add(flat)
            }
            write("appPage.sel", sel.joinToString(","))
            paint()
        }
        c.addView(r)
    }

    /** 停靠栏某一格当前绑的是什么（给设置页的行描述用）。 */
    private fun describeSlot(i: Int): String {
        val cnt = (snap?.int("sidebar.phCount", 6) ?: 6).coerceIn(1, 8)
        val v = DockSlots.raw(this, i)
        if (v == null) {
            if (i == cnt - 1) return "默认：设置"
            val rail = snap?.list("rail")
            val item = rail?.getOrNull(i) as? Map<*, *>
            val label = item?.get("label")?.toString()
            return if (label.isNullOrBlank()) "未设置 · 点「更换」添加应用" else "默认：$label"
        }
        if (v.isEmpty()) return "空 · 点「更换」添加应用"
        if (v == DockSlots.HOME) return "主页按钮"
        if (v == DockSlots.SETTINGS) return "设置按钮"
        return DockSlots.pkgOf(v)
    }

    // 6.0.31：温度也按物理量程兜底，NaN / 越界（其它车型的 Invalid 标记）统一显示「--」
    private fun fmtC(v: Float): String = if (v in -40f..200f) v.toInt().toString() else "--"

    private fun join(vararg parts: String): String = parts.joinToString(" · ")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val TABS = arrayOf("外观", "功能", "车机", "维护", "关于")
    }
}

/**
 * 设置页自绘导航图标（原版是 NavIcon 自绘矢量；这里用同样的线条风格）。
 * kind：0 外观（圆环）1 功能（滑杆）2 车机（车身）3 维护（扳手）4 关于（i）5 返回（‹）
 */
internal class NavIcon(ctx: android.content.Context, private val kind: Int) : View(ctx) {

    var color: Int = 0xFF5C6470.toInt()
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        p.color = color
        p.strokeWidth = w * 0.075f
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.30f
        when (kind) {
            0 -> {
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawCircle(cx, cy, r * 0.34f, p)
            }
            1 -> {
                for (i in 0..2) {
                    val y = h * (0.27f + 0.23f * i)
                    canvas.drawLine(w * 0.14f, y, w * 0.86f, y, p)
                    canvas.drawCircle(w * (0.34f + 0.16f * i), y, w * 0.085f, p)
                }
            }
            2 -> {
                rect.set(w * 0.12f, h * 0.30f, w * 0.88f, h * 0.64f)
                canvas.drawRoundRect(rect, w * 0.12f, w * 0.12f, p)
                canvas.drawLine(w * 0.32f, h * 0.50f, w * 0.68f, h * 0.50f, p)
                canvas.drawCircle(w * 0.29f, h * 0.74f, w * 0.075f, p)
                canvas.drawCircle(w * 0.71f, h * 0.74f, w * 0.075f, p)
            }
            3 -> {
                canvas.drawLine(w * 0.26f, h * 0.74f, w * 0.62f, h * 0.38f, p)
                canvas.drawCircle(w * 0.68f, h * 0.32f, w * 0.14f, p)
            }
            4 -> {
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawCircle(cx, h * 0.34f, w * 0.035f, p)
                canvas.drawLine(cx, h * 0.46f, cx, h * 0.68f, p)
            }
            else -> {
                canvas.drawLine(w * 0.38f, h * 0.24f, w * 0.62f, h * 0.50f, p)
                canvas.drawLine(w * 0.62f, h * 0.50f, w * 0.38f, h * 0.76f, p)
            }
        }
    }
}
