package com.h6n.launcher.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.viewModels
import com.h6n.launcher.ui.common.ScaledActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.h6n.launcher.core.AmapNightSync
import com.h6n.launcher.core.AutoBackup
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.EngineHours
import com.h6n.launcher.core.LocPerm
import com.h6n.launcher.core.MockSource
import com.h6n.launcher.core.NavInfoOverlay
import com.h6n.launcher.core.NavOverlay
import com.h6n.launcher.core.NightScreen
import com.h6n.launcher.core.PermAuto
import com.h6n.launcher.core.SignalHub
import com.h6n.launcher.core.StartStop
import com.h6n.launcher.core.Storage
import com.h6n.launcher.core.BootTask
import com.h6n.launcher.core.EdgeGesture
import com.h6n.launcher.core.FloatButton
import com.h6n.launcher.core.SystemBar
import com.h6n.launcher.core.TireAlert
import com.h6n.launcher.core.UpdateManager
import com.h6n.launcher.core.WallpaperStore
import com.h6n.launcher.core.WeatherSource
import com.h6n.launcher.domain.ConfigSnapshot
import com.h6n.launcher.ui.common.BackdropBg
import com.h6n.launcher.ui.common.GlassBg
import com.h6n.launcher.ui.common.NightMode
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf
import com.h6n.launcher.ui.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 桌面主页，按原版 `activity_main.xml` 的层次重建：
 *
 * ```
 * root（渐变底）
 *  ├ statusBar        72dp 高：时间/日期/天气 + 挡位/模式 + 6 组状态值 + 信号图标
 *  ├ cardMap          sizes: mapLeft/mapRight/cardTop，里面是第 2 页的卡
 *  │   └ SixGrid      左列 车速 + 应用网格｜右列 胎压车模｜底部通栏 机油寿命
 *  └ dock             左侧竖排：时钟 + 应用快捷方式 + 设置
 * ```
 *
 * 音乐横条（U 盘播放 / 系统媒体）与车速卡上的「回家 / 公司」已按需求整体删除。
 *
 * 刷新策略：
 * ```
 * lastApplied == null -> bindAll()      仅进程内第一次
 * 否则               -> applyDelta()   只重画变了的分组
 * ```
 */
class MainActivity : ScaledActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var root: FrameLayout
    private lateinit var statusBar: StatusBarView
    private lateinit var pager: PageHost
    private lateinit var mapCard: FrameLayout
    private lateinit var sixGrid: SixGrid
    private lateinit var page2Card: FrameLayout
    private lateinit var appPage: AppPageView
    private lateinit var dock: DockView

    /** 壁纸铺在 root 最底层；上面再盖一层很淡的白色蒙层，保证深色文字仍读得清。 */
    private lateinit var wallpaperView: ImageView
    private lateinit var wallpaperDim: View
    /** 上次解码的配置指纹，避免昼夜切换 / onResume 反复解码大图。 */
    private var wallpaperKey = ""

    /**
     * 壁纸解码（v6.0.43）。
     *
     * 原先每次换壁纸都 `Thread(...).start()`：连点几张就是几个线程同时 decode
     * 1920×720 的 JPEG，CPU 和 Bitmap 内存一起抢，短时间内存峰值能翻好几倍。
     * 改成单线程串行 + 提交时取消尚未执行的旧任务：同一时刻最多一张在解，
     * 已经在解的那张取消不了，但它的结果会被 [wallpaperKey] 校验丢掉。
     */
    private val wallExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "h6-wallpaper-decode").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private var wallTask: Future<*>? = null

    private var theme: Theme? = null
    private var lastApplied: ConfigSnapshot? = null
    private var snap: ConfigSnapshot? = null

    /** 当前是否夜间（跟随车灯）。变化时整套重刷 + 通知高德。 */
    private var nightNow = false

    /** 上一次 applyGeometry 的几何指纹（v6.0.46）：没变就不重设 LayoutParams。 */
    private var lastGeomFp = ""

    /** 每隔一段时间给高德补发一次昼夜（高德重启/被强停后不会自己回头问）。按分钟累加。 */
    private var amapTick = 0

    // ---- 昼夜判定的节流状态（v6.0.50）----
    // 目标：1s ticker 保持不变，但「时间模式」不再每秒 new 一个 Calendar，
    // 车灯模式也不再每秒重算一遍已经确定的结论。
    /** 上次判定用的快照对象；换对象 = 配置改过，强制重判一次。 */
    private var nightSnap: ConfigSnapshot? = null
    private var nightMode = ""
    private var nightMinute = Long.MIN_VALUE
    private var nightLamp = Int.MIN_VALUE
    private var nightJudged = false
    /** 上次累加 [amapTick] 的分钟标记（避免每秒 ++）。 */
    private var lastAmapMinute = Long.MIN_VALUE

    /**
     * 桌面不可见期间判出的昼夜翻转还没重绘过（6.0.82）。
     *
     * 高德全屏盖住桌面时 [syncNight] 照样在跑（要给高德发广播），但**不能**重绘
     * 桌面 View；这时只把结论记在 [nightNow]，置位本标记，回到桌面由 [onResume]
     * 补一次 [bindAll]。不置位的后果是：`nightNow` 已是新值，`onResume` 里
     * `nightJudged=false` 重判也会判成「没变」，桌面就停在旧主题。
     */
    private var nightPendingRebind = false


    private var weather: WeatherSource? = null

    /** 顶部下滑手势：按下点的屏幕坐标，用来判起点和方向。 */
    private var topDownY = -1f
    private var topDownX = -1f
    private var topSwipeDone = false

    /** 兜底方案是否已经把系统状态栏「露」出来了（露出来后要定时/下次触摸收回）。 */
    private var barsRevealed = false

    /** 本次启动已经主动弹过提醒的版本号：同一版本不重复弹，想再看就点状态栏徽标。 */
    private var promptedCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首帧兜底：配置、Binder、网络任一环节尚未就绪时也先显示稳定底色。
        window.setBackgroundDrawableResource(android.R.color.black)
        applyImmersive()
        // 发动机累计运行时间：进程一起来就把上次的账补上（保养卡按它折算机油寿命）
        EngineHours.attach(this)
        // 夜间自动息屏：只需一个 applicationContext（读亮度 / 下发背光），
        // 真正的计时挂在下面 ticker 里，跟着桌面前台状态走。
        NightScreen.attach(this)
        weather = WeatherSource(this) { vm.config.value }
        buildUi()
        observeConfig()
        observeSignals()
        observeWeather()
        startTicker()

        // 非首屏任务统一延后：桌面首帧优先，不让弹窗、扫盘、更新和备份争抢启动资源。
        // 捐赠弹窗不再挂在这里 —— 6.0.60 起统一由 onResume 按「每次回到主页」触发。
        root.postDelayed({
            if (!isFinishing) UpdateManager.onStart(this) { st, v ->
                if (::statusBar.isInitialized) statusBar.refreshUpdate()
                if (st == UpdateManager.FOUND && v != null) promptUpdate(v)
            }
        }, 5000L)
        root.postDelayed({
            if (!isFinishing && ::sixGrid.isInitialized) {
                StorageAlert.check(this) { sixGrid.bind(SignalHub.current()) }
            }
        }, 7000L)
        root.postDelayed({
            if (!isFinishing && LocPerm.shouldAskOnBoot(this)) {
                LocPerm.markAsked(this)
                if (!LocPerm.granted(this)) LocPerm.request(this)
            }
        }, 8000L)
        root.postDelayed({ if (!isFinishing) AutoBackup.onColdStart(this) }, 6000L)
        // 6.0.90：自动启停默认值 —— 内部再延时 9 秒、进程内只排一次；未解锁/保持原样会跳过
        root.postDelayed({ if (!isFinishing) StartStop.onColdStart(this) }, 1000L)
    }

    override fun onPause() {
        super.onPause()
        // 胎压弹窗不能盖到导航/音乐上去：摘回调 + 收掉当前实例（倒计时也一并停）
        TireAlert.setListener(null)
        TireAlarm.dismiss()
        // 夜间息屏只属于桌面：一离开就把亮度还回去，别把黑屏带进导航/音乐
        NightScreen.onBack()
        // 离开桌面：高德导航信息允许挂成悬浮窗（车上有导航数据时才真的挂）
        NavInfoOverlay.desktopFront = false
        NavInfoOverlay.sync(this)
        if (rxOn) {
            runCatching { unregisterReceiver(overlayStateRx) }
            rxOn = false
        }
        // 更新弹窗只属于桌面：切到导航/音乐/安装器时立刻收掉，绝不在别人上面盖一层
        UpdateDialog.dismissPrompt()
    }

    override fun onDestroy() {
        weather?.stop()
        weather = null
        // 壁纸解码线程随 Activity 一起收掉（daemon + 单线程，不留常驻）
        wallTask?.cancel(false)
        wallExec.shutdownNow()
        UpdateDialog.dismissPrompt()
        // 车模底图是 decodeResource 出来的独立 Bitmap，Activity 结束就回收；
        // 翻页 / 换肤只是 detach，不在这里回收（CarModelView.release 有说明）。
        if (::sixGrid.isInitialized) sixGrid.release()
        super.onDestroy()
    }

    /**
     * 自动检查撞到新版本时的主动提醒。
     *
     * 旧版是 `UpdatePrompt.show()`：只在桌面**前台且有窗口焦点**时弹，退到后台由
     * `onBack()` 收掉（这里对应 [onPause]）。另加一条「同一次启动同一个版本只弹一次」，
     * 免得用户点了「稍后」又被 onResume 反复骚扰；想再看就点状态栏那个金色徽标。
     */
    private fun promptUpdate(v: UpdateManager.Ver, retry: Int = 0) {
        if (isFinishing) return
        if (promptedCode == v.code) return
        // 没焦点时弹出来会被系统立刻收走（比如冷启动那会儿赞赏码还开着）。
        // 这里隔 1.5 秒再试，最多 6 次；仍拿不到焦点就只留状态栏徽标。
        if (!hasWindowFocus()) {
            if (retry < 6) {
                root.postDelayed({
                    if (!isFinishing) promptUpdate(v, retry + 1)
                }, 1500L)
            }
            return
        }
        promptedCode = v.code
        UpdateDialog.prompt(this, v)
    }

    private fun applyImmersive() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---------------- 顶部下滑：拉出原车状态栏 ----------------

    /**
     * 沉浸全屏把系统状态栏藏起来之后，屏幕上**没有任何窗口**接得住「从顶部往下划」，
     * 车机上表现为"桌面顶部划不出原车状态栏"。所以手势只能由 Activity 自己听：
     *
     * - 起点在顶部 [TOP_PULL_DP] 以内（正好是我们画的那条状态栏高度）；
     * - 向下位移超过 [TOP_PULL_MIN_DP]；
     * - 基本竖直（|dx| < 0.8·dy，避免和左右翻页抢）。
     *
     * 满足就交给 [SystemBar.expand]，优先用无障碍的 GLOBAL_ACTION_NOTIFICATIONS 让
     * SystemUI 自己把面板拉下来。
     *
     * 只在旁路观察，**不改写事件分发**：桌面原有的点击、翻页、拖卡片全部照旧。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        runCatching { trackTopPull(ev) }
        return super.dispatchTouchEvent(ev)
    }

    private fun trackTopPull(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                topDownY = ev.y
                topDownX = ev.x
                topSwipeDone = false
                // 兜底露出系统栏之后，用户再动桌面就收回沉浸，免得两条状态栏一直叠着
                if (barsRevealed) hideSystemBarsNow()
            }
            MotionEvent.ACTION_MOVE -> {
                if (topSwipeDone || topDownY < 0f) return
                val dy = ev.y - topDownY
                val dx = kotlin.math.abs(ev.x - topDownX)
                if (topDownY <= dp(TOP_PULL_DP).toFloat() &&
                    dy > dp(TOP_PULL_MIN_DP).toFloat() &&
                    dx < dy * 0.8f
                ) {
                    topSwipeDone = true
                    pullDownSystemBar()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                topDownY = -1f
                topSwipeDone = false
            }
        }
    }

    private fun pullDownSystemBar() {
        if (SystemBar.expand(this)) return
        // 无障碍没开、StatusBarManager 也没权限：先把沉浸全屏撤掉，
        // 让原车状态栏自己露出来，用户再往下拉一次就是系统原生下拉。
        revealSystemBars()
    }

    private fun revealSystemBars() {
        if (barsRevealed) return
        barsRevealed = true
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        Toast.makeText(
            this,
            "已露出系统状态栏，再往下拉一次即可展开；打开「设置 → 停靠栏 → 无障碍服务」后可一步拉出",
            Toast.LENGTH_LONG
        ).show()
        root.removeCallbacks(hideBarsTask)
        root.postDelayed(hideBarsTask, 15_000L)
    }

    private val hideBarsTask = Runnable { hideSystemBarsNow() }

    private fun hideSystemBarsNow() {
        if (!barsRevealed) return
        barsRevealed = false
        root.removeCallbacks(hideBarsTask)
        applyImmersive()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        root = FrameLayout(this)
        root.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        // 即使配置尚未加载，也不能让 HOME 首帧暴露为透明/未绘制窗口。
        root.setBackgroundColor(0xFF101820.toInt())

        // 壁纸在最底层：CENTER_CROP 铺满整屏，比例不一致的图由系统裁切，
        // 不需要预先缩放，也不会拉伸变形。
        wallpaperView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(wallpaperView)
        wallpaperDim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            // 颜色由 applyScrim() 按昼夜现算：白天白蒙层、夜间黑蒙层。
            // 这里不能写死，早期写死 0x33FFFFFF 会让夜间壁纸更亮。
            setBackgroundColor(0x00000000)
            visibility = View.GONE
        }
        root.addView(wallpaperDim)

        statusBar = StatusBarView(this)
        statusBar.setOnWeatherLongClick { weather?.refreshNow() }
        // 状态栏「↑ 新版本」徽标 → 打开完整更新面板（带更新源与检查按钮）
        statusBar.setOnUpdateClick { UpdateDialog.show(this) }
        root.addView(statusBar)

        // 第 1 页：车况（车速 / 机油 / 胎压车模）。第 2 页：整页应用图标墙。
        // 两页都放进 PageHost，左右滑动 / 底部圆点切换。
        pager = PageHost(this)
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(72) })

        mapCard = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            // 子卡片按需使用软件层；父容器不再把整页车况强制降为软件绘制。
        }
        sixGrid = SixGrid(this)
        // 卡片长按各自有自己的动作（原版：胎压卡→调胎压位置），不是"长按进设置"。
        // 之前为了修"长按没反应"在 dispatchTouchEvent 里统一拦了一次长按 → openSettings()，
        // 结果任何一张卡长按都跳设置页，已撤掉。
        // 保养计划已整体删除：机油卡长按直接打开「发动机累计运行时长」，
        // 保存后立刻按新的累计时间重算剩余寿命（固定 200h 周期）。
        sixGrid.setCardLongPress(
            onTire = {
                CardDialogs.showTireSwap(this, vm.config.value) { map ->
                    vm.setConfig("page2.tirePosMap", map)
                }
            },
            onHours = {
                CardDialogs.showEngineHours(this) { h, m ->
                    EngineHours.setTotal(h, m)
                    sixGrid.bind(SignalHub.current())
                    Toast.makeText(this, "已保存，机油剩余时间已重新计算", Toast.LENGTH_SHORT).show()
                }
            }
        )
        // 机油卡右侧「剩余储存」：低空间弹强制清理选项，正常时弹手动清理面板
        sixGrid.setStorageClick {
            val refresh = { sixGrid.bind(SignalHub.current()) }
            if (Storage.isLow()) StorageAlert.show(this, onDone = refresh)
            else FullCleanDialog.show(this, refresh)
        }
        // 6.0.91：应用商店入口按用户红圈标注移到车模左下角（六宫格里），不再占 dock 顶部
        sixGrid.setStoreClick { AppStoreDialog.show(this) }
        mapCard.addView(sixGrid, FrameLayout.LayoutParams(MATCH, MATCH))

        // 第 2 页：整页应用墙（点右上「编辑」→ 设置页勾选要显示的应用）
        page2Card = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            // 应用墙保持硬件加速，避免整页图标因父层设置而走软件绘制。
        }
        appPage = AppPageView(this)
        appPage.onEdit = { openSettings(tab = 1) }
        appPage.onDockChanged = { theme?.let { t -> dock.applyTheme(t) } }
        page2Card.addView(appPage, FrameLayout.LayoutParams(MATCH, MATCH))

        pager.setPages(mapCard, page2Card)
        // 翻到第 2 页时重新查一次应用：刚装完的应用不用重启桌面
        pager.onPageChanged = { i -> if (i == 1) appPage.reload() }

        dock = DockView(
            this,
            { openSettings() },
            // 长按 dock 时钟 = 换壁纸；和「设置 → 外观 → 更换壁纸」是同一面板
            { WallpaperDialog.show(this) { applyWallpaper(force = true) } }
        )
        root.addView(dock, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            topMargin = dp(72)
            leftMargin = dp(16)
        })

        // 状态栏放到最上层，避免任何卡片阴影压在上边
        statusBar.bringToFront()

        setContentView(root)

        // 上面 bindAll 里的第一次 applyGeometry 跑在 measure 之前（root 宽高还是 0，
        // 只能退回 displayMetrics，会把右侧音乐卡排到屏幕外）。布局完再算一遍。
        root.post { theme?.let { applyGeometry(it) } }
    }

    /**
     * 按 `sizes.*` 摆放两块：翻页容器（两页）/ 停靠栏。
     *
     * ```
     * 顶部状态栏 cardTop(72)
     * ┌──────┬───────────────────────────────────┐
     * │ dock │ 第1页 车速 + 机油 / 胎压车模        │
     * │ 整列高│ ⇄ 左右滑动 ⇄                       │
     * │      │ 第2页 全部应用（整页图标墙）         │
     * └──────┴───────────────────────────────────┘  底部留 cardBottom(16)
     * ```
     * 两页共用同一块尺寸（PageHost），停靠栏浮在左侧、两页都能用。
     */
    private fun applyGeometry(t: Theme) {
        val s = t.snap
        val density = resources.displayMetrics.density
        // ★ 必须用 root 自己的宽高，不能直接用 displayMetrics：
        //   这台车的屏幕 2400×1080，但左侧被系统栏占了 90px（原版截图同样如此），
        //   应用可用宽度只有 2310。按 2400 算会把内容排出屏幕右侧（右边圆角看不见）。
        //   root 还没测量时退回 displayMetrics，等 buildUi 里的 post 再算一遍。
        val wpx = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val hpx = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        val screenWdp = Math.round(wpx / density)
        val screenHdp = Math.round(hpx / density)

        val homeOn = s.bool("sidebar.homeOn", false)
        val mapLeftDp = if (homeOn) s.int("sizes.mapLeft", 8) else s.int("sizes.mapLeftNoSide", 16)
        val mapRightDp = s.int("sizes.mapRight", mapLeftDp)
        val auto = s.bool("sizes.layoutAuto", true) || s.int("sizes.layoutAuto", 0) != 0

        // 翻页容器占满内容宽度（停靠栏那块靠每页自己的左内边距避开）
        val contentWdp = (screenWdp - mapLeftDp - mapRightDp).coerceAtLeast(320)
        val dockOn = s.bool("sidebar.dockOn", true)

        val cardTop = s.int("sizes.cardTop", 72)
        val cardBottom = s.int("sizes.cardBottom", 16)
        // 「上下两段」的总高：默认铺满状态栏以下、底边距以上
        var totalH = if (auto && screenHdp > 0) {
            (screenHdp - cardTop - cardBottom).coerceAtLeast(320)
        } else {
            s.int("sizes.cardH", 632)
        }
        // 设置页「卡片高度」写的是 sizes.cardSize（650 / 632 / 614），
        // 音乐条删掉后它就是翻页容器本身的高度，给了就压到这个值以内。
        val cardSize = s.int("sizes.cardSize", 0)
        if (cardSize > 0) totalH = Math.min(totalH, Math.max(360, cardSize))

        val mapHdp = totalH

        // 每页的左内边距 = 停靠栏右边缘相对卡片左边缘的距离 + 12dp 呼吸
        // （提前算：下面的几何指纹要用它，它自己又依赖 sidebar.dockX/dockW）
        val dockRightDp = s.int("sidebar.dockX", 16) + s.int("sidebar.dockW", 104)
        val inset = (dockRightDp - mapLeftDp + 12).coerceAtLeast(0)

        // ★ v6.0.46：几何指纹。applyGeometry 在 buildUi 与 root.post 里各调一次，
        //   配置变化时也会连调两次；每次重设 LayoutParams 都会触发整棵树的 measure/layout。
        //   算出来的几何完全一致时只做「可见性确认」，不碰布局。
        val fp = "$contentWdp|$mapHdp|$mapLeftDp|$cardTop|$dockOn|$homeOn|$inset"
        if (fp == lastGeomFp) {
            applyDockVisibility()
            return
        }
        lastGeomFp = fp

        pager.layoutParams = FrameLayout.LayoutParams(dp(contentWdp), dp(mapHdp)).apply {
            leftMargin = dp(mapLeftDp)
            topMargin = dp(cardTop)
        }
        dock.layoutParams = DockView.layoutParams(this, t).apply {
            height = dp(if (dockOn) totalH else mapHdp)
        }

        sixGrid.insetLeftDp = inset
        appPage.setInset(inset)

        // 覆盖原厂导航栏打开后，桌面这条 dock 让位给覆盖层：
        // 同一条侧栏只保留一份，切应用时不会"换一条"、也不会两层叠出重影。
        // 覆盖层的位置/宽度就是 sidebar.dockX/dockW，正好落在上面留白的那块，所以 inset 不用改。
        applyDockVisibility()

        // 卡片尺寸由这里给死：六格内部按原版 p2 算法分列分行，不再用权重硬挤
        sixGrid.setGeometry(contentWdp, mapHdp)

        // 卡片高度变化可能顶到状态栏，重新置顶
        statusBar.bringToFront()
    }

    /** @param tab 打开的设置页分栏（0 外观 / 1 功能 / 2 车机 / 3 维护 / 4 关于） */
    private fun openSettings(tab: Int = 0) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", tab))
    }

    /**
     * 桌面自带的 dock 该不该显示。
     *
     * 打开「覆盖原厂导航栏」且悬浮窗权限在手 → 隐藏，这条侧栏交给覆盖层
     * （覆盖层位置就是 sidebar.dockX/Y/W，正好填在 dock 原来的位置上）。
     * 没权限时覆盖层挂不上，dock 必须留着，否则桌面上一条侧栏都没有。
     */
    private fun applyDockVisibility() {
        dock.visibility = if (NavOverlay.replacesDock(this)) View.GONE else View.VISIBLE
    }

    private val dockCheck = Runnable { applyDockVisibility() }

    /**
     * 覆盖层挂上 / 掉下来时它会广播一次 [NavOverlay.ACTION_STATE]，
     * 收到就立刻重算 dock 显隐。之前只靠 400/900/1800/3000ms 四次轮询，
     * 覆盖层要是 3 秒后才挂上（自愈重试那次），dock 就一直占着位置。
     */
    private val overlayStateRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (::dock.isInitialized) applyDockVisibility()
        }
    }
    private var rxOn = false

    /**
     * 覆盖层是**异步**起来的：起服务 → onCreate → 延迟 250ms 才 addView。
     * 刚打开开关就回桌面时还读不到 running/attached，若当场把 dock 藏了、
     * 覆盖层又没挂上，桌面上就一条侧栏都没有（"打开没效果"的一种表现）。
     *
     * 所以这里在几个时间点各复查一次：挂上了就继续让位；
     * 一直没挂上就把 dock 放回来，宁可两个都在也别什么都没有。
     */
    private fun scheduleDockChecks() {
        for (d in longArrayOf(400L, 900L, 1800L, 3000L)) root.postDelayed(dockCheck, d)
    }

    // ---------------- 配置 ----------------

    private fun observeConfig() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.config.collect { s ->
                    if (!s.isReady) return@collect
                    snap = s
                    val prev = lastApplied
                    if (prev == null || !prev.isReady) bindAll(s) else applyDelta(s, s.diff(prev))
                    lastApplied = s
                }
            }
        }
    }

    private fun bindAll(s: ConfigSnapshot) {
        val night = NightMode.isNight(s)
        nightNow = night
        // 息屏开关要在 dock 重建（下面 dock.applyTheme）之前就同步好，
        // 否则冷启动 / 刚改完配置时月亮格会画成上一次的状态。
        NightScreen.syncFrom(s)
        val t = Theme(this, s, night)
        theme = t
        snap = s
        root.background = screenBg(t)
        applyWallpaper()
        applyGeometry(t)
        statusBar.applyTheme(t)
        // 6.0.105：时间统一显示在状态栏（用户要求「时间移到状态栏」）。
        // 停靠栏时钟由 sidebar.showClock 控制，默认已改为 false，两处不再互相顶掉。
        statusBar.setTimeVisible(true)
        renderWeather(weather?.state?.value)
        mapCard.background = mapBg(t)
        page2Card.background = mapBg(t)
        sixGrid.applyTheme(t)
        appPage.applyTheme(t)
        dock.applyTheme(t)
        val now = Date()
        statusBar.bindClock(now)
        dock.bindClock(now)
        val sig = SignalHub.current()
        statusBar.bindSignal(sig)
        sixGrid.bind(sig)
        // 桌面这一侧换肤的同时把昼夜推给高德 —— 两边同一判据，不会一边夜间一边白天
        AmapNightSync.apply(this, night, s.bool("theme.amapSync", true), force = true)
        // 覆盖原厂导航栏：开关/停靠栏/昼夜变了，悬浮层要跟着重建
        NavOverlay.refresh(this)
    }

    /**
     * 按 [WallpaperStore] 的选择重画桌面底图。
     * 解码放到后台（车机 CPU 慢，1920×720 的 JPEG 直接占首帧会卡顿），
     * 用 wallpaperKey 挡住过期结果；配置没变时直接跳过，不重复分配 Bitmap。
     */
    private fun applyWallpaper(force: Boolean = false) {
        val ch = WallpaperStore.get(this)
        // 蒙层要跟着昼夜走，所以每次都重算（很便宜），不参与下面的 key 短路判断。
        applyScrim(ch)
        val key = ch.key()
        if (!force && key == wallpaperKey) return
        wallpaperKey = key
        if (ch.mode == WallpaperStore.MODE_THEME) {
            hideWallpaper()
            return
        }
        val w = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val h = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        // 单线程解码：撤掉还没开始跑的旧任务，已经在跑的那张结果回来时会被 key 校验丢掉。
        wallTask?.cancel(false)
        wallTask = wallExec.submit {
            val bmp = WallpaperStore.decode(applicationContext, ch, w.coerceAtLeast(1), h.coerceAtLeast(1))
            runOnUiThread {
                if (isFinishing || isDestroyed || wallpaperKey != key) {
                    bmp?.takeUnless { it.isRecycled }?.recycle()
                    return@runOnUiThread
                }
                if (bmp == null) {
                    hideWallpaper()
                    return@runOnUiThread
                }
                val old = wallpaperView.drawable as? BitmapDrawable
                wallpaperView.setImageBitmap(bmp)
                // 在线图库与本机图片统一 CENTER_CROP：图源都是 1920×720，
                // 铺满 16:9 车机屏不会变形，也不像 FIT_XY 那样在极端比例下被压扁
                wallpaperView.scaleType = ImageView.ScaleType.CENTER_CROP
                wallpaperView.visibility = View.VISIBLE
                old?.bitmap?.let { if (it !== bmp && !it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * 壁纸之上的可读性蒙层。
     *
     * 颜色必须跟主题同向：白天文字深 → 白蒙层提亮壁纸；夜间文字白 → 黑蒙层压暗壁纸。
     * 之前这里是固定 `0x33FFFFFF`，夜间不但没压暗，反而把壁纸提亮了一层，
     * 于是「换壁纸后字看不清」和「深色模式桌面太亮」同时出现。
     */
    private fun applyScrim(ch: WallpaperStore.Choice) {
        val t = theme
        if (t == null || ch.mode == WallpaperStore.MODE_THEME) {
            wallpaperDim.visibility = View.GONE
            return
        }
        if (ch.scrim <= WallpaperStore.SCRIM_OFF) {
            wallpaperDim.visibility = View.GONE
            return
        }
        wallpaperDim.setBackgroundColor(WallpaperStore.scrimColor(ch.scrim, t.lightText()))
        wallpaperDim.visibility = View.VISIBLE
    }

    private fun hideWallpaper() {
        wallpaperView.visibility = View.GONE
        wallpaperDim.visibility = View.GONE
    }

    private fun applyDelta(s: ConfigSnapshot, changed: Set<String>) {
        if (changed.isEmpty()) return
        // 昼夜翻转（含 theme.mode 被改）走整套重刷：只换一半会留下"背景夜间、文字白天"的花屏
        if (NightMode.isNight(s) != nightNow) {
            bindAll(s)
            return
        }
        val t = Theme(this, s, NightMode.isNight(s))
        theme = t
        snap = s
        if (changed.any {
                it.startsWith("colors.") || it.startsWith("sizes.") || it.startsWith("texts.") ||
                    it.startsWith("theme.") || it.startsWith("badge.") || it.startsWith("fonts.")
            }) {
            root.background = screenBg(t)
            statusBar.applyTheme(t)
            // 6.0.105：时间统一显示在状态栏（用户要求「时间移到状态栏」）。
        // 停靠栏时钟由 sidebar.showClock 控制，默认已改为 false，两处不再互相顶掉。
        statusBar.setTimeVisible(true)
            mapCard.background = mapBg(t)
            page2Card.background = mapBg(t)
        }
        if (changed.any {
                it.startsWith("page2.") || it.startsWith("temp.") || it.startsWith("window.")
            }) {
            sixGrid.applyTheme(t)
            // ★ applyTheme 只换了皮肤，卡片上的数字要 bind 才重算。
            //   机油寿命的长按弹窗直接改 EngineHours，保存时调用方已经立刻重绑。
            sixGrid.bind(SignalHub.current())
        }
        // 第 2 页：勾选了哪些应用 / 每行个数 / 换了皮肤都重排
        if (changed.any {
                it.startsWith("appPage.") || it.startsWith("colors.") || it.startsWith("sizes.") ||
                    it.startsWith("fonts.")
            }) {
            appPage.applyTheme(t)
        }
        if (changed.any { it.startsWith("sizes.") || it.startsWith("sidebar.") }) {
            // 卡片宽高 / 停靠栏位置变了：重新算几何，两页的左内边距也随之变化
            applyGeometry(t)
            if (changed.any { it.startsWith("sizes.") }) {
                sixGrid.applyTheme(t)
                appPage.applyTheme(t)
            }
        }
        if (changed.any { it.startsWith("sidebar.") }) {
            dock.applyTheme(t)
        }
        if (changed.any { it.startsWith("statusItems.") }) {
            statusBar.applyTheme(t)
        }
        if (changed.any { it.startsWith("weather.") }) {
            weather?.refreshNow()
        }
        if (changed.any { it.startsWith("sidebar.") }) {
            // 覆盖原厂导航栏开关 / 宽度位置 / 昼夜都在这组里
            if (!NavOverlay.sync(this)) {
                // 开了开关但没悬浮窗权限：提示一次，让用户去系统页授权
                Toast.makeText(this, "需要「显示在其他应用上层」权限才能覆盖原厂导航栏", Toast.LENGTH_LONG).show()
                NavOverlay.openPermissionPage(this)
            }
        }
    }

    /** 整屏背景：三段渐变 + 两团柔光（见 [BackdropBg]），比纯三段色更有层次。 */
    private fun screenBg(t: Theme): BackdropBg {
        val top = t.color("screenTop", 0xFFDCE4F0.toInt())
        val mid = t.color("screenMid", 0xFFC6D3E6.toInt())
        val bot = t.color("screenBot", 0xFFAABAD4.toInt())
        val warm = if (t.night) 0x1A5B7BD6 else 0x66FFFFFF
        val cool = if (t.night) 0x223B82F6 else 0x44C9DBF7.toInt()
        return BackdropBg(top, mid, bot, warm, cool)
    }

    private fun mapBg(t: Theme): GlassBg {
        val radius = t.size("mapRadius", 96).toFloat()
        // colors.map = #00FFFFFF / mapNight = #001B2029 —— 原版地图卡是"全透明填充"，
        // 只有柔和外阴影 + 1px 描边，卡片边界靠大圆角暗示。
        // 早期版本给了 0x40FFFFFF 的半透明白，整块会明显发白，和原版不像。
        return GlassBg(
            radiusPx = dpf(radius),
            blurPx = dpf(t.size("mapCardShadow", 10).toFloat()),
            spreadPx = dpf(4f),
            shadowColor = if (t.night) 0x66000000 else 0x22000000,
            fillTop = t.color("map", 0x00FFFFFF),
            fillBottom = t.color("map", 0x00FFFFFF),
            strokePx = dpf(t.size("mapStrokeW", 1).toFloat()),
            strokeColor = t.color("mapStroke", 0x1A1F2937),
            exponent = 5f
        )
    }

    // ---------------- 信号 ----------------

    private fun observeSignals() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.signals.collect { s ->
                    if (theme == null) return@collect
                    statusBar.bindSignal(s)
                    sixGrid.bind(s)
                }
            }
        }
    }

    /**
     * 天气：数据源在 IO 线程自己按 `weather.refreshMin` 轮询，
     * 这里只收结果画到状态栏；断网时收的是上一次的缓存值。
     */
    private fun observeWeather() {
        weather?.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                weather?.state?.collect { w -> renderWeather(w) }
            }
        }
    }

    private fun renderWeather(w: WeatherSource.Info?) {
        if (w == null) {
            statusBar.bindWeather(null, null)
        } else {
            statusBar.bindWeather(w.state, w.tempC)
        }
    }

    // ---------------- 时钟 / mock ----------------

    /**
     * 1 秒心跳。**故意不降频**：发动机累计时间要后台走秒、夜间息屏倒计时要准，
     * 时钟也要跟系统秒对齐；能省的只是「已经算过、这一秒不会变」的重活。
     *
     * 每秒真正必做的只有三件：刷时钟（前台）、推模拟信号（若开）、喂一帧给
     * [EngineHours.tick]。其余按条件跳过：
     *  - 页面不可见：所有 View 更新、昼夜判定、息屏检查全部停（见 [NightScreen.onBack] 已置前台态为 false）；
     *  - 机油卡：只在累计时间跨分钟时重绑；
     *  - 昼夜：时间模式按分钟判、车灯模式按灯值变化判、固定模式只在配置变更时判；
     *  - 高德补发：只在分钟边界累加计数。
     */
    private fun startTicker() {
        lifecycleScope.launch {
            while (true) {
                val nowMs = System.currentTimeMillis()
                val uiActive = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                val s = snap

                // ① 时钟：前台每秒照常刷，后台一次都不做
                if (uiActive) {
                    val now = Date(nowMs)
                    statusBar.bindClock(now)
                    dock.bindClock(now)
                }

                // ② 模拟信号：开着就照常推帧。这是数据源不是 UI，后台也要保持连续，
                //    否则 engineOn 会停在最后一帧、发动机累计时间算错。
                //    View 绑定由 observeSignals 的 STARTED 生命周期自己停，这里不用管。
                if (s != null && s.bool("mock.enabled", false)) {
                    SignalHub.push(MockSource.from(s))
                }

                // ③ 发动机累计：后台也必须按秒喂帧，这行是整个 ticker 不能降频的理由
                val sigNow = SignalHub.current()
                val minuteBefore = EngineHours.totalSeconds() / 60
                EngineHours.tick(sigNow.engineOn)

                // ④ 昼夜：**不跟 uiActive**。桌面被高德全屏盖住时这里必须继续判 ——
                //    判出翻转要立刻把昼夜推给高德；旧版整段塞在下面的 uiActive 里，
                //    车内开灯后高德一直是白底，必须退一次桌面（onResume 的 force 补发）
                //    再进高德才变色。桌面自己的重绘仍在下面的 uiActive 分支里跳过。
                if (s != null) syncNight(sigNow, s, nowMs, uiActive)

                // ⑤ 从这里往下全是 UI 工作，页面不可见时整体跳过
                if (uiActive && s != null) {
                    // 机油卡只在「累计时间跨分钟」时重绑，不做每秒全卡片重绑
                    if (EngineHours.totalSeconds() / 60 != minuteBefore) sixGrid.bind(sigNow)
                    // 夜间自动息屏：倒计时必须每秒走，复用这个 ticker 不另开 Handler
                    NightScreen.tick(sigNow, s)
                }

                delay(1000L - (nowMs % 1000L))
            }
        }
    }

    /**
     * 昼夜判定变了就整套重刷并同步高德；另外每 5 分钟无条件补发一次高德昼夜。
     *
     * 高德不会主动回来问系统昼夜，重启/被强停后停在它自己的默认值，
     * 所以除了"切换瞬间"，还要周期性补发 —— 成本是一条本地广播，可以忽略。
     *
     * v6.0.50：判定本身按「结论会不会变」节流，不再每秒 new Calendar：
     *  | theme.mode | 何时真的判一次 |
     *  |---|---|
     *  | `time` | 分钟边界（一天 1440 次，原 86400 次） |
     *  | `auto` 且车灯有值 | 车灯 0/1/-1 变化时（常态 0 次） |
     *  | `auto` 且车灯未知 | 退回落日/日出时间 → 同 `time`，按分钟 |
     *  | `night` / `day` | 只在配置快照换过（改过设置）时 |
     *
     * 桌面长时间在后台跨过昼夜边界也没问题：回来时分钟标记必然不同，
     * 下一秒就会重判并整套重刷。
     *
     * v6.0.82：本方法**不再受桌面可见性约束**（调用点在 ticker 的 UI 分支之外）。
     * 桌面被高德全屏盖住时 `lifecycle` 是 CREATED，但 ticker 协程仍在跑；旧版把
     * 判定塞在 `if (uiActive)` 里，于是「在高速上开灯 → 高德不变色，退一次桌面
     * 再进高德才变」。现在判定与高德广播照常，只有 [bindAll] 这一步按 [uiActive]
     * 分流：不可见时置 [nightPendingRebind]，回桌面由 [onResume] 补一次重绘。
     */
    private fun syncNight(sig: CarSignal, s: ConfigSnapshot, nowMs: Long, uiActive: Boolean) {
        // 快照是不可变对象，换引用就代表设置被改过 → 重读模式并强制重判一次
        if (s !== nightSnap) {
            nightSnap = s
            nightMode = s.str("theme.mode", "auto")
            nightJudged = false
        }
        val minute = nowMs / 60_000L
        val lamp = sig.night
        val clockDriven = nightMode == "time" || (nightMode == "auto" && lamp < 0)
        val needJudge = !nightJudged || (when {
            nightMode == "night" || nightMode == "day" -> false
            // 时间流逝只在分钟边界算；但车灯值一变（含「读不到→回落时间」）立刻算，
            // 否则 auto 模式在车灯丢失的那一分钟里会一直沿用旧结论。
            clockDriven -> minute != nightMinute || lamp != nightLamp
            else -> lamp != nightLamp
        })
        if (needJudge) {
            nightJudged = true
            nightMinute = minute
            nightLamp = lamp
            val night = NightMode.isNight(s, sig)   // 唯一会创建 Calendar 的地方
            if (night != nightNow) {
                if (uiActive) {
                    bindAll(s)   // 里面已经带一次 AmapNightSync
                } else {
                    // 桌面看不见：不重绘，但高德必须立刻收到 —— 这正是本 bug 的修复点
                    nightNow = night
                    nightPendingRebind = true
                    AmapNightSync.apply(this, night, s.bool("theme.amapSync", true), force = true)
                }
                amapTick = 0
                return
            }
        }
        // 高德 5 分钟补发：只在分钟边界累加，别每秒 ++、也别每秒读配置
        if (minute != lastAmapMinute) {
            lastAmapMinute = minute
            if (++amapTick >= 5) {
                amapTick = 0
                AmapNightSync.apply(this, nightNow, s.bool("theme.amapSync", true), force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 胎压告警的弹窗只属于桌面：回到前台才挂回调，切走立刻摘掉
        // （判定与播报在 Application 里一直跑，这里只管「有没有界面可以弹」）
        TireAlert.setListener { a -> runCatching { TireAlarm.show(this, a) } }
        // 回到桌面：夜间息屏重新计时；若还停在息屏态（系统点亮后又切回来）先恢复亮度
        NightScreen.onFront(this, snap)
        // 后台可能跨过日出/日落、车灯也换过值：让下一秒 ticker 强制重判一次昼夜，
        // 避免「页面不可见时跳过判定」把旧结论带回来。
        nightJudged = false
        // 6.0.82：桌面被高德盖住期间已经判出翻转（高德那边也已经变色），但 View 没重绘过。
        // 这里必须补一次 bindAll —— 上面的 nightJudged=false 重判只会得到「没变」，
        // 不补就会出现「高德夜间、回到桌面还是白天」。
        if (nightPendingRebind) {
            snap?.let {
                nightPendingRebind = false
                bindAll(it)
            }
        }
        // 覆盖层挂上/掉下来会广播，收到立刻重算 dock 显隐
        if (!rxOn) {
            runCatching { registerReceiver(overlayStateRx, IntentFilter(NavOverlay.ACTION_STATE)) }
            rxOn = true
        }
        // 覆盖原厂导航栏：桌面在前台时**要显示**覆盖层（桌面自己的 dock 已让位），
        // 所以这里放开而不是收起；只有设置页才压住它。
        NavOverlay.setSuppressed(this, false)
        NavOverlay.sync(this)
        // 回到桌面：导航信息收起悬浮窗（车速卡右侧已经有同一份信息，不能两份都挂）
        NavInfoOverlay.desktopFront = true
        NavInfoOverlay.sync(this)
        // 从设置页回来：开关可能刚被打开/关掉，dock 显隐要跟着变
        // （覆盖层是异步挂窗的，这里再排几次复查，挂上了才继续让位）
        applyDockVisibility()
        scheduleDockChecks()
        // 自动化：开机任务（只在「刚开机」窗口内补跑一次）+ 悬浮按钮与手势按新配置重挂
        runCatching { BootTask.onHomeReady(this) }
        runCatching { FloatButton.sync(this) }
        runCatching { EdgeGesture.sync(this) }
        // 权限激活面板还开着：从系统授权页回来后按最新状态重画
        runCatching { PermPanel.onHostResume() }
        // ★ 6.0.89：权限检测只在新装 / 更新后跑一次（PermAuto 内部按 versionCode 门控）。
        //   同版本重启、转屏、从设置页回来都不再检测，也不再每次回桌面静默重启 adbd。
        //   PermAuto 内部有进程闸门 + 版本闸门双保险，这里每次 onResume 调它是安全的。
        runCatching {
            PermAuto.onColdStart(this) { miss, detail ->
                PermPanel.promptManager(this, miss, detail)
            }
        }
        // 面板刷新延后到 4.5s：给后台授权留时间，别一进桌面就误报「缺 8 项」。
        // 6.0.88：缺失提示改由 PermAuto 在冷启动授权链真正结束后统一弹，
        // 这里不再调 promptIfNeeded，避免和「打开应用管家」引导叠两张弹窗。
        window?.decorView?.postDelayed({
            if (!isFinishing && !isDestroyed) runCatching {
                PermPanel.onHostResume()
            }
        }, 4500)
        // 设置页里换过壁纸 / 恢复默认后，回到桌面按新配置重画底图
        applyWallpaper()
        // 从设置页回来时停靠栏可能刚被改过（设置页也能换格子），重建一次免得显示旧图标
        theme?.let { dock.applyTheme(it) }
        // 设置页里改过「发动机累计运行时长」：回来立刻按新值重算机油寿命
        sixGrid.bind(SignalHub.current())
        // 刚装/卸载过应用也一并反映到第 2 页
        appPage.reload()
        // 从高德/设置页回来补发一次，避免高德刚启动时还停在它自己的默认昼夜
        AmapNightSync.apply(this, nightNow, snap?.bool("theme.amapSync", true) ?: true, force = true)
        // 清理完回到桌面：重新读一次剩余空间（机油卡右侧那一块）
        StorageAlert.check(this) { sixGrid.bind(SignalHub.current()) }
        // 覆盖安装完进程没被杀 / 刚装完回到桌面：按已装版本号补删下载的安装包
        UpdateManager.onFront(this)
        // 从「允许安装未知应用」设置页回来：权限一开就自动接着装，不用再点一次更新
        val resumed = UpdateManager.resumeInstall(this)
        if (resumed.isNotEmpty()) {
            Toast.makeText(this, resumed, Toast.LENGTH_LONG).show()
            statusBar.refreshUpdate()
        }
        // 系统安装器 / 应用管家给过结果（成功、取消、被拦截）：补一条看得清的提示
        val installMsg = UpdateManager.installResult()
        if (installMsg.isNotEmpty()) {
            UpdateManager.clearInstallResult()
            Toast.makeText(this, installMsg, Toast.LENGTH_LONG).show()
            statusBar.refreshUpdate()
        }
        // 天气按 GPS 判城市：刚在设置页授权（或首次装完授权）后立刻重拉一次，
        // 不用等下一轮 20 分钟的定时刷新
        if (LocPerm.granted(this) && weather?.state?.value == null) weather?.refreshNow()
        // 有待更新版本：刷一次状态栏徽标，并补一次弹窗（从设置页 / 安装器回来也能看到）
        statusBar.refreshUpdate()
        root.postDelayed({
            if (!isFinishing) UpdateManager.pendingVer()?.let { promptUpdate(it) }
        }, 4000)
    }

    // 离开桌面（切到导航/音乐等）时**不做任何事**：覆盖层现在就是这条侧栏本身，
    // 它要一直留在屏幕上；压住它的只有设置页（见 SettingsActivity.onResume）。

    companion object {
        private const val MATCH = -1

        /** 顶部下滑手势的起点范围：我们画的状态栏是 72dp，给到 96dp 更好按。 */
        private const val TOP_PULL_DP = 96

        /** 判定「这是一次要拉状态栏的下滑」所需的最小位移。 */
        private const val TOP_PULL_MIN_DP = 52
    }
}
