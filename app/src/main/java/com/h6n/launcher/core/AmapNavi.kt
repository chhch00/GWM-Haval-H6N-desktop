package com.h6n.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import android.util.LruCache
import com.h6n.launcher.H6NApplication
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 高德地图车机版的红绿灯 / 车道数据源。
 *
 * ## 数据从哪来
 * 高德车机版对外发一套标准广播 `AUTONAVI_STANDARD_BROADCAST_SEND`，用 `KEY_TYPE` 分类：
 *
 * | KEY_TYPE | 内容 | 本模块怎么用 |
 * |---|---|---|
 * | 10001 | 引导 / 巡航信息 | `NEW_ICON`/`ICON` ≠ 0 判「导航中」，超时 6s 判「导航结束」 |
 * | 60073 | 红绿灯 | 导航态读 `trafficLightStatus`/`dir`/`redLightCountDownSeconds`；<br>巡航态读 `lightsData`（JSON 数组，多灯） |
 * | 13012 | 车道 | `EXTRA_DRIVE_WAY`（JSON，含 `drive_way_info[]`） |
 * | 10019 | 状态 | `EXTRA_STATE`：3 高德前台 / 4 高德后台 / 9 导航结束 / 25 巡航结束 |
 *
 * ## 为什么必须动态注册
 * 高德发的是**隐式**广播，8.0 之后（targetSdk ≥ 26）静态清单里注册的接收器收不到；
 * 本应用 targetSdk=27，只能代码注册。接收器挂在 `Application` 上，桌面是 HOME 进程，
 * 只要系统不杀就一直能收 —— 这也是为什么悬浮窗不需要单独的常驻服务。
 *
 * ## 值的语义（踩过坑，别想当然）
 * 红绿灯 `status` 在**导航态和巡航态编号不同**：
 * - 导航态：`4` 绿、`1` 红、其余黄；
 * - 巡航态（`lightsData[]` 里）：`1` 绿、`0` 红、其余黄。
 *
 * `[Light.navi]` 就是用来区分这两种编号的，显示层别自己猜。
 *
 * ## 6.0.52：只显示当前路口的红绿灯
 * 用户要求「当前路口有灯就显示，没有就不显示，不要跨越路口显示下一个路口的灯」。
 * 60073 广播里**没有路口编号，也没有到灯距离**，所以数据层用「本帧有没有有效灯」
 * 来界定当前路口：
 * - 收到无效 / 空灯包 → **立刻清掉**旧的 `lightVal`，不等超时（旧版什么都不做，
 *   上一个路口的灯会带着 5 秒存活期一直挂到下一个路口）；
 * - 巡航多灯 `lightsData` 是**同一路口的多个方向灯**（参考工程会并排全画出来），
 *   只在这一组里选，这一组全无效就返回 null，绝不往后找下一组；
 * - 巡航态不认「导航单灯」包（双高德共存时串过来的），导航态不认巡航多灯包；
 * - 存活期 5s → 2s，过完路口尽快撤掉，只作为高德彻底停推时的兜底。
 *
 * ## 6.0.53：用「到灯距离」界定当前路口，并把距离显示在胶囊右边
 * 6.0.52 只按「本帧有没有有效灯」判定，实车仍然会提前显示下一个路口的灯 —— 因为
 * 高德推过来的那一帧本身就是有效的灯，只是物理上还在很远的路口。60073 不带路口编号，
 * **但 10001 带 `SEG_REMAIN_DIS`（到下一个路口的米数）**，实测导航时每秒两条，
 * 与 60073 的一秒一条天然对齐。于是：
 * - 60073 的灯先尝试读广播自带的距离键（不同高德版本字段名不一，列了一组候选），
 *   读不到就用最近一帧 10001 的 `SEG_REMAIN_DIS`；
 * - 距离已知且 > `navi.lightMaxDis`（默认 800 米，设置页可调，0 = 关闭门控）
 *   → 判为「下一个路口的灯」，这一帧直接清空、不上屏；
 * - 距离未知（巡航态没有 SEG_REMAIN_DIS）→ 不做门控，保持 6.0.52 的行为，避免误杀；
 * - 上屏的灯带 [Light.distanceM] / [Light.distanceText]，显示层在胶囊右边画「距灯 130米」。
 *
 * ★ 边界：`SEG_REMAIN_DIS` 语义是「到下一个**转向点**」，若灯在更近的直行路口，
 *   这个数会偏大；门控因此做成可调档位，实车上发现误杀可以调大或直接关掉。
 *
 * ## 6.0.55：「距灯 现在」与仍然跨路口
 * 两件事同一个根：`SEG_REMAIN_DIS_AUTO` 是**导航提示文案**，不是纯距离串。临近
 * 转向点高德会给「现在」（导航卡那个大字就是它），6.0.53 把它原样存进 `segDisText`
 * 当灯距画出来，于是胶囊右边出现「距灯 现在」；同时 `SEG_REMAIN_DIS` 在「现在」
 * 时≈0，800 米门控必然放行 —— 而车正在通过的路口已经不是这盏灯，高德此刻推的是
 * **更前面**路口的灯，于是依然跨路口。
 * - 距离文本一律由米数经 [formatDis] 生成，文案解析不出米数 = 距离未知，不画灯距、
 *   也不做门控（显示层零改动，`Info.segDis` 大字仍原样显示「现在」）；
 * - 门控默认 800 → 300 米，档位补 100 米；
 * - 新增「路口到达边沿」：距离从远跳到 ≤80 米（或文案是「现在」这类到达词）时，
 *   认定车正在通过当前路口，立刻撤灯并进入静默；静默期间高德推来的灯一律判为
 *   下一路口，直到距离重新拉开到 >250 米（驶离）或 90 秒兜底超时；
 * - 静默与门控同一个开关：设置里选「关闭」= 完全退回 6.0.52 的行为。
 *
 * ★ 取舍：车停在红灯前等 60 秒时「现在」会一直成立，当前路口的灯也会被静默挡掉
 *   （宁可少显示，不误显示，延续 6.0.52 原则）；现场若觉得挡多了，把门控调大一档
 *   或关掉即可。
 *
 * ## 线程
 * 广播回调在主线程；[snapshot] 与各时间戳都是 `@Volatile`，订阅方（[addListener]）
 * 一律在主线程回调，可以直接刷 UI。监听只在快照**真的变了**的时候触发，
 * 高德导航时 10001 一秒好几条，不去重的话会把 UI 刷穿。
 */
object AmapNavi {

    private const val TAG = "H6AmapNavi"

    const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
    const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"

    /** 高德地图车机版的包名（`高德地图车机版` / AutoNavi）。 */
    const val PKG = "com.autonavi.amapauto"

    private const val K_INFO = 10001
    private const val K_LIGHT = 60073
    private const val K_LANE = 13012
    private const val K_STATE = 10019
    /** 向高德主动要一次车道数据（没收到推送时的兜底）。 */
    private const val K_ASK_LANE = 10062

    /**
     * 红绿灯这么久没更新就撤掉。
     *
     * v6.0.52：5s → 2s。实机 logcat 里 60073 **每秒都发**，哪怕两个路口之间、
     * 当前路口根本没有灯；5 秒足够把上一个路口的灯带到下一个路口。现在本帧
     * 无效会立刻清（见 [handleLocked]），超时只兜底高德彻底停推的情况，2 秒足够，
     * 也不会把正常 1s 一推的灯闪掉。
     */
    private const val LIGHT_KEEP_MS = 2_000L
    /** 车道数据更新频率比红绿灯低（多在路口前才推），留更长的存活期。 */
    private const val LANE_KEEP_MS = 15_000L
    /** 没有 10001 这么久 = 导航结束。 */
    private const val NAVI_KEEP_MS = 6_000L
    /** 路线 / 转向信息的存活期（巡航时高德同样每秒发包）。 */
    private const val INFO_KEEP_MS = 6_000L
    /** 主动要车道的最小间隔。 */
    private const val ASK_GAP_MS = 3_000L

    /**
     * 距下一个路口超过这个米数还报灯 = 判成「下一路口」的灯，不上屏。
     * 设置页「高德导航信息 → 红绿灯距离门控」写 `navi.lightMaxDis`，0 = 关闭门控。
     *
     * 6.0.55：800 → 300。实车高德常在几百米外就推下一路口的灯，800 米等于没拦。
     */
    private const val LIGHT_DIS_DEF = 300

    /** 6.0.55：「车正在这个路口」的近距阈值（米）。 */
    private const val PASS_NEAR_M = 80

    /** 距离重新拉开到这么多米，认定车已驶离路口、解除静默。 */
    private const val PASS_CLEAR_M = 250

    /** 静默兜底：高德长时间停在「现在」也不能把灯永久屏蔽。 */
    private const val PASS_MAX_MS = 90_000L

    /** `SEG_REMAIN_DIS_AUTO` 里的到达文案（不是距离，主卡大字会原样显示它）。 */
    private val ARRIVAL_WORDS = arrayOf("现在", "即将", "马上", "立即", "即刻")

    /**
     * 60073 里可能存在的「到灯距离」字段名（不同高德车机版 / 不同适配层不一样）。
     * 官方协议文档只列了 4 个字段，这里属于**兼容探测**：读不到就回退 10001 的
     * `SEG_REMAIN_DIS`，不会因为没有这些键而报错或误判。
     */
    private val LIGHT_DIS_KEYS = arrayOf(
        "trafficLightDistance", "lightDistance", "distanceToLight",
        "traffic_light_distance", "distance", "dis", "remainDis"
    )

    /**
     * 6.0.105：巡航灯对象的键名候选。
     *
     * 官方协议文档只写了一套名字，但实车上不同高德车机版 / 适配层给的是另一套 ——
     * 只认死一套就会「整帧读不出灯」，表现正是巡航态永远没有红绿灯。
     * 这里按顺序探测，第一个存在的键说了算。
     */
    private val LIGHT_STATUS_KEYS = arrayOf(
        "status", "lightStatus", "light_status", "state", "lightColor"
    )
    private val LIGHT_CD_KEYS = arrayOf(
        "countdown", "lightCountdown", "light_countdown", "remainTime",
        "redLightCountDownSeconds", "restTime", "time"
    )
    private val LIGHT_DIR_KEYS = arrayOf(
        "dir", "direction", "lightDir", "light_dir"
    )

    /** `lightsData` 被包一层时，外层可能用的键名。 */
    private val CRUISE_ARR_KEYS = arrayOf(
        "lightsData", "lights_data", "lights", "lightData", "data"
    )

    /**
     * 高德在前台时，超时巡检从 1 秒降到 5 秒。
     *
     * 此时桌面被高德整屏盖住，红绿灯/车道/导航卡一个都看不见，唯一还要做的是
     * 「确认高德什么时候退回后台」。降频不影响体感（回桌面时高德每秒都在发包，
     * 而且 [H6A11y.onForeground] 会主动叫一次 [onForegroundChanged]）。
     */
    private const val PAUSE_TICK_MS = 5_000L
    /** 无障碍读不到前台包名时的死锁兜底：暂停中这么久一条包都没有，就退回正常模式。 */
    private const val PAUSE_IDLE_MAX_MS = 90_000L

    /**
     * 转向图标编号（高德 `NEW_ICON`）。这些值由 Navi-Link 实测确认过，别自己改：
     * `2` 左转、`3` 右转、`4` 左前方、`5` 右前方、`8` 掉头、`9` 直行、
     * `10` 途经点、`11` 进匝道、`12` 出匝道、`15` 终点。
     */
    const val TURN_LEFT = 2
    const val TURN_RIGHT = 3
    const val TURN_LEFT_FRONT = 4
    const val TURN_RIGHT_FRONT = 5
    const val TURN_UTURN = 8
    const val TURN_STRAIGHT = 9
    const val TURN_WAYPOINT = 10
    const val TURN_RAMP_IN = 11
    const val TURN_RAMP_OUT = 12
    const val TURN_END = 15

    /**
     * 一盏灯。`navi` 决定 [status] / [dir] 用哪套编号（见类注释）。
     *
     * v6.0.53 新增 [distanceM] / [distanceText]：到这盏灯的近似距离（米 / 带单位文本）。
     * `-1` / 空串 = 高德没给距离（巡航态常见），此时不做距离门控、也不画距离文字。
     */
    data class Light(
        val status: Int,
        val countdown: Int,
        val dir: Int,
        val navi: Boolean,
        val distanceM: Int = -1,
        val distanceText: String = ""
    )

    /** 一条车道。`icon` 是高德给的图标编号，运行时按 `lane_pdf_<icon>` 取图。 */
    data class Lane(val icon: String, val advised: Boolean)

    /**
     * 10001 里的路线 / 转向信息。
     *
     * 距离和时间**直接用高德给的 `_AUTO` 文本**（形如 `"300米"` / `"12.4公里"` / `"18分钟"`），
     * 不自己按米数换算 —— 高德已经做过取整和单位切换（1 公里以内给「米」），
     * 自己算反而会在 999/1000 米之间跳。
     */
    data class Info(
        /** 转向图标编号（见 [TURN_*]）。0 = 巡航，没有转向。 */
        val icon: Int = 0,
        /** 到下一个转向点的距离，带单位。 */
        val segDis: String = "",
        /** 下一步要进入的道路名。 */
        val nextRoad: String = "",
        /** 当前所在道路名（巡航时也有）。 */
        val curRoad: String = "",
        /** 全程剩余距离，带单位。 */
        val remainDis: String = "",
        /** 全程剩余时间，带单位。 */
        val remainTime: String = "",
        /**
         * 预计到达文案。★ 实机 `ETA_TEXT` 是「预计18:14到达」**整句**，
         * 不是纯时刻 `"08:42"`；显示层别再套一层「预计 … 到达」（6.0.54 已修）。
         */
        val eta: String = "",
        /** 全程进度 0..100，-1 = 高德没给总里程，画不出进度。 */
        val progress: Int = -1,
        /** 高德报的车速（km/h），-1 = 没有。悬浮窗没有车机车速时用它。 */
        val speed: Int = -1
    )

    /** 某一时刻可用于上屏的全部导航信息。 */
    data class Snapshot(
        val navi: Boolean,
        val light: Light?,
        val lanes: List<Lane>,
        val info: Info = Info()
    ) {
        /**
         * 有没有任何值得显示的内容（决定悬浮窗要不要出现）。
         *
         * 导航中也算：转向图标 / 剩余距离本身就是信息，哪怕这一段没有红绿灯和车道。
         */
        val hasData: Boolean get() = navi || light != null || lanes.isNotEmpty()

        companion object {
            val EMPTY = Snapshot(false, null, emptyList())
        }
    }

    // ---------------- 可变状态 ----------------

    @Volatile
    private var snap: Snapshot = Snapshot.EMPTY

    @Volatile
    private var lightVal: Light? = null

    @Volatile
    private var laneVal: List<Lane> = emptyList()

    @Volatile
    private var infoVal: Info = Info()

    @Volatile
    private var lightAt = 0L

    @Volatile
    private var infoAt = 0L

    /**
     * 最近一帧 10001 报的「到下一个路口」距离。
     * 60073 每秒一条、10001 每秒两条，两者天然对齐；60073 里没有距离字段时用它兜底。
     * 超时（[INFO_KEEP_MS]）就当未知，避免拿很久以前的路口距离去做门控。
     */
    @Volatile
    private var segDisM = -1

    @Volatile
    private var segDisText = ""

    @Volatile
    private var segDisAt = 0L

    /** 6.0.55：车正在通过 / 刚通过路口，期间不收任何灯包（它们属于更前面的路口）。 */
    @Volatile
    private var passActive = false

    @Volatile
    private var passAt = 0L

    /** 上一帧 10001 报的路口距离，用来识别「从远到近」的到达边沿。 */
    @Volatile
    private var lastSegM = -1

    @Volatile
    private var laneAt = 0L

    /** 最近一次「确认在导航」的时间（只认 10001 里 ICON≠0 的包）。 */
    @Volatile
    private var naviAt = 0L

    @Volatile
    private var askAt = 0L

    /** 高德自己报的前后台（10019 的 3/4）。无障碍没开时靠它判「高德在前台」。 */
    @Volatile
    private var amapFront = false

    /**
     * 高德在前台 → 暂停取数。
     *
     * 高德整屏前台时桌面的导航卡、红绿灯、车道都不可见，悬浮窗按规则也不显示
     * （见 [NavInfoOverlay.wantShow]），这段时间解析 JSON、建对象、通知订阅方全是白烧。
     * 所以收到包只做一件事：确认高德是否已经退回后台，其余直接丢。
     */
    @Volatile
    private var paused = false

    /** 最近一次收到任何高德广播的时间（暂停中的死锁兜底用）。 */
    @Volatile
    private var lastRxAt = 0L

    /** 进入暂停的时刻（一条包都还没收到过时，用它算兜底超时）。 */
    @Volatile
    private var pausedAt = 0L

    // ---------------- 诊断（设置页直接显示） ----------------

    @Volatile
    var registered: Boolean = false
        private set

    /** 累计收到的广播条数。一直是 0 = 高德没发 / 收不到（权限或版本问题）。 */
    @Volatile
    var rxCount: Int = 0
        private set

    @Volatile
    var lastKeyType: Int = 0
        private set

    @Volatile
    var lastError: String = ""
        private set

    /**
     * 最近一帧 60073 的原始内容（巡航取 `lightsData`，导航取状态/倒计时）。
     *
     * 只留前 300 个字符，给设置页「诊断」用：巡航态没有红绿灯时，把这一行发回来
     * 就能看出是高德根本没给灯，还是给了但字段名不在我们的候选表里。
     */
    @Volatile
    var lastLightRaw: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var appCtx: Context? = null
    private var tickerRunning = false
    private var firePending = false

    /**
     * 广播解析线程（v6.0.43）。
     *
     * `onReceive` 默认跑在主线程，而高德导航时 10001 每秒好几条，解析里还有
     * `JSONObject/JSONArray`（车道、多灯）和一堆对象分配 —— 全压在主线程上，
     * 正好和桌面的绘制帧抢同一个线程。这里改成：主线程只做「取 KEY_TYPE、
     * 判暂停、投递」，解析 + 建快照在后台单线程串行跑，算完再回主线程通知。
     *
     * 串行是必要的：广播之间有先后依赖（比如导航结束包必须先于下一条 10001 生效）。
     */
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    /** 解析结果与 [publish] 的互斥锁：后台解析写状态、主线程 ticker 也在重算快照。 */
    private val lock = Any()

    // ---------------- 对外读取 ----------------

    fun snapshot(): Snapshot = snap

    /** 高德当前是不是在前台（无障碍读不到前台包名时的兜底）。 */
    fun amapForeground(): Boolean = amapFront

    /**
     * 前台应用换了 —— 由 [H6A11y.onForeground] / [H6A11y.onDisconnected] 调用。
     *
     * 不必等下一次广播：切到高德就立刻停，退回桌面就立刻恢复。
     */
    fun onForegroundChanged() {
        syncPause()
    }

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    // ---------------- 开关（ui_config 的 navi 段） ----------------

    private fun conf(ctx: Context) =
        (ctx.applicationContext as? H6NApplication)?.config?.current()

    fun enabled(ctx: Context): Boolean = conf(ctx)?.bool("navi.enabled", true) ?: true

    fun lightOn(ctx: Context): Boolean =
        enabled(ctx) && (conf(ctx)?.bool("navi.light", true) ?: true)

    fun laneOn(ctx: Context): Boolean =
        enabled(ctx) && (conf(ctx)?.bool("navi.lane", true) ?: true)

    fun overlayOn(ctx: Context): Boolean =
        enabled(ctx) && (conf(ctx)?.bool("navi.overlay", true) ?: true)

    // ---------------- 生命周期 ----------------

    /**
     * 注册广播接收器。幂等 —— `H6NApplication.onCreate` 调一次即可。
     *
     * ★ 一定要 `RECEIVER_EXPORTED`：广播来自**别的应用**（高德），
     *   用 `RECEIVER_NOT_EXPORTED` 会一条都收不到，而且不报错，极难查。
     */
    fun start(app: Context) {
        val ctx = app.applicationContext
        appCtx = ctx
        if (registered) return
        val r = runCatching {
            val f = IntentFilter(ACTION_SEND)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(rx, f)
            }
            true
        }
        registered = r.getOrDefault(false)
        if (!registered) {
            lastError = "注册广播失败：${r.exceptionOrNull()?.javaClass?.simpleName} " +
                (r.exceptionOrNull()?.message ?: "")
            Log.w(TAG, lastError)
        }
        ensureWorker()
        startTicker()
        // 应用刚起来时高德可能已经在前台（系统把桌面拉起又压回去），先判一次
        syncPause()
    }

    /** 懒建解析线程：`start()` 一定会调，这里只是兜底防重。 */
    private fun ensureWorker(): Handler {
        worker?.let { return it }
        val ht = HandlerThread("h6-amap-rx", Process.THREAD_PRIORITY_BACKGROUND)
        ht.start()
        val h = Handler(ht.looper)
        workerThread = ht
        worker = h
        return h
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null || i.action != ACTION_SEND) return
            lastRxAt = SystemClock.elapsedRealtime()
            val k = i.getIntExtra("KEY_TYPE", 0)

            // 10019 自己就带前后台状态，先更新再判暂停，否则从「高德刚进前台」那一刻起
            // 要多等一轮才发现（高德前台时每秒都在发 10001）。
            if (k == K_STATE) {
                when (i.getIntExtra("EXTRA_STATE", -1)) {
                    3 -> amapFront = true
                    4 -> amapFront = false
                }
            }

            syncPause()

            // ★ 暂停时只放行 10019：其余包直接丢在解析之前 —— 不读 extras、不建
            //   Snapshot、不解析车道 JSON、不通知订阅方。高德导航时 10001 每秒好几条，
            //   这部分开销在整屏不可见的场景下纯属浪费。
            if (paused && k != K_STATE) return

            // ★ v6.0.43：解析整体挪到后台线程，主线程只剩上面这几行。
            //   `post` 之后不能再碰 i 之外的共享变量；Intent 由系统为每个接收者单独
            //   clone（BroadcastQueue 的 `new Intent(intent)`），可以安全跨线程读。
            ensureWorker().post {
                // 排队期间可能刚进了暂停（高德抢到前台）：这时除了 10019 一律丢，
                // 别让暂停前那一两条包在 clearAll() 之后又把旧数据点亮。
                if (paused && i.getIntExtra("KEY_TYPE", 0) != K_STATE) return@post
                try {
                    handle(i)
                } catch (t: Throwable) {
                    lastError = "解析广播失败：${t.javaClass.simpleName} ${t.message}"
                    Log.w(TAG, lastError)
                }
            }
        }
    }

    // ---------------- 解析 ----------------

    private fun handle(i: Intent) {
        // 与 [publish] 同一把锁：主线程 ticker 不能看到「时间戳已更新、值还没写」的中间态
        synchronized(lock) { handleLocked(i) }
    }

    private fun handleLocked(i: Intent) {
        rxCount++
        val k = i.getIntExtra("KEY_TYPE", 0)
        lastKeyType = k
        val now = SystemClock.elapsedRealtime()

        when (k) {
            K_STATE -> {
                when (i.getIntExtra("EXTRA_STATE", -1)) {
                    // 3/4 已在 [rx] 里先更新过（判暂停要用），这里不重复赋值
                    // 导航 / 巡航结束：数据立刻作废，别等 5~15 秒超时
                    9 -> clearAll()
                    25 -> {
                        laneAt = 0L
                        laneVal = emptyList()
                        // 巡航结束：保留最后一条路线信息会让悬浮窗停在「上一个路口」上，
                        // 但导航还在跑时不能清（高德偶尔把 25 也发出来），所以只清非导航
                        if (now - naviAt > NAVI_KEEP_MS) {
                            infoAt = 0L
                            infoVal = Info()
                        }
                    }
                }
                publish()
            }

            K_INFO -> {
                // 有转向图标 = 导航中；没有 = 巡航。
                // ★ 导航态只能由 ICON≠0 建立：巡航包同样每秒来一条，拿它续命会让
                //   「导航结束」永远判不出来（高德后台巡航是常态）。
                val icon = intExtra(i, "NEW_ICON", 0).let { if (it != 0) it else intExtra(i, "ICON", 0) }
                val inNavi = naviAt > 0L && now - naviAt <= NAVI_KEEP_MS
                if (icon == 0 && inNavi) {
                    // 双高德共存（预装巡航 + 改装导航）时导航中会混进巡航包，
                    // 巡航包不带 SEG_REMAIN / NEXT_ROAD_NAME，照收会把导航信息冲成空。
                    return
                }
                if (icon != 0) naviAt = now

                // ★ v6.0.53：记下「到下一个路口」的距离，给 60073 的红绿灯当灯距 / 门控用。
                // 优先数值 SEG_REMAIN_DIS，个别版本只给 _AUTO 文本（"130米" / "1.2公里"）。
                //
                // ★ v6.0.55：`_AUTO` 是**导航提示文案**，临近转向点会给「现在」（主卡
                // 那个大字就是它）。6.0.53 把它原样塞进 segDisText，胶囊右边就成了
                // 「距灯 现在」。现在距离文本一律由米数生成，文案认不出米数 = 距离未知。
                val segAuto = strExtra(i, "SEG_REMAIN_DIS_AUTO")
                val segRaw = intExtra(i, "SEG_REMAIN_DIS", -1)
                val segM = if (segRaw >= 0) segRaw else parseAutoDisM(segAuto)
                segDisM = segM
                segDisText = formatDis(segM)
                segDisAt = if (segM >= 0) now else 0L

                // ★ v6.0.55：路口到达边沿。「现在」时 segM≈0，距离门控必然放行，
                // 但此刻高德推来的灯已经是**更前面**路口的 —— 距离门控救不了，
                // 只能按「车已经在这个路口」整体撤灯并静默，直到驶离。
                //
                // ★ 6.0.105：「路口静默」只在**导航态**成立。巡航包里 icon=0，且
                //   `SEG_REMAIN_DIS` 常常就是 0 / 缺失、「现在」这类文案也会出现，
                //   于是巡航一开始就被判成「正在通过路口」并静默 90 秒；更糟的是
                //   巡航时 segM 永远拉不到 PASS_CLEAR_M 以上，静默到期又立刻重新触发 ——
                //   表现就是「巡航状态永远不显示红绿灯」。导航态才需要这套门控。
                val gateOn = lightMaxDis() > 0 && icon != 0
                // 文案里已经有明确米数（"300米"）时以文案为准，别把矛盾的数值当到达
                val autoM = parseAutoDisM(segAuto)
                val atJunction = gateOn && autoM < 0 &&
                    (segRaw in 0..PASS_NEAR_M || isArrivalWord(segAuto))
                if (!gateOn) {
                    passActive = false
                } else if (atJunction && !passActive &&
                    (lastSegM < 0 || lastSegM > PASS_NEAR_M)
                ) {
                    passActive = true
                    passAt = now
                    lightVal = null
                    lightAt = 0L
                } else if (!atJunction && segM > PASS_CLEAR_M) {
                    // 距离重新拉开 = 车已驶离这个路口，下一段路可以重新收灯
                    passActive = false
                }
                lastSegM = segM

                val remainM = intExtra(i, "ROUTE_REMAIN_DIS", -1)
                val allM = intExtra(i, "ROUTE_ALL_DIS", -1)
                infoVal = Info(
                    icon = icon,
                    segDis = segAuto,
                    nextRoad = strExtra(i, "NEXT_ROAD_NAME"),
                    curRoad = strExtra(i, "CUR_ROAD_NAME"),
                    remainDis = strExtra(i, "ROUTE_REMAIN_DIS_AUTO"),
                    remainTime = strExtra(i, "ROUTE_REMAIN_TIME_AUTO"),
                    eta = strExtra(i, "ETA_TEXT"),
                    // 高德只在全程导航时给 ROUTE_ALL_DIS；巡航 / 无目的地时为 0
                    progress = if (allM > 0 && remainM >= 0)
                        ((1f - remainM.toFloat() / allM) * 100f).toInt().coerceIn(0, 100)
                    else -1,
                    speed = intExtra(i, "CUR_SPEED", -1)
                )
                infoAt = now
                publish()
            }

            K_LIGHT -> {
                // ★ 6.0.55：车正在通过 / 刚通过路口。高德在这段时间推的是**更前面**
                // 路口的灯（当前路口已经过了），一律不收；否则距离≈0 时门控必然放行，
                // 就又成了「跨路口红绿灯」。门控选「关闭」时这里不生效。
                // ★ 6.0.105：静默只对导航态生效（巡航没有 SEG_REMAIN_DIS，判不出路口）。
                if (snap.navi && junctionPassing(now)) {
                    lightVal = null
                    lightAt = 0L
                    publish()
                    return
                }
                val lightsData = i.getStringExtra("lightsData")
                val cruise = !lightsData.isNullOrBlank()
                lastLightRaw = if (cruise) {
                    "cruise " + lightsData!!.take(300)
                } else {
                    "navi status=" + intExtra(i, "trafficLightStatus", -1) +
                        " cd=" + intExtra(i, "redLightCountDownSeconds", -1) +
                        " dir=" + intExtra(i, "dir", -1)
                }
                // 双高德共存（预装巡航 + 改装导航）时，导航态忽略巡航的多灯广播，避免串味。
                // 保持 return：导航单灯包仍在持续刷新 lightAt，灯不会被超时撤掉。
                if (snap.navi && cruise) return
                val raw = when {
                    cruise -> parseCruiseLight(lightsData!!)
                    // 巡航态高德只发 lightsData；没有它却带 trafficLightStatus 的包
                    // 是另一套高德串过来的导航残留，不认（与参考工程一致）。
                    snap.navi -> parseNaviLight(i)
                    else -> null
                }
                // ★ v6.0.53：先补上到灯距离，再做距离门控。
                // 6.0.52 只判「本帧有没有有效灯」，但高德提前推下一路口的灯时
                // 那一帧本身就是有效灯，所以还是会跨路口 —— 现在用物理距离拦。
                val cand = raw?.let { withDistance(it, now) }
                if (cand != null && lightVisible(cand)) {
                    lightVal = cand
                    lightAt = now
                } else {
                    // ① 6.0.52：本帧没有有效灯 = 当前路口没有红绿灯，立刻撤掉，
                    //    旧版空包什么都不做会让上一个路口的灯挂满存活期；
                    // ② 6.0.53：被距离门控拦下的「下一路口」灯同样立刻撤掉。
                    lightVal = null
                    lightAt = 0L
                }
                publish()
            }

            K_LANE -> {
                val s = i.getStringExtra("EXTRA_DRIVE_WAY")
                if (!s.isNullOrBlank()) {
                    laneVal = parseLanes(s)
                    laneAt = now
                    publish()
                }
            }
        }
    }

    /**
     * 巡航态多灯：`lightsData` 是**同一个路口的多个方向灯**（参考工程把它们并排全画出来），
     * 不是多个路口的灯。所以整帧只看这一组：全组倒计时归零 / 无效 = 当前路口没有灯，
     * 直接返回 null，绝不往后翻找下一组。
     *
     * v6.0.52 之前是「跳过 countdown<=0 的项、再挑倒计时最小的一盏」，当前路口的灯
     * 归零时会自动挪到数组里还活着的那一盏上；再叠加外面 5 秒残留，就成了跨路口显示。
     *
     * 选哪一盏：优先直行（巡航 `dir=2`，参考工程的提示音逻辑也这么选），没有直行就取
     * 这一组里第一盏有效的 —— 都还在同一个路口内。
     *
     * ★ 6.0.105：字段名按数组探测。实车上不同高德车机版的巡航灯对象键名并不统一
     *   （`status` / `lightStatus` / `state`，`countdown` / `remainTime` …），
     *   以前只认 `status`+`countdown` 两个名字，名字对不上就整帧被当成「没有灯」，
     *   于是巡航态永远不显示红绿灯。现在四个键各组都试一遍，读不到才算没有。
     */
    private fun parseCruiseLight(json: String): Light? {
        val arr = cruiseArray(json) ?: return null
        if (arr.length() == 0) return null
        var first: Light? = null
        var straight: Light? = null
        // 有些适配层会在灯对象里塞一个到灯距离；同一路口的一组灯共用它
        var groupDis = -1
        for (idx in 0 until arr.length()) {
            val o = arr.optJSONObject(idx) ?: continue
            if (groupDis < 0) groupDis = jsonDis(o)
            val cd = optIntAny(o, LIGHT_CD_KEYS, 0)
            val st = optIntAny(o, LIGHT_STATUS_KEYS, -1)
            if (cd <= 0 || st < 0 || st > 9) continue
            val cand = Light(
                status = st,
                countdown = cd,
                dir = optIntAny(o, LIGHT_DIR_KEYS, 2),
                navi = false
            )
            if (first == null) first = cand
            if (cand.dir == 2 && straight == null) straight = cand
        }
        val picked = straight ?: first ?: return null
        return if (groupDis >= 0)
            picked.copy(distanceM = groupDis, distanceText = formatDis(groupDis))
        else picked
    }

    /** `lightsData` 可能是数组本体，也可能被包一层（`{"lightsData":[…]}`）。两种都认。 */
    private fun cruiseArray(json: String): JSONArray? {
        if (json.isBlank()) return null
        runCatching { JSONArray(json) }.getOrNull()?.let { return it }
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        for (k in CRUISE_ARR_KEYS) {
            val a = o.optJSONArray(k) ?: continue
            return a
        }
        return null
    }

    /** 在一组候选键里取第一个能读成整数的值（读不到返回 [def]）。 */
    private fun optIntAny(o: JSONObject, keys: Array<String>, def: Int): Int {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.optInt(k, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
            val f = o.optString(k, "").trim().toFloatOrNull()
            if (f != null) return f.toInt()
        }
        return def
    }

    /**
     * 导航态单灯：`trafficLightStatus`（4 绿 / 1 红 / 其余黄）+ 倒计时。
     *
     * 60073 实测每秒都发，两个路口之间也会发一条「字段在、但没有灯」的空包
     * （status≤0、countdown=0）。旧版把它当黄灯画成一个 `--` 胶囊，整段路一直挂着 ——
     * 这正是「当前路口没有却还在显示」的来源。现在要求灯色有效且**有倒计时**才认，
     * 否则由 [handleLocked] 立刻清掉。代价：高德若给绿灯但完全不给倒计时，也不显示，
     * 宁可少显示，不误显示。
     */
    private fun parseNaviLight(i: Intent): Light? {
        val st = intExtra(i, "trafficLightStatus", -1)
        val cd = intExtra(i, "redLightCountDownSeconds", 0)
        if (st <= 0 || cd <= 0) return null
        return Light(
            status = st,
            countdown = cd,
            dir = intExtra(i, "dir", 4),
            navi = true,
            distanceM = lightExtraDis(i)
        )
    }

    // ---------------- v6.0.53 到灯距离 ----------------

    /** 60073 顶层里的距离字段（字段名按 [LIGHT_DIS_KEYS] 兼容探测，读不到返回 -1）。 */
    private fun lightExtraDis(i: Intent): Int {
        for (k in LIGHT_DIS_KEYS) {
            val v = intExtra(i, k, -1)
            if (v >= 0) return v
        }
        return -1
    }

    /** 巡航 `lightsData[]` 单项里的距离字段。 */
    private fun jsonDis(o: JSONObject): Int {
        for (k in LIGHT_DIS_KEYS) {
            val v = o.optInt(k, -1)
            if (v >= 0) return v
        }
        return -1
    }

    /**
     * `"130米"` / `"1.2公里"` / `"1.9km"` → 米；认不出返回 -1。
     *
     * 6.0.55：必须带距离单位（或整串纯数字）。否则「预计18:14到达」里的 18
     * 会被当成 18 米，导航提示文案也会混进距离里。
     */
    private fun parseAutoDisM(s: String): Int {
        if (s.isEmpty()) return -1
        val t = s.trim()
        val num = Regex("[0-9]+(\\.[0-9]+)?").find(t)?.value?.toFloatOrNull() ?: return -1
        val km = t.contains("公里") || t.contains("千米") || t.contains("km", true)
        val hasUnit = km || t.contains("米") || t.contains("m", true)
        val pureNum = Regex("^[0-9]+(\\.[0-9]+)?$").matches(t)
        if (!hasUnit && !pureNum) return -1
        val m = if (km) num * 1000f else num
        return if (m >= 0f) m.toInt() else -1
    }

    /** 6.0.55：`SEG_REMAIN_DIS_AUTO` 是不是「马上到路口」的提示文案（不是距离）。 */
    private fun isArrivalWord(s: String): Boolean {
        if (s.isEmpty() || parseAutoDisM(s) >= 0) return false
        return ARRIVAL_WORDS.any { s.contains(it) }
    }

    /**
     * 6.0.55：静默期判定。门控关闭或超时都会自动解除，不会把灯永久屏蔽。
     * 触发边沿在 10001 的解析里（距离从远跳到近 / 文案出现「现在」）。
     */
    private fun junctionPassing(now: Long): Boolean {
        if (lightMaxDis() <= 0) {
            passActive = false
            return false
        }
        if (!passActive) return false
        if (now - passAt > PASS_MAX_MS) {
            passActive = false
            return false
        }
        return true
    }

    /**
     * 米 → 带单位文本。
     * 6.0.55 起这是**唯一**的灯距文本来源：`SEG_REMAIN_DIS_AUTO` 的原文只用于
     * 导航卡大字（`Info.segDis`），不再透传到「距灯 XXX」里。
     */
    private fun formatDis(m: Int): String {
        if (m < 0) return ""
        if (m < 1000) return m.toString() + "米"
        val f = Math.round(m / 100f) / 10f
        val s = f.toString()
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + "公里"
    }

    /**
     * 把「当前能拿到的到灯距离」补进灯对象。
     * 优先级：60073 / lightsData 自带 > 最近一帧 10001 的 `SEG_REMAIN_DIS` > 未知。
     */
    private fun withDistance(l: Light, now: Long): Light {
        val segValid = segDisAt > 0L && now - segDisAt <= INFO_KEEP_MS
        val m = when {
            l.distanceM >= 0 -> l.distanceM
            segValid -> segDisM
            else -> -1
        }
        // ★ 6.0.55：距离文本一律按米数重新生成，绝不把导航提示文案（「现在」）
        // 原样上屏；6.0.53 就是在这里把 _AUTO 原文透传出去的。
        val src = when {
            l.distanceText.isNotEmpty() -> l.distanceText
            m >= 0 && segValid -> segDisText
            else -> ""
        }
        val fromText = parseAutoDisM(src)
        val finalM = if (m >= 0) m else fromText
        val t = when {
            fromText >= 0 -> formatDis(fromText)
            finalM >= 0 -> formatDis(finalM)
            else -> ""
        }
        return l.copy(distanceM = finalM, distanceText = t)
    }

    /**
     * 距离门控：距离已知且超过 `navi.lightMaxDis` 米 → 判为「下一个路口的灯」，不上屏。
     * 距离未知（巡航态）或门控关闭时一律放行，避免把正常的当前路口灯误杀。
     */
    private fun lightVisible(l: Light): Boolean {
        val max = lightMaxDis()
        if (max <= 0 || l.distanceM < 0) return true
        return l.distanceM <= max
    }

    private fun lightMaxDis(): Int {
        val c = appCtx?.let { conf(it) } ?: return LIGHT_DIS_DEF
        return runCatching {
            c.str("navi.lightMaxDis", LIGHT_DIS_DEF.toString()).trim().toIntOrNull()
                ?: LIGHT_DIS_DEF
        }.getOrDefault(LIGHT_DIS_DEF)
    }

    /**
     * 解析 `EXTRA_DRIVE_WAY`：
     * ```json
     * {"drive_way_enabled":true,"drive_way_size":3,
     *  "drive_way_info":[{"drive_way_number":0,"drive_way_lane_Back_icon":"0",
     *                     "trafficLaneAdvised":false}, …]}
     * ```
     * 按 `drive_way_number` 升序（左 → 右）排列。`drive_way_enabled=false` 视为没有车道。
     */
    private fun parseLanes(json: String): List<Lane> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (!root.optBoolean("drive_way_enabled", false)) return emptyList()
        if (root.optInt("drive_way_size", 0) <= 0) return emptyList()
        val arr = root.optJSONArray("drive_way_info") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = ArrayList<Pair<Int, Lane>>(arr.length())
        for (idx in 0 until arr.length()) {
            val o = arr.optJSONObject(idx) ?: continue
            val icon = o.optString("drive_way_lane_Back_icon", "")
            val advised = o.optBoolean("trafficLaneAdvised", false) ||
                o.optString("trafficLaneAdvised").equals("true", true) ||
                o.optString("trafficLaneAdvised") == "1"
            out.add(o.optInt("drive_way_number", idx) to Lane(icon, advised))
        }
        out.sortBy { it.first }
        return out.map { it.second }
    }

    /** `getIntExtra` 的宽松版：高德个别字段会塞字符串（"12" / "12.0"）。 */
    private fun intExtra(i: Intent, key: String, def: Int): Int {
        val e = i.extras ?: return def
        return when (val v = e.get(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toFloatOrNull()?.toInt() ?: def
            else -> def
        }
    }

    /** 取字符串字段：高德有些版本会把 `null` 塞成字面量 `"null"`，一并当空处理。 */
    private fun strExtra(i: Intent, key: String): String {
        val v = i.extras?.get(key) ?: return ""
        val s = (v as? String) ?: v.toString()
        val t = s.trim()
        return if (t.isEmpty() || t == "null" || t == "NULL") "" else t
    }

    // ---------------- 快照与超时 ----------------

    /**
     * 按当前时间戳重算快照；**内容真的变了**才通知订阅方。
     *
     * 高德导航时 10001 每秒好几条，若每条都通知，卡片会跟着一直 invalidate。
     * 这里用不可变 [Snapshot] 的 `equals` 去重（`data class` + 列表比较）。
     */
    private fun publish() {
        var changed = false
        // ★ v6.0.43：解析在后台线程（[worker]），主线程 ticker 也在调这里重算超时，
        //   两边都会读一组状态字段并写 [snap]，必须互斥，否则可能把半更新的
        //   状态拼成一份快照发出去。
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val navi = naviAt > 0L && now - naviAt <= NAVI_KEEP_MS
            val light = if (lightAt > 0L && now - lightAt <= LIGHT_KEEP_MS) lightVal else null
            val lanes = if (laneAt > 0L && now - laneAt <= LANE_KEEP_MS) laneVal else emptyList()
            val info = if (infoAt > 0L && now - infoAt <= INFO_KEEP_MS) {
                // 导航刚结束、10001 还在继续发（变巡航）的那几秒，别让上一次的转向图标留在屏上
                if (navi) infoVal else infoVal.copy(icon = 0)
            } else {
                Info()
            }
            val next = Snapshot(navi, light, lanes, info)
            if (next == snap) return
            snap = next
            changed = true
        }
        // 通知放在锁外：订阅方是 View.invalidate()，不需要占着锁执行
        if (changed) fire()
    }

    private fun fire() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
            return
        }
        if (firePending) return
        firePending = true
        main.post {
            firePending = false
            dispatch()
        }
    }

    private fun dispatch() {
        for (l in listeners) runCatching { l() }
    }

    /**
     * 每秒过一遍超时。
     *
     * 只靠广播驱动的清除是不够的：高德把后台杀掉、或者用户直接退出导航，
     * 都**不会再发任何包**，红绿灯就会永久停在上一次的数字上。
     * 顺带在导航活跃但一直没车道时，主动跟高德要一次（[ASK_GAP_MS] 限流）。
     *
     * 高德在前台时降到 [PAUSE_TICK_MS] 一次：不做超时计算、不问车道，
     * 只确认「它什么时候退回后台」，这是暂停期间唯一还需要工作的东西。
     */
    private val tickR = object : Runnable {
        override fun run() {
            if (!tickerRunning) return

            syncPause()

            if (paused) {
                // 死锁兜底：无障碍读不到前台包名时，"高德已回后台"只能靠 10019 的 4，
                // 万一那条丢了，暂停就再也退不出来。高德在前台导航时每秒都有包，
                // 90 秒一条都没有 = 它早已不在前台，直接退回正常模式。
                val since = if (lastRxAt > 0L) lastRxAt else pausedAt
                if (H6A11y.foregroundPkg.isEmpty() && since > 0L &&
                    SystemClock.elapsedRealtime() - since > PAUSE_IDLE_MAX_MS
                ) {
                    amapFront = false
                    Log.i(TAG, "暂停中 90s 无包，退回正常接收")
                    exitPause()
                }
                main.postDelayed(this, PAUSE_TICK_MS)
                return
            }

            publish()
            maybeAskLane()
            main.postDelayed(this, 1_000L)
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.postDelayed(tickR, 1_000L)
    }

    // ---------------- 高德前台暂停 ----------------

    /**
     * 现在该不该暂停。
     *
     * 1. 无障碍能读到前台包名 → 按包名判。退回桌面时包名立刻变成自己，恢复最快最准。
     * 2. 读不到（服务没开 / 被系统停掉）→ 退高德自己报的 10019（3 前台 / 4 后台）。
     */
    private fun shouldPause(): Boolean {
        val fg = H6A11y.foregroundPkg
        return if (fg.isNotEmpty()) fg == PKG else amapFront
    }

    private fun syncPause() {
        val want = shouldPause()
        if (want == paused) return
        if (want) enterPause() else exitPause()
    }

    private fun enterPause() {
        paused = true
        pausedAt = SystemClock.elapsedRealtime()
        // 进暂停就把在途数据清掉：桌面被盖住看不见，留着只会在退回桌面的一瞬间
        // 闪一下暂停前的旧数字（那里现在是「上一个路口」）。
        clearAll()
        Log.i(TAG, "高德前台，暂停取数")
    }

    private fun exitPause() {
        paused = false
        // 不主动 publish：高德退回后台会继续每秒发包，等它自然刷新，
        // 避免拿暂停前的残留数据点亮一次界面。
        Log.i(TAG, "高德已不在前台，恢复取数")
    }

    /** 清掉所有在途数据（导航 / 巡航结束、进入暂停共用）。 */
    private fun clearAll() {
        synchronized(lock) {
            naviAt = 0L
            lightAt = 0L
            laneAt = 0L
            infoAt = 0L
            lightVal = null
            laneVal = emptyList()
            infoVal = Info()
            segDisM = -1
            segDisText = ""
            segDisAt = 0L
            passActive = false
            passAt = 0L
            lastSegM = -1
            publish()
        }
    }

    private fun maybeAskLane() {
        val s = snap
        if (!s.navi || s.lanes.isNotEmpty()) return
        val ctx = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - askAt < ASK_GAP_MS) return
        askAt = now
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_RECV)
                    .putExtra("KEY_TYPE", K_ASK_LANE)
                    .putExtra("SOURCE_APP", ctx.packageName)
            )
        }
    }

    // ---------------- 车道图标 ----------------

    /**
     * 车道图标位图缓存。55 张 53×70 的浅灰 PNG，全部解码也就 200KB 上下，
     * 但**绝不能在 onDraw 里解码** —— 高德一秒能推好几次车道，主线程会直接掉帧。
     */
    private val icons = object : LruCache<String, Bitmap>(64) {}

    /** 按高德给的编号取车道图标；没有对应图就用 `lane_special_unknown`。 */
    fun laneBitmap(ctx: Context, icon: String): Bitmap? {
        val key = if (icon.isEmpty()) "unknown" else icon
        icons.get(key)?.let { return it }
        val res = ctx.resources
        var id = 0
        if (icon.isNotEmpty()) {
            id = runCatching {
                res.getIdentifier("lane_pdf_$icon", "drawable", ctx.packageName)
            }.getOrDefault(0)
        }
        if (id == 0) {
            id = runCatching {
                res.getIdentifier("lane_special_unknown", "drawable", ctx.packageName)
            }.getOrDefault(0)
        }
        if (id == 0) return null
        val bmp = runCatching { BitmapFactory.decodeResource(res, id) }.getOrNull() ?: return null
        icons.put(key, bmp)
        return bmp
    }

    // ---------------- 诊断 ----------------

    /** 设置页「高德导航信息 → 广播状态」那一行。 */
    fun describe(): String {
        if (!registered) return "接收器未注册" + if (lastError.isEmpty()) "" else "（$lastError）"
        if (paused) return "已暂停取数（高德在前台）· 退回桌面自动恢复"
        if (rxCount == 0) return "等待高德广播 · 请打开高德地图车机版开始导航"
        val s = snap
        val b = StringBuilder()
        b.append("已收 ").append(rxCount).append(" 条 / 最近 KEY_TYPE ").append(lastKeyType)
        b.append(if (s.navi) " · 导航中" else " · 未导航")
        val road = s.info.nextRoad.ifEmpty { s.info.curRoad }
        if (road.isNotEmpty()) b.append(" · ").append(road)
        if (s.info.segDis.isNotEmpty()) b.append(" ").append(s.info.segDis)
        if (s.light != null) {
            b.append(" · 红绿灯 ").append(s.light.countdown).append("s")
            if (s.light.distanceText.isNotEmpty()) b.append(" / 距灯 ").append(s.light.distanceText)
        } else {
            b.append(" · 无红绿灯")
        }
        if (s.lanes.isNotEmpty()) b.append(" · 车道 ${s.lanes.size} 条")
        if (paused) b.append(" · 已暂停取数（高德前台）")
        else if (amapFront) b.append(" · 高德前台")
        return b.toString()
    }

    /** 一次性把内部状态摊开（排查「收不到广播」时截图发回来）。 */
    fun diagnose(ctx: Context): String {
        val b = StringBuilder()
        b.append(if (enabled(ctx)) "开关:开" else "开关:关")
        b.append(if (registered) " · 接收器:已注册" else " · 接收器:未注册")
        b.append(" · 累计 ").append(rxCount).append(" 条")
        b.append(" · 最近 ").append(lastKeyType)
        b.append(if (amapFront) " · 高德前台" else " · 高德非前台")
        if (paused) b.append(" · 取数已暂停")
        if (passActive) b.append(" · 路口静默中")
        if (lastError.isNotEmpty()) b.append(" · 错误:").append(lastError)
        if (lastLightRaw.isNotEmpty()) b.append("\n最近灯包：").append(lastLightRaw)
        return b.toString()
    }
}
