package com.h6n.launcher.core

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.data.ConfigRepository
import com.h6n.launcher.domain.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 熄火自动关窗。
 *
 * ## ★ 6.0.107 实车纠正：value 不是开关，是动作编号
 * 车友实测（6.0.106）：
 * - `value = 0` → **四窗全部下降**
 * - `value = 1` → **只有主驾窗下降**
 *
 * 也就是说 `DOOR_WINDOW_VR_CONTROL(0x00)` 的入参是一张「功能点编号表」，不是 0/1 布尔。
 * 6.0.103~106 把它当成「0 是关、1 是开」的开关在做，所以不管切哪个值窗户都只会往下走
 * —— 这才是「熄火关窗没反应 / 窗户反而全开」的真因。
 *
 * 现在改成**标定**：候选表 [WIN_CANDIDATES] 按可能性排序（0 全降 / 5 全升这类「先降后升」
 * 枚举排最前，位域编码 16 = 全升排第二），逐值下发 + 回读开度合计，第一个让开度明显变小
 * 的就是关窗值，落盘到 [K_WIN_CLOSE]，之后熄火直接用。
 *
 * ## 为什么默认是关的
 * 这条通道没有返回值、也没有读入口，写进去不代表车真的执行了。所以按
 * 「默认关闭 + 实车先标定一次」的原则做：
 *
 * - 设置页有「自动找关窗值」，四窗有开度时点一下，几十秒内自己试出来；
 * - 「试关窗」按已标定的值下发一次，当场看动作；
 * - 下发后**必回读**车窗开度裁决，绝不假装成功；
 * - 未标定时熄火也会按 [DEFAULT_CLOSE_VALUE] 试一次，真关上了就自动记住。
 *
 * ## 触发
 * 三个条件可组合（设置项 [K_TRIGGER]）：
 * - `both`（默认）：熄火 **且** 已锁车。两种先后顺序都认 —— 熄火时已经锁着、
 *   或者熄火后再按钥匙锁车，都能触发。
 * - `engine`：只要熄火就关（最可靠：熄火这一刻车机一定还活着）。
 * - `lock`：只要锁车就关（要求车速为 0 且发动机已停）。
 *
 * 熄火后车机会断电，锁车信号那会儿可能已经读不到了 —— 这是硬约束，所以
 * `engine` 才是「一定能跑到」的那一档，`both` 在本车能不能成立取决于锁车后
 * 车机还供不供电。日志里会写清楚卡在哪一步。
 *
 * ## 安全闸（硬编码，不可关）
 * - 车速 > [MAX_SPEED_KMH] 一律不下发；
 * - 每次熄火周期只执行一次（重新上电才复位）；
 * - 车窗本来就全关时不下发，省一次 Binder 写入。
 */
object AutoClose {

    private const val TAG = "H6AutoClose"

    // ---------------------------------------------------------------- 配置键
    /** 总开关，默认关 */
    const val K_ON = "autoClose.on"
    /** 天窗是否一起关，默认关（用户要求「可选」） */
    const val K_ROOF = "autoClose.roof"
    /** 触发条件：both / engine / lock */
    const val K_TRIGGER = "autoClose.trigger"
    /** 熄火后延时秒数 */
    const val K_DELAY = "autoClose.delay"
    /**
     * 车窗关窗值（标定出来的**功能点编号**）。-1 / 缺省 = 还没标定。
     *
     * ★ 6.0.107 实车纠正：`DOOR_WINDOW_VR_CONTROL(0x00)` 的入参**不是 0/1 布尔**，
     *   而是「动作编号」——实车 value=0 是四窗全部下降、value=1 只是主驾窗下降。
     *   原来按「0 是关、1 是开」做的开关语义整套都是错的，所以 [K_VALUE] 废弃。
     */
    const val K_WIN_CLOSE = "autoClose.winClose"

    /**
     * @deprecated 6.0.107 起不再使用。实车证明 0/1 都是「降窗」，留常量只为读旧配置时不炸。
     */
    const val K_VALUE = "autoClose.value"
    /** 锁车读数反相（该项为 0 才算已锁） */
    const val K_LOCK_INV = "autoClose.lockInv"

    const val TRIGGER_BOTH = "both"
    const val TRIGGER_ENGINE = "engine"
    const val TRIGGER_LOCK = "lock"

    private const val MAX_SPEED_KMH = 3
    /** 下发后等这么久回读开度：信号总线 3 秒一帧，留一帧半的余量 */
    private const val CONFIRM_MS = 4500L
    /** 标定时每个候选值的等待时间：升窗动作比降窗慢，留足 */
    private const val STEP_MS = 4200L
    /** 同一次熄火里，两次触发之间的最小间隔 */
    private const val DEBOUNCE_MS = 6000L
    /** 开度合计掉这么多才算「真的在关」：低于它多半是采样抖动 */
    private const val DROP_HIT = 12

    /**
     * 未标定时先试这个值。实车已知 0=全降、1=主驾降，按「先降后升」的编号惯例
     * （0 全降 / 5 全升）把 5 排在第一位；不中也只是四窗再降一次，不会更糟。
     */
    private const val DEFAULT_CLOSE_VALUE = 5

    /**
     * 关窗候选值，**按可能性从高到低**排列，标定时逐个试、第一个见效的即命中。
     *
     * 为什么是这些数：0/1 已实车确认为「降」，剩下两种常见编码 ——
     * ①「先降后升」枚举：5=全升、6=主驾升…（本表把它们排最前）；
     * ②「低 4 位选窗 + bit4 上升位」位域：16=全升、17=主驾升。
     * 都是从 0/1 的实测量反推出来的，不再靠猜。
     */
    private val WIN_CANDIDATES = intArrayOf(5, 16, 6, 7, 2, 3, 4, 8, 9, 10, 11, 12, 13, 14, 15)

    @Volatile private var app: Application? = null
    @Volatile private var attached = false
    @Volatile private var lastResult = "还没执行过"
    /** 标定进行中：挡住重复点击与同时到来的熄火触发 */
    @Volatile private var calibrating = false

    private var scope: CoroutineScope? = null
    private val main = Handler(Looper.getMainLooper())

    // 以下状态只在主线程读写
    private var lastPower = -1
    private var lastEngineOn: Boolean? = null
    private var lastLocked: Boolean? = null
    /** 上一次的锁车**原始值**：用来识别「方向不明但确实变过」的锁车动作。 */
    private var lastRawLock = -1
    private var firedThisCycle = false
    private var pending: Runnable? = null
    private var pendingAt = 0L
    private var lastRunAt = 0L

    fun attach(app: Application, scope: CoroutineScope) {
        if (attached) return
        attached = true
        this.app = app
        this.scope = scope
        scope.launch(Dispatchers.Default) {
            SignalHub.state.collect { s -> main.post { onFrame(s) } }
        }
        Log.i(TAG, "熄火自动关窗已挂载（默认关闭，设置 → 车辆 → 熄火自动关窗）")
    }

    /** 设置页 / 导出日志显示最近一次判定。 */
    fun lastLog(): String = lastResult

    /**
     * 当前生效的车窗关窗值；**-1 = 还没标定**。
     *
     * 设置页拿它显示状态；[execute] 拿它决定下发什么。
     */
    fun closeValue(snap: ConfigSnapshot): Int = snap.int(K_WIN_CLOSE, -1)

    /** 四窗开度合计。99/100 这类「运动中 / 全开」哨兵按原值算，前后同口径即可比。 */
    private fun sumOpen(s: CarSignal): Int {
        var sum = 0
        for (v in s.windowPos.take(4)) if (v in 0..100) sum += v
        return sum
    }

    /** 锁车状态的人类可读串，设置页直接显示读到的原始值。 */
    fun lockText(s: CarSignal): String {
        val v = s.doorLock
        if (v < 0) return "未读到（该车型无此项或通道未连）"
        val inv = app?.let { ConfigRepository.get(it).current().bool(K_LOCK_INV, false) } ?: false
        val locked = lockState(v, inv) == true
        return "原始值 $v → ${if (locked) "已锁" else "未锁"}"
    }

    // ---------------------------------------------------------------- 判定

    private fun onFrame(s: CarSignal) {
        val a = app ?: return
        // 模拟信号开着的时候绝不真的去关窗：那是调试用的假门窗，发到车上会误伤
        if (s.simActive) {
            if (lastResult != "模拟信号开着，跳过") note("模拟信号开着，跳过（设置 → 车辆 → 模拟信号）")
            return
        }
        val snap = ConfigRepository.get(a).current()
        if (!snap.bool(K_ON, false)) {
            // 关掉开关时把周期状态复位，下次打开从干净状态开始
            if (pending != null) cancel("设置里关掉了自动关窗")
            lastLocked = null
            return
        }

        // 重新上电 / 发动机再启动 → 复位「本周期已执行」
        if (s.powerMode > 0 || s.engineOn) {
            firedThisCycle = false
        }

        val offEdge = offEdge(s)
        val offNow = isOffNow(s)
        val rawLock = s.doorLock
        val locked = lockState(rawLock, snap.bool(K_LOCK_INV, false))
        val flipped = rawLock >= 0 && lastRawLock >= 0 && rawLock != lastRawLock
        if (rawLock >= 0) lastRawLock = rawLock
        // ★ 6.0.105：锁车方向各车型不一（0 未锁/1 已锁，也有反过来的）。
        //   所以「原始值变过」也算一次锁车动作 —— 只要当时是熄火态。
        val lockEdge = (locked != null && locked && lastLocked == false) ||
            (flipped && offNow && rawLock >= 0)
        if (locked != null) lastLocked = locked

        if (firedThisCycle) return

        val trigger = snap.str(K_TRIGGER, TRIGGER_BOTH)
        val hit = when (trigger) {
            TRIGGER_ENGINE -> offEdge
            TRIGGER_LOCK -> lockEdge && offNow
            // 熄火时锁车读数**读不到**也照关：这台车 0x4C 常常拿不到值，
            // 以前死等「locked == true」就永远不触发（用户反馈的就是这种）。
            else -> (offEdge && (locked == true || locked == null)) || (lockEdge && offNow)
        }
        if (!hit) return

        val speed = s.speedKmh
        if (speed > MAX_SPEED_KMH) {
            note("车速 ${speed} km/h，不关窗")
            return
        }

        val why = when {
            trigger == TRIGGER_ENGINE -> "熄火"
            trigger == TRIGGER_LOCK -> "锁车"
            offEdge && locked == true -> "熄火时车门已锁"
            offEdge -> "熄火（锁车读数读不到，按熄火处理）"
            else -> "已熄火，检测到锁车"
        }
        schedule(snap.int(K_DELAY, 0).coerceIn(0, 600) * 1000L, why)
    }

    /**
     * 熄火边沿：**电源模式和发动机状态任一个掉了都算**。
     *
     * 以前是「电源可读就只看电源」。实车上 `car.basic.power_mode` 有时恒为 0
     * （这个键在部分固件里就是常量），那样「熄火」永远判不出来，自动关窗一次都不会触发。
     * 现在两条边沿取或：谁掉了都算熄火。
     */
    private fun offEdge(s: CarSignal): Boolean {
        var hit = false
        val p = s.powerMode
        if (p >= 0) {
            val prev = lastPower
            lastPower = p
            if (prev > 0 && p == 0) hit = true
        }
        val e = s.engineOn
        val prevE = lastEngineOn
        lastEngineOn = e
        if (prevE == true && !e) hit = true
        return hit
    }

    private fun isOffNow(s: CarSignal): Boolean {
        val powerOff = s.powerMode >= 0 && s.powerMode == 0
        val engineOff = s.powerMode < 0 && !s.engineOn
        return powerOff || engineOff
    }

    /** 锁车读数 → 布尔。null = 读不到（不能当成「未锁」，否则会一直不触发）。 */
    private fun lockState(raw: Int, invert: Boolean): Boolean? {
        if (raw < 0) return null
        return if (invert) raw == 0 else raw > 0
    }

    // ---------------------------------------------------------------- 执行

    private fun schedule(delayMs: Long, why: String) {
        val now = SystemClock.uptimeMillis()
        val old = pending
        // 已经有更早的计划就别往后推
        if (old != null && pendingAt - now <= delayMs) return
        old?.let { main.removeCallbacks(it) }
        val r = Runnable { doRun(why) }
        pending = r
        pendingAt = now + delayMs
        main.postDelayed(r, delayMs)
        note(if (delayMs <= 0) "$why → 立即关窗" else "$why → ${delayMs / 1000} 秒后关窗")
    }

    private fun cancel(reason: String) {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        note(reason)
    }

    private fun doRun(why: String) {
        pending = null
        val a = app ?: return
        val snap = ConfigRepository.get(a).current()
        if (!snap.bool(K_ON, false)) {
            note("开关已关，取消")
            return
        }
        val s = SignalHub.current()
        if (s.speedKmh > MAX_SPEED_KMH) {
            note("车速 ${s.speedKmh} km/h，取消关窗")
            return
        }
        // 延时期间人回来了（上电）就不关 —— 只对「需要熄火」的两种触发校验
        if (snap.str(K_TRIGGER, TRIGGER_BOTH) != TRIGGER_LOCK && !isOffNow(s)) {
            note("电源模式回到 ${s.powerMode}，取消关窗")
            return
        }
        if (firedThisCycle) return
        if (SystemClock.uptimeMillis() - lastRunAt < DEBOUNCE_MS) {
            note("刚执行过（${(SystemClock.uptimeMillis() - lastRunAt) / 1000} 秒前），本次不再重复")
            return
        }
        firedThisCycle = true
        execute(s, snap, why)
    }

    /**
     * 真正下发。车窗「本来就全关」且**读得到开度**时跳过；读不到开度时照样下发
     * —— 用户要的是关窗，不是「确认已经关了才动手」。
     *
     * ★ 6.0.107：还没标定过就先按 [DEFAULT_CLOSE_VALUE] 试一次，回读裁决见
     *   [reportAfter] —— 真关上了就顺手把值记下来，用户不必先去点标定。
     */
    private fun execute(s: CarSignal, snap: ConfigSnapshot, why: String) {
        val roof = snap.bool(K_ROOF, false)
        val cali = closeValue(snap)
        val guess = cali < 0
        val value = if (guess) DEFAULT_CLOSE_VALUE else cali
        val before = SceneStore.openWindowCount(s)
        val windowKnown = s.windowPos.any { it >= 0 }
        val roofKnown = s.sunroof >= 0
        val allKnown = windowKnown || roofKnown
        if (allKnown && before == 0) {
            note("$why → 车窗本来就全关，没下发")
            return
        }
        if (!CarBinderSource.canControl()) {
            note("$why → 车控通道没连上，没下发")
            return
        }

        lastRunAt = SystemClock.uptimeMillis()
        note(
            "$why → 下发关窗（车窗 value=$value" +
                (if (guess) " · 未标定，先按这个试" else "") +
                (if (roof) " · 天窗" else " · 不动天窗") + "）"
        )
        val sc = scope ?: return
        val openBefore = if (allKnown) before else -1
        val sumBefore = if (windowKnown) sumOpen(s) else -1

        sc.launch(Dispatchers.IO) {
            val sent = runCatching { CarBinderSource.closeWindows(true, roof, value) }
                .getOrElse {
                    Log.w(TAG, "关窗下发异常", it)
                    false
                }
            if (!sent) {
                note("关窗指令写不进 Binder（车控通道断开？）")
                return@launch
            }
            delay(CONFIRM_MS)
            reportAfter(openBefore, sumBefore, roof, value, guess)
        }
    }

    /**
     * 回读裁决：读得到就说清关没关，读不到就如实说「读不到」。
     *
     * @param sumBefore 下发前四窗开度合计；-1 = 读不到开度，只能按扇数说
     * @param value     这次下发的编号
     * @param guess     true = 这个值是猜的（未标定），关上了要顺手记下来
     */
    private fun reportAfter(openBefore: Int, sumBefore: Int, roof: Boolean, value: Int, guess: Boolean) {
        val s = SignalHub.current()
        val after = SceneStore.openWindowCount(s)
        val windowKnown = s.windowPos.any { it >= 0 }
        val roofKnown = s.sunroof >= 0
        val known = windowKnown || roofKnown
        val pos = if (s.windowPos.isEmpty()) "--" else s.windowPos.take(4).joinToString("/")
        val sumAfter = if (windowKnown) sumOpen(s) else -1
        val drop = if (sumBefore >= 0 && sumAfter >= 0) sumBefore - sumAfter else 0

        when {
            !known -> note("已下发，但读不到车窗开度（$pos），请目视确认")
            after == 0 && sumAfter <= 2 -> {
                if (guess) remember(value, "四窗已全关")
                note("已关好：四窗 $pos · 天窗 ${if (s.sunroof in 6..94) "开" else "关"}" + if (guess) " · 已记住 value=$value" else "")
            }
            drop >= DROP_HIT -> {
                if (guess) remember(value, "开度 $sumBefore→$sumAfter")
                note("正在关（开度 $sumBefore → $sumAfter，剩 $after 扇未到位）" + if (guess) " · 已记住 value=$value" else "")
            }
            drop <= -DROP_HIT ->
                note("窗户反而开了（开度 $sumBefore → $sumAfter）→ value=$value 是「降窗」编号，去设置 → 车辆 → 熄火自动关窗 → 点「自动找关窗值」")
            else ->
                note("下发后窗户没动（开度 $sumBefore → $sumAfter）→ value=$value 可能是无效编号，去设置点「自动找关窗值」")
        }
        Log.i(TAG, lastResult)
    }

    /** 把标定出来的关窗值落盘。只在「猜的」且确实关上时调用，避免无谓写入。 */
    private fun remember(value: Int, evidence: String) {
        val a = app ?: return
        Log.i(TAG, "标定：关窗 value=$value（$evidence）")
        scope?.launch(Dispatchers.IO) {
            runCatching { ConfigRepository.get(a).set(K_WIN_CLOSE, value.toString()) }
        }
    }

    // ---------------------------------------------------------------- 标定

    /**
     * 逐值试，找出「上升」的编号并记住。
     *
     * 实车已知 0 = 四窗全降、1 = 主驾窗降，所以这两个值**跳过**（没有意义还浪费一次动作）。
     * 每试一个值等 [STEP_MS] 再回读开度合计：明显变小 = 命中；明显变大 = 这是个降窗值，
     * 把基准刷新成新的开度再继续（不然「降了 → 基准偏低 → 后面真关上也看不出变化」）。
     *
     * 前提：四窗得有点开度。全关着的时候下标定等于测不出东西。
     */
    fun calibrate(ctx: Context, onDone: (String) -> Unit) {
        val a = ctx.applicationContext as? Application ?: return
        if (calibrating) {
            onDone("正在找，别重复点")
            return
        }
        if (!CarBinderSource.canControl()) {
            onDone("车控通道没连上：回桌面等几秒，车身信号页能看到「ICarInfo ✔」再试")
            return
        }
        val s0 = SignalHub.current()
        if (!s0.windowPos.any { it >= 0 }) {
            onDone("读不到车窗开度，没法判断有没有关上 —— 先看车身信号页四窗有没有数字")
            return
        }
        val base = sumOpen(s0)
        if (base < 10) {
            onDone("四窗现在基本是关的（开度合计 $base），先把窗降下来再点这个")
            return
        }
        val sc = scope ?: return
        calibrating = true

        sc.launch(Dispatchers.IO) {
            var cur = base
            var hit = -1
            var hitFrom = -1
            val tried = StringBuilder()
            for (v in WIN_CANDIDATES) {
                val before = cur
                val sent = runCatching { CarBinderSource.closeWindows(true, false, v) }.getOrElse { false }
                if (!sent) {
                    calibrating = false
                    main.post { onDone("写不进 Binder：车控通道可能已断开") }
                    return@launch
                }
                note("试 value=$v …（开度合计 $before）")
                delay(STEP_MS)
                val after = sumOpen(SignalHub.current())
                tried.append("$v:$before→$after  ")
                if (before - after >= DROP_HIT) {
                    hit = v
                    hitFrom = before
                    cur = after
                    break
                }
                cur = after
                if (cur <= 2) break   // 已经全关（意外），没有继续试的必要
            }
            calibrating = false
            if (hit >= 0) {
                runCatching { ConfigRepository.get(a).set(K_WIN_CLOSE, hit.toString()) }
                val msg = "找到关窗值：value=$hit（开度 $hitFrom → $cur）· 已保存，熄火自动关窗现在能用"
                note(msg)
                main.post { onDone(msg) }
            } else {
                val msg = "试了 ${WIN_CANDIDATES.size} 个候选值，开度都没变小。明细：$tried"
                note(msg)
                main.post { onDone(msg) }
            }
        }
    }

    // ---------------------------------------------------------------- 设置页手动试

    /** 设置页「试关窗」：不看熄火/锁车，立刻按当前配置下发一次并回读。 */
    fun testClose(ctx: Context, onDone: (String) -> Unit) {
        val app = ctx.applicationContext as? Application ?: return
        val snap = ConfigRepository.get(app).current()
        val roof = snap.bool(K_ROOF, false)
        val cali = closeValue(snap)
        val guess = cali < 0
        val value = if (guess) DEFAULT_CLOSE_VALUE else cali
        if (!CarBinderSource.canControl()) {
            onDone("车控通道没连上：回桌面等几秒，车身信号页能看到「ICarInfo ✔」再试")
            return
        }
        val s = SignalHub.current()
        val openBefore = SceneStore.openWindowCount(s)
        val sumBefore = if (s.windowPos.any { it >= 0 }) sumOpen(s) else -1
        note("手动试关窗（value=$value${if (guess) " · 未标定" else ""}${if (roof) " · 含天窗" else ""}）")
        val sc = scope ?: return
        sc.launch(Dispatchers.IO) {
            val sent = runCatching { CarBinderSource.closeWindows(true, roof, value) }.getOrElse { false }
            if (!sent) {
                main.post { onDone("写不进 Binder：车控通道可能已断开") }
                return@launch
            }
            delay(CONFIRM_MS)
            reportAfter(if (openBefore > 0) openBefore else -1, sumBefore, roof, value, guess)
            main.post { onDone(lastResult) }
        }
    }

    private fun note(msg: String) {
        lastResult = msg
        Log.i(TAG, msg)
    }
}
