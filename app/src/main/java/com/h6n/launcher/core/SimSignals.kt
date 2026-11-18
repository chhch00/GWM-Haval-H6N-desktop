package com.h6n.launcher.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全车模拟信号源 —— 让没有 ICarInfo / 平台属性的手机也能把桌面所有卡片跑起来。
 *
 * ## 覆盖范围
 * 打开「整车数据」后，除了门窗（四窗 / 天窗 / 五门），下面这些也全部由本对象生成：
 * 车速 / 均速 / 最高速、挡位、驾驶模式、水温、变速箱油温、四轮胎压与轮温、
 * 续航 / 油量、电压 / 电量 / 硫化率、总里程、蓝牙 / 热点、4G / WiFi 信号。
 * 桌面第 1 页、状态栏、设置「车机」页的读数会一起动，方便在没有车机信号的手机上验收。
 *
 * **不覆盖** `engineOn` / `night` / 机油寿命：发动机累计小时、夜间主题属于本机状态，
 * 模拟它们会把用户手填的累计时间和机油剩余时间改掉，得不偿失。
 *
 * ## 真实信号优先
 * 模拟只是 [SignalHub.decorate] 的一层覆盖：关掉总开关立刻把开启前那帧真实数据写回，
 * 车机上不会残留任何模拟读数，Binder 下一轮轮询照常接管。
 *
 * ## 两种玩法
 * - 手动：一组固定常用数值 + 逐个点开/关四窗、天窗、五门，或一键全开 / 全关。
 * - 循环演示：每 2.2 秒换一组门窗状态，车速/里程在同一段里连续爬升，
 *   回桌面第 1 页就能看到琥珀玻璃、车门开合动画和所有数字变化。
 *
 * 状态存 `SharedPreferences(h6_sim_signals)`，与 ui_config 的用户覆盖分开，
 * 「恢复出厂配置」不会把调试开关一起清掉。
 */
object SimSignals {

    val WINDOW_LABELS = arrayOf("左前窗", "右前窗", "左后窗", "右后窗")
    val DOOR_LABELS = arrayOf("左前门", "右前门", "左后门", "右后门", "尾门")
    const val ROOF_LABEL = "天窗"

    private const val SP = "h6_sim_signals"
    /** 一次性纠偏标记：老版本残留的「模拟已开启」状态只清一次，之后用户自己开的会被尊重。 */
    private const val K_FORCE_OFF = "migrated_force_off_v1"
    /** 车窗开度：auto 判定是 >1 且 <99 算开，天窗阈值默认 6 —— 50 两边都稳过 */
    private const val OPEN_POS = 50
    private const val CLOSE_POS = 0
    private const val STEP_MS = 2200L
    private const val TICK_MS = 200L

    data class Frame(
        val windows: List<Int>,
        val sunroof: Int,
        val doors: List<Int>,
        val label: String
    )

    /** 设置页面板用的一份快照。 */
    data class State(
        val enabled: Boolean,
        val auto: Boolean,
        val all: Boolean,
        val win: BooleanArray,
        val roof: Boolean,
        val door: BooleanArray,
        val label: String
    )

    /** 一帧整车数值。 */
    private data class Values(
        val speedKmh: Int,
        val avgSpeed: Int,
        val maxSpeed: Int,
        val gear: String,
        val driveMode: Int,
        val coolantC: Float,
        val atfC: Float,
        val tireKpa: List<Int>,
        val tireTempC: List<Int>,
        val rangeKm: Int,
        val fuelLiters: Float,
        val fuelPct: Int,
        val volt: Float,
        val battPct: Int,
        val sulfurPct: Int,
        val mileageKm: Int,
        val btConnected: Boolean,
        val hotspotOn: Boolean,
        val signalLevel: Int,
        val wifiLevel: Int,
        val powerMode: Int = 1
    )

    /** 演示脚本：门窗按组切换，整车数值跟着换工况（驻车 → 起步 → 巡航 → 加速 → 减速 → 停车 → 倒车）。 */
    private data class Step(
        val win: String,
        val roof: Boolean,
        val door: String,
        val label: String,
        val v: Values
    )

    private fun v(
        speed: Int, gear: String, mode: Int, coolant: Int, atf: Int,
        tire: List<Int>, range: Int, fuel: Int, volt: Float, sulfur: Int,
        mileage: Int, sig: Int, wifi: Int, bt: Boolean, hotspot: Boolean,
        avg: Int = 38, max: Int = 96, power: Int = 1
    ) = Values(
        speedKmh = speed, avgSpeed = avg, maxSpeed = max,
        gear = gear, driveMode = mode,
        coolantC = coolant.toFloat(), atfC = atf.toFloat(),
        tireKpa = tire,
        tireTempC = listOf(31, 33, 30, 32),
        rangeKm = range, fuelLiters = fuel.toFloat(), fuelPct = fuel * 100 / 55,
        volt = volt, battPct = 85, sulfurPct = sulfur, mileageKm = mileage,
        btConnected = bt, hotspotOn = hotspot,
        signalLevel = sig, wifiLevel = wifi, powerMode = power
    )

    /** 手动模式的一组「正常行驶」读数。 */
    private val STATIC = v(
        speed = 46, gear = "D", mode = CarBinderSource.MODE_STANDARD, coolant = 92, atf = 68,
        tire = listOf(236, 238, 235, 233), range = 486, fuel = 36,
        volt = 13.8f, sulfur = 12, mileage = 28640, sig = 3, wifi = 2,
        bt = true, hotspot = false
    )

    /** 第三个位置参数是驾驶模式，取值必须用原厂值域：1 标准 / 2 经济 / 3 运动。 */
    private val STEPS = listOf(
        Step("0000", false, "00000", "① 驻车 · 全关", v(
            0, "P", 1, 62, 45, listOf(232, 234, 231, 230), 512, 42, 12.6f, 8, 28640, 4, 3, false, false, 0, 0
        )),
        Step("1000", false, "00000", "② 起步 · 左前窗未关", v(
            18, "D", 3, 78, 56, listOf(236, 238, 235, 233), 498, 40, 13.2f, 9, 28641, 4, 3, true, false, 16, 24
        )),
        Step("1111", true, "00000", "③ 巡航 · 四窗 + 天窗未关", v(
            76, "D", 2, 92, 68, listOf(239, 241, 237, 236), 462, 35, 13.9f, 12, 28643, 3, 2, true, false, 52, 92
        )),
        Step("1111", true, "10000", "④ 加速 · 左前门打开", v(
            108, "S", 3, 96, 74, listOf(243, 245, 240, 239), 438, 32, 14.1f, 13, 28645, 3, 2, true, true, 68, 116
        )),
        Step("0101", false, "01101", "⑤ 减速 · 右前 / 左后门 + 尾门", v(
            42, "D", 1, 94, 71, listOf(240, 242, 238, 237), 426, 31, 13.8f, 14, 28647, 2, 2, true, true, 61, 116
        )),
        Step("0000", false, "11111", "⑥ 停车 · 五门全开", v(
            0, "N", 1, 90, 66, listOf(237, 239, 235, 234), 418, 30, 12.9f, 15, 28648, 2, 1, false, true, 58, 116
        )),
        Step("1010", true, "00010", "⑦ 倒车 · 混合状态", v(
            6, "R", 3, 88, 64, listOf(236, 238, 234, 233), 416, 30, 13.1f, 15, 28649, 4, 3, true, true, 58, 116
        )),
        Step("0000", false, "00000", "⑧ 回到驻车 · 全关", v(
            0, "P", 1, 84, 60, listOf(234, 236, 232, 231), 414, 30, 12.7f, 16, 28650, 4, 3, false, false, 56, 116
        ))
    )

    private val lock = Any()
    @Volatile private var on = false
    @Volatile private var autoDemo = false

    /**
     * 「整车数据」子开关。**默认关**：即便总开关被误开，也不会立刻把车速/里程/胎压
     * 全套换成假数据 —— 只接管门窗，真实车身信号照常显示。
     * 要看完整演示效果，用户在设置里自己打开这一项。
     */
    @Volatile private var allData = false
    private val win = BooleanArray(4)
    private val door = BooleanArray(5)
    private var roof = false
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var demoAt = 0L
    @Volatile private var lastStep = -1
    @Volatile private var lastOn = false
    @Volatile private var attached = false
    /** 开启模拟前的一帧真实数据，关掉时原样写回。 */
    @Volatile private var realBase: CarSignal? = null
    /** 「模拟熄火」脉冲：-1 不接管，1 通电，0 熄火。 */
    @Volatile private var powerOverride = -1
    private val main = Handler(Looper.getMainLooper())

    fun attach(app: Application, scope: CoroutineScope) {
        if (attached) return
        attached = true
        val p = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
        forceOffOnce(p)
        synchronized(lock) {
            prefs = p
            on = p.getBoolean("enabled", false)
            autoDemo = p.getBoolean("auto", false)
            allData = p.getBoolean("all", false)
            for (i in 0..3) win[i] = p.getBoolean("w$i", false)
            for (i in 0..4) door[i] = p.getBoolean("d$i", false)
            roof = p.getBoolean("roof", false)
        }
        lastOn = on
        demoAt = SystemClock.elapsedRealtime()
        if (on) applyNow()
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { tick() }
                delay(TICK_MS)
            }
        }
    }

    /**
     * 一次性纠偏：**车辆数据模拟默认关闭**。
     *
     * 老版本可能把「模拟信号」留成开启状态（手机上验收完装回车上，就会一直显示
     * 车速 46、里程 28640 这类假数据，且看不出哪里不对）。升级到本版后第一次启动
     * 强制关一次，之后用户在设置里自己打开仍然有效 —— 只纠偏一次，不夺权。
     */
    private fun forceOffOnce(p: SharedPreferences) {
        if (p.getBoolean(K_FORCE_OFF, false)) return
        p.edit()
            .putBoolean("enabled", false)
            .putBoolean("auto", false)
            .putBoolean(K_FORCE_OFF, true)
            .apply()
    }

    /** 配置备份恢复后重新读 SP，让内存状态与磁盘一致，不必重启桌面进程。 */
    fun reloadPrefs() {
        val p = prefs ?: return
        synchronized(lock) {
            on = p.getBoolean("enabled", false)
            autoDemo = p.getBoolean("auto", false)
            allData = p.getBoolean("all", false)
            for (i in 0..3) win[i] = p.getBoolean("w$i", false)
            for (i in 0..4) door[i] = p.getBoolean("d$i", false)
            roof = p.getBoolean("roof", false)
        }
        powerOverride = -1
        demoAt = SystemClock.elapsedRealtime()
        lastStep = -1
        if (on) applyNow() else restoreReal()
        lastOn = on
    }

    /** 每 200ms 保证模拟帧盖在信号总线上（Binder 1s/3s 一轮的直接写入也会被立刻纠正）。 */
    private fun tick() {
        val nowOn = on
        if (nowOn != lastOn) {
            lastOn = nowOn
            if (!nowOn) {
                restoreReal()
                return
            }
            demoAt = SystemClock.elapsedRealtime()
            lastStep = -1
        }
        if (!nowOn) return
        // 循环演示按节拍换组；整车数值即使在同一组里也连续爬升，所以每帧都要重推
        if (autoDemo) lastStep = stepIndex()
        applyNow()
    }

    /**
     * 信号总线的统一叠加入口：**任何来源**（Binder / GPS / mock）写入的帧都会经过这里。
     * [allData] 打开时覆盖整车读数，关着只覆盖门窗；总开关关闭时完全放行真实值。
     */
    fun decorate(s: CarSignal): CarSignal {
        if (!attached || !on) {
            return if (s.simActive || s.simAll || s.source == "sim") {
                s.copy(
                    simActive = false, simAll = false,
                    source = if (s.source == "sim") "none" else s.source
                )
            } else s
        }
        val f = frame()
        if (!allData) {
            return s.copy(
                windowPos = f.windows, sunroof = f.sunroof, doors = f.doors,
                powerMode = effectivePower(s),
                simActive = true, simAll = false
            )
        }
        val v = values()
        return s.copy(
            speedKmh = v.speedKmh, avgSpeed = v.avgSpeed, maxSpeed = v.maxSpeed,
            gear = v.gear, driveMode = v.driveMode,
            coolantC = v.coolantC, atfC = v.atfC,
            tireKpa = v.tireKpa, tireTempC = v.tireTempC,
            rangeKm = v.rangeKm, fuelLiters = v.fuelLiters, fuelPct = v.fuelPct,
            volt = v.volt, battPct = v.battPct, sulfurPct = v.sulfurPct,
            mileageKm = v.mileageKm,
            btConnected = v.btConnected, hotspotOn = v.hotspotOn,
            signalLevel = v.signalLevel, wifiLevel = v.wifiLevel,
            windowPos = f.windows, sunroof = f.sunroof, doors = f.doors,
            powerMode = effectivePower(s),
            source = "sim", simActive = true, simAll = true
        )
    }

    /** 模拟信号开启时电源读数的默认值（手机上平台属性读不到，按「已通电」算）。 */
    private fun effectivePower(s: CarSignal): Int =
        if (powerOverride >= 0) powerOverride else if (s.powerMode >= 0) s.powerMode else 1

    /**
     * 手动模拟一次「熄火」：通电 → 熄火 → 恢复，制造出 SceneAnnouncer 需要的 1→0 边沿。
     * 熄火后的 1.5 秒会检查当前车窗状态，有未关的车窗就播 window.wav。
     */
    fun pulsePowerOff() {
        if (!on) setEnabled(true)
        powerOverride = 1
        applyNow()
        main.removeCallbacks(restorePower)
        main.postDelayed({
            powerOverride = 0
            applyNow()
        }, 600L)
        main.postDelayed(restorePower, 3600L)
    }

    private val restorePower = Runnable {
        powerOverride = -1
        applyNow()
    }

    /** 当前应生效的门窗一帧。 */
    fun frame(): Frame = synchronized(lock) {
        if (autoDemo) fromStep(STEPS[stepIndex()])
        else Frame(
            windows = List(4) { if (win[it]) OPEN_POS else CLOSE_POS },
            sunroof = if (roof) OPEN_POS else CLOSE_POS,
            doors = List(5) { if (door[it]) 1 else 0 },
            label = "手动"
        )
    }

    /** 当前应生效的整车数值。循环演示时车速/里程在同一组里连续变化。 */
    private fun values(): Values {
        if (!autoDemo) return STATIC
        val idx = stepIndex()
        val base = STEPS[idx].v
        val elapsed = SystemClock.elapsedRealtime() - demoAt
        val phase = ((elapsed % STEP_MS).toFloat() / STEP_MS).coerceIn(0f, 1f)
        val speed = if (base.speedKmh <= 6) base.speedKmh
        else (base.speedKmh * (0.45f + 0.55f * phase)).toInt()
        return base.copy(
            speedKmh = speed,
            mileageKm = base.mileageKm + (elapsed / 3000L).toInt(),
            coolantC = base.coolantC + phase * 1.5f,
            atfC = base.atfC + phase * 1.2f
        )
    }

    /** 面板显示的开关状态：演示时跟着脚本走，方便看出现在轮到哪一组。 */
    fun panelState(): State = synchronized(lock) {
        if (on && autoDemo) {
            val st = STEPS[stepIndex()]
            State(true, true, allData, bits(st.win), st.roof, bits(st.door), st.label)
        } else {
            State(on, autoDemo, allData, win.copyOf(), roof, door.copyOf(), if (on) "手动" else "未启用")
        }
    }

    fun enabled(): Boolean = on
    fun auto(): Boolean = autoDemo
    fun all(): Boolean = allData

    fun setEnabled(v: Boolean) {
        if (v && !on) {
            // 先记下开启瞬间的真实帧，关掉时原样还回去
            realBase = SignalHub.current().copy(simActive = false, simAll = false)
        }
        // 同步 lastOn：开关状态在这里已经处理完，tick 不要再把刚恢复的真实帧清一遍
        synchronized(lock) { on = v; lastOn = v; persist() }
        if (v) {
            demoAt = SystemClock.elapsedRealtime()
            lastStep = -1
            applyNow()
        } else {
            restoreReal()
        }
    }

    fun toggleEnabled() = setEnabled(!on)

    /** 关掉模拟：把开启前那帧真实数据写回总线，之后 Binder / GPS 继续接管。 */
    private fun restoreReal() {
        val b = realBase
        realBase = null
        val src = b?.source
        SignalHub.push((b ?: CarSignal.EMPTY).copy(
            simActive = false, simAll = false,
            source = if (src == "sim") "none" else (src ?: "none")
        ))
    }

    fun setAuto(v: Boolean) {
        synchronized(lock) { autoDemo = v; persist() }
        if (v) {
            demoAt = SystemClock.elapsedRealtime()
            lastStep = -1
        }
        applyNow()
    }

    fun toggleAuto() = setAuto(!autoDemo)

    fun setAllData(v: Boolean) {
        synchronized(lock) { allData = v; persist() }
        applyNow()
    }

    fun toggleAll() = setAllData(!allData)

    fun toggleWindow(i: Int) {
        if (i !in 0..3) return
        beforeManual()
        synchronized(lock) { win[i] = !win[i]; persist() }
        applyNow()
    }

    fun toggleRoof() {
        beforeManual()
        synchronized(lock) { roof = !roof; persist() }
        applyNow()
    }

    fun toggleDoor(i: Int) {
        if (i !in 0..4) return
        beforeManual()
        synchronized(lock) { door[i] = !door[i]; persist() }
        applyNow()
    }

    fun setAll(open: Boolean) {
        beforeManual()
        synchronized(lock) {
            for (i in 0..3) win[i] = open
            for (i in 0..4) door[i] = open
            roof = open
            persist()
        }
        applyNow()
    }

    /** 手动改任何一项：自动开启模拟，并退出循环演示（否则点了立刻被脚本盖掉）。 */
    private fun beforeManual() {
        if (!on) setEnabled(true)
        if (autoDemo) {
            synchronized(lock) { autoDemo = false; persist() }
        }
    }

    private fun applyNow() {
        SignalHub.update { it }
    }

    private fun stepIndex(): Int =
        (((SystemClock.elapsedRealtime() - demoAt) / STEP_MS) % STEPS.size).toInt()

    private fun fromStep(st: Step): Frame = Frame(
        windows = st.win.map { if (it == '1') OPEN_POS else CLOSE_POS },
        sunroof = if (st.roof) OPEN_POS else CLOSE_POS,
        doors = st.door.map { if (it == '1') 1 else 0 },
        label = st.label
    )

    private fun bits(s: String): BooleanArray = BooleanArray(s.length) { s[it] == '1' }

    /** 必须在 [lock] 内调用：一次写全量，省得每个开关各开一个事务。 */
    private fun persist() {
        val e = prefs?.edit() ?: return
        e.putBoolean("enabled", on).putBoolean("auto", autoDemo).putBoolean("all", allData)
        for (i in 0..3) e.putBoolean("w$i", win[i])
        for (i in 0..4) e.putBoolean("d$i", door[i])
        e.putBoolean("roof", roof)
        e.apply()
    }

    /** 诊断行用：把当前帧翻译成一行文字。 */
    fun describe(s: CarSignal): String {
        if (!s.simActive) return "未启用 · 全部走真实车身信号"
        val w = (0..3).joinToString("/") {
            if ((s.windowPos.getOrNull(it) ?: 0) in 2..98) "开" else "关"
        }
        val r = if (s.sunroof in 6..94) "开" else "关"
        val d = (0..4).joinToString("/") { if (s.doors.getOrNull(it) == 1) "开" else "关" }
        val door = "窗 $w · 顶 $r · 门 $d"
        if (!s.simAll) return "仅门窗 · $door"
        val tire = if (s.tireKpa.isEmpty()) "--" else s.tireKpa.joinToString("/")
        return "车速 ${s.speedKmh} · ${s.gear} · 水温 ${s.coolantC.toInt()}℃ / 变速油 ${s.atfC.toInt()}℃ · " +
            "胎压 $tire · 里程 ${s.mileageKm}km | $door"
    }
}
