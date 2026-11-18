package com.h6n.launcher.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 胎压告警引擎。
 *
 * ## 两条规则（用户 2026-11-15 指定）
 * 1. **胎压过低**：任一轮低于 [TireRules.lowKpa]（默认 240 kPa，设置页可改）。
 * 2. **差值过大**：四轮极差达到 [TireRules.diffKpa]（默认 15 kPa，设置页可改）。
 *
 * ## 三条必须守住的纪律
 * - **迟滞**：进入用 N / N，退出用 N+5 / N-3（默认 240/15 进、245/12 出）。直接在阈值上判定，239→241→239
 *   这种传感器抖动会让语音反复播报，比不播还烦。
 * - **一次异常只播一次**：`lowLatched` / `diffLatched` 是"这次异常已经提醒过"的锁，
 *   只在整个异常消失（且有迟滞余量）后才复位。异常持续三小时也只响一次。
 * - **无效值不参与判定**：某轮 `<= 0` 表示没有传感器读数（刚开机 CAN 没上来、
 *   传感器丢失），拿它算极差会得出"差值 250kPa"的假报警。差值必须四轮齐全才算。
 *
 * ## 播报走和场景播报同一条路
 * 优先播 `assets/voice/tire_low.wav` / `tire_diff.wav`（音色包前缀同样生效），
 * 文件缺失就退回系统 TTS —— 与熄火未关车窗那条完全一致。开关也放在设置页
 * 「场景播报提醒」卡片里，受总开关 [SceneStore.master] 管。
 *
 * ## 与 UI 的分工
 * 本类在 core 包，不认识 Activity。要弹窗时通过 [setListener] 抛出去，
 * 由 MainActivity 接住并调 `TireAlarmPanel.show(...)`，避免 core 反向依赖 ui。
 */
object TireAlert {

    private const val TAG = "H6Tire"

    /** 告警类型 */
    enum class Kind { LOW, DIFF }

    /** 一次告警的完整现场，弹窗用它。 */
    class Alarm(
        val kind: Kind,
        /** 四轮当前 kPa，下标 0..3 = 左前/右前/左后/右后 */
        val kpa: IntArray,
        /** 四轮当前 ℃，-1 表示读不到 */
        val temp: IntArray,
        /** 触发告警的轮位（LOW 可能是多个） */
        val wheels: IntArray,
        /** 四轮极差（DIFF 用；LOW 也给，方便弹窗一起显示） */
        val diff: Int
    )

    /** 轮位中文名，顺序与协议一致：0 左前 / 1 右前 / 2 左后 / 3 右后 */
    val WHEEL_NAMES = arrayOf("左前", "右前", "左后", "右后")

    private val started = AtomicBoolean(false)

    /** 播报与弹窗都必须回主线程：`SignalHub` 的 collect 跑在 Dispatchers.Default。 */
    private val main = Handler(Looper.getMainLooper())

    /** 迟滞锁：true = 本次异常已提醒过，等它彻底消失才复位 */
    @Volatile private var lowLatched = false
    @Volatile private var diffLatched = false

    /** 主屏注册的弹窗回调；不在主屏时为 null，只播报不弹窗 */
    @Volatile private var listener: ((Alarm) -> Unit)? = null

    /** 设置页「最近一次判定」用的一行文字 */
    @Volatile private var lastText = "尚未判定"

    fun lastLog(): String = lastText

    fun attach(a: Application, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Default) {
            SignalHub.state.collect { onFrame(it) }
        }
        // 6.0.66 起不再存胎压历史（曲线/事件表功能已移除），把 6.0.65 留下的库文件删掉，
        // 免得白占车机 eMMC —— deleteDatabase 本身线程安全，放 IO 线程更稳。
        scope.launch(Dispatchers.IO) {
            runCatching { a.deleteDatabase("tire.db") }
        }
        Log.i(TAG, "胎压告警引擎已启动 · 低于 ${TireRules.lowKpa()}kPa / 极差 ${TireRules.diffKpa()}kPa")
    }

    /** MainActivity 在前台时注册；onDestroy 传 null 注销。 */
    fun setListener(l: ((Alarm) -> Unit)?) {
        listener = l
    }

    /** 判定入口，每帧一次（SignalHub 推送频率 200ms~1s，四则比较，开销可忽略）。 */
    fun onFrame(s: CarSignal) {
        // CarSignal 里这两个字段都是 List<Int>（不是 IntArray），
        // 先转成定长 IntArray 再往下走：判定和弹窗都要下标访问，List 的装箱在 1s 级
        // 调用下没必要。长度不足 4 直接放弃这一帧（开机头几秒常这样）。
        val raw = s.tireKpa
        if (raw.size < 4) return
        val v = raw.toIntArray()
        val t = s.tireTempC.toIntArray().let { arr ->
            if (arr.size >= 4) arr else IntArray(4) { arr.getOrElse(it) { -1 } }
        }

        // 有读数的轮位；全无读数直接跳过（开机头几秒常这样）
        val validIdx = v.indices.filter { v[it] >= TireRules.VALID_MIN }
        if (validIdx.isEmpty()) return

        // ---- ① 胎压过低 ----
        // 阈值每帧现读：设置页改完立刻按新值判，不用重启也不用等下一轮
        val low = TireRules.lowKpa()
        val lowReset = TireRules.lowResetKpa()
        val lowIdx = validIdx.filter { v[it] < low }
        if (lowIdx.isNotEmpty()) {
            if (!lowLatched) {
                lowLatched = true
                fire(Kind.LOW, v, t, lowIdx.toIntArray())
            }
        } else if (lowLatched) {
            // 必须全部回到 lowReset（默认 245）以上才解锁，否则在 240 附近抖一次就又响一遍
            if (validIdx.all { v[it] >= lowReset }) {
                lowLatched = false
                Log.i(TAG, "胎压已回升，过低告警解锁")
            }
        }

        // ---- ② 四轮极差 ----
        // 必须四轮齐全：缺一个轮子时 min/max 偏差极大，会报出假警
        if (validIdx.size == 4) {
            val max = validIdx.maxOf { v[it] }
            val min = validIdx.minOf { v[it] }
            val diff = max - min
            val diffOn = TireRules.diffKpa()
            if (diff >= diffOn) {
                if (!diffLatched) {
                    diffLatched = true
                    val high = validIdx.filter { v[it] == max }.toIntArray()
                    fire(Kind.DIFF, v, t, high)
                }
            } else if (diffLatched && diff <= TireRules.diffResetKpa()) {
                diffLatched = false
                Log.i(TAG, "四轮差值已收窄，差值告警解锁")
            }
        }
    }

    /**
     * 真正的触发：记账 → 播报 → 弹窗。
     *
     * 6.0.66 起不再落库（胎压历史已移除），只留「最近一次判定」一行文字给设置页看，
     * 以及受开关控制的播报 + 弹窗。
     */
    private fun fire(kind: Kind, kpa: IntArray, temp: IntArray, wheels: IntArray) {
        val diff = run {
            val valid = kpa.filter { it >= TireRules.VALID_MIN }
            if (valid.isEmpty()) 0 else valid.max() - valid.min()
        }
        val alarm = Alarm(kind, kpa.copyOf(), temp.copyOf(), wheels, diff)

        val head = wheels.joinToString("、") { "${WHEEL_NAMES[it]} ${kpa[it]}kPa" }
        lastText = when (kind) {
            Kind.LOW -> "胎压过低：$head"
            Kind.DIFF -> "胎压差值过大：四轮极差 ${diff}kPa（$head 最高）"
        }
        Log.i(TAG, lastText)
        SceneAnnouncer.note(lastText)

        // ---- 提醒（受开关控制）----
        // 注意线程：onFrame 来自 Dispatchers.Default，而播报要碰 MediaPlayer/TTS、
        // 弹窗要 new Dialog —— 两者都必须在主线程。这里统一 post 一把。
        if (!SceneStore.master()) return
        val on = when (kind) {
            Kind.LOW -> SceneStore.tireLowOn()
            Kind.DIFF -> SceneStore.tireDiffOn()
        }
        if (!on) return
        main.post {
            speak(kind, alarm)
            listener?.invoke(alarm)
        }
    }

    /**
     * 播报。**wav 优先、TTS 兜底** —— 和熄火未关车窗那条路径完全一致：
     * 用户换了音色包 / 自己放了 `snd_tire_low` 就用他的，没有就让系统念。
     */
    private fun speak(kind: Kind, a: Alarm) {
        val file = when (kind) {
            Kind.LOW -> SceneStore.tireLowFile()
            Kind.DIFF -> SceneStore.tireDiffFile()
        }
        if (VoicePlayer.hasFile(file)) {
            Log.i(TAG, "播报音效 $file")
            VoicePlayer.playPriority(file)
            return
        }
        val text = when (kind) {
            Kind.LOW -> if (a.wheels.size == 1) "${WHEEL_NAMES[a.wheels[0]]}轮胎压过低，请检查"
            else "胎压过低，请检查"
            Kind.DIFF -> "胎压差值过大，请检查"
        }
        Log.i(TAG, "音效缺失，改用 TTS：$text")
        SceneAnnouncer.speakText(text, "h6tire")
    }

    /** 设置页测试按钮：直接造一次假告警，走完整的播报 + 弹窗链路。 */
    fun testLow() {
        // 数值跟着当前阈值走：阈值调到 200 时，弹窗若还写「低于 200」而下面是 228 就很怪
        val low = TireRules.lowKpa()
        val fake = intArrayOf((low - 12).coerceAtLeast(120), low + 6, low + 7, low + 5)
        fire(Kind.LOW, fake, intArrayOf(26, 27, 27, 26), intArrayOf(0))
    }

    fun testDiff() {
        // 保持「极差 = 阈值 + 4」的关系，与改阈值之前那组 (262/244/245/243) 一致
        val base = 245
        val fake = intArrayOf(base + TireRules.diffKpa() + 4, base, base + 1, base - 1)
        fire(Kind.DIFF, fake, intArrayOf(27, 26, 26, 26), intArrayOf(0))
    }

    /**
     * 调试/设置页用：当前是否已经处于告警锁里。
     * 返回 null 表示还没有任何判定。
     */
    fun stateText(): String = when {
        lowLatched && diffLatched -> "过低 + 差值（已提醒）"
        lowLatched -> "胎压过低（已提醒）"
        diffLatched -> "差值过大（已提醒）"
        else -> "正常"
    }
}
