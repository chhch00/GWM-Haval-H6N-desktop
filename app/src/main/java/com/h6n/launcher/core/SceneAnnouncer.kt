package com.h6n.launcher.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.h6n.launcher.core.H6Log as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 车辆场景播报引擎 —— 移植自未重写版 `com.h6n.launcher.scene.SceneEngine`。
 *
 * 保留旧版三条核心判定（边沿 + 去抖 + 基线），只把信号入口换成重写版的 [SignalHub]：
 *
 * 1. **车门打开 / 关闭播报**：5 路（左前/右前/左后/右后/尾门）逐路比较原始值，
 *    首帧只记基线（开机时本来就开着的门不播，启动 3s 后除外）；值变化才播，
 *    同一路 320ms 内的抖动跳过；`door_active_high` 可反转有效电平。
 * 2. **换挡档位播报**：只在 P/R/N/D 变化时播，180ms 内的连续跳动用 pending + 延时刷新
 *    收敛成最后一次，避免换挡过程播一串。
 * 3. **熄火未关车窗提醒**：`car.basic.power_mode` 由非 0 → 0（Binder 断开时用
 *    `engineOn` 兜底），1500ms 后检查四窗 + 天窗开度，有未关就高优先级播 `window.wav`，
 *    同一次熄火只提醒一次；音效文件缺失时退回系统 TTS「车窗未关闭，请检查」。
 *
 * 信号帧来自 Binder / 模拟源的后台线程，所有状态与播放都收敛到主线程 Handler。
 */
object SceneAnnouncer {

    private const val TAG = "H6Scene"
    private const val DOOR_DEBOUNCE_MS = 320L      // 旧版 0x140
    private const val GEAR_DEBOUNCE_MS = 180L      // 旧版 0xb4
    private const val START_IGNORE_MS = 3000L      // 旧版 0xbb8：启动 3s 内不播基线开门
    private const val OFF_CHECK_DELAY_MS = 1500L   // 旧版 0x5dc
    private const val OFF_THROTTLE_MS = 5000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Application? = null
    @Volatile private var attached = false
    @Volatile private var lastLogText = "等待信号触发"
    @Volatile private var lastOffMs = 0L

    // 车门：原始值 1 开 / 0 关 / -1 未知
    private val doorRaw = IntArray(5) { -1 }
    private val doorLastPlayMs = LongArray(5)
    private var lastGear: String? = null
    private var pendingGear: String? = null
    private var lastGearPlayMs = 0L
    private var powerRaw = -1
    private var lastEngineOn: Boolean? = null
    private var startedAtMs = 0L
    private var tts: TextToSpeech? = null

    private val flushGear = Runnable {
        val g = pendingGear
        pendingGear = null
        if (g != null && g != lastGear) doGear(g)
    }

    private val checkWindowsAfterOff = Runnable { checkWindows() }

    fun attach(app: Application, scope: CoroutineScope) {
        if (attached) return
        attached = true
        this.app = app
        SceneStore.attach(app)
        VoicePlayer.attach(app)
        VoicePlayer.setEnabled(SceneStore.master())
        startedAtMs = SystemClock.uptimeMillis()
        VoicePlayer.preload(SceneStore.packFiles())
        scope.launch(Dispatchers.Default) {
            SignalHub.state.collect { onFrame(it) }
        }
        log("播报引擎已启动 · ${SceneStore.packName()}音色 · ${SceneStore.audioName()}")
    }

    /** 供设置页显示：最近一次判定 / 播报的文字，手机没声音也能验收逻辑。 */
    fun lastLog(): String = lastLogText

    fun setEnabled(v: Boolean) {
        SceneStore.setMaster(v)
        VoicePlayer.setEnabled(v)
        log(if (v) "播报提醒已开启" else "播报提醒已关闭")
    }

    /** 换音色包 / 音频通道后重建播放器缓存并重新预加载。 */
    fun reloadSounds() {
        VoicePlayer.clearCache()
        VoicePlayer.preload(SceneStore.packFiles())
        log("已切换到${SceneStore.packName()}音色 · ${SceneStore.audioName()}")
    }

    /** 音频通道切换后同步刷新「最近一次判定」，避免还显示旧通道名。 */
    fun noteRouteChanged() {
        log("播报通道 → ${SceneStore.audioName()} · 提示音量 ${SceneStore.volume()}%")
    }

    /** 一帧车身信号：三条判定都在这里驱动。 */
    fun onFrame(s: CarSignal) {
        if (!SceneStore.master()) return
        main.post {
            handleDoors(s)
            handleGear(s)
            handlePower(s)
        }
    }

    // ---------------- ① 车门开关播报 ----------------

    private fun handleDoors(s: CarSignal) {
        if (s.doors.isEmpty()) return
        for (i in 0..4) {
            val v = s.doors.getOrElse(i) { -1 }
            if (v < 0) continue                 // 读不到就保留上一轮，别误报关门
            val prev = doorRaw[i]
            if (prev < 0) {
                // 基线：旧版启动 3s 内不播「本来就开着」的门，避免上电就吵
                doorRaw[i] = v
                if (SceneStore.isDoorOpen(v) &&
                    SystemClock.uptimeMillis() - startedAtMs > START_IGNORE_MS &&
                    SceneStore.doorsOn()
                ) playDoor(i, true)
                continue
            }
            if (prev == v) continue
            doorRaw[i] = v
            val now = System.currentTimeMillis()
            if (now - doorLastPlayMs[i] < DOOR_DEBOUNCE_MS) {
                log("${SceneStore.DOOR_NAMES[i]} 抖动跳过（$v）")
                continue
            }
            if (!SceneStore.doorsOn()) continue
            doorLastPlayMs[i] = now
            playDoor(i, SceneStore.isDoorOpen(v))
        }
    }

    private fun playDoor(index: Int, open: Boolean) {
        val f = SceneStore.doorFile(index, open)
        log("${SceneStore.DOOR_NAMES[iSafe(index)]} ${if (open) "打开" else "关闭"} → $f")
        VoicePlayer.play(f)
    }

    private fun iSafe(i: Int) = i.coerceIn(0, 4)

    // ---------------- ② 换挡档位播报 ----------------

    private fun handleGear(s: CarSignal) {
        val g = s.gear.trim().uppercase(Locale.ROOT)
        if (g !in SceneStore.GEAR_CHARS) return
        if (lastGear == null) {
            lastGear = g                        // 首帧只记基线
            return
        }
        if (g == lastGear) {
            pendingGear = null
            return
        }
        val wait = GEAR_DEBOUNCE_MS - (System.currentTimeMillis() - lastGearPlayMs)
        if (wait > 0) {
            pendingGear = g
            main.removeCallbacks(flushGear)
            main.postDelayed(flushGear, wait)
            return
        }
        doGear(g)
    }

    private fun doGear(g: String) {
        lastGear = g
        lastGearPlayMs = System.currentTimeMillis()
        if (!SceneStore.gearOn()) return
        val f = SceneStore.gearFile(g)
        log("挡位 $g → $f")
        VoicePlayer.play(f)
    }

    // ---------------- ③ 熄火未关车窗提醒 ----------------

    private fun handlePower(s: CarSignal) {
        val p = s.powerMode
        if (p >= 0) {
            val prev = powerRaw
            powerRaw = p
            if (prev > 0 && p == 0) onPowerOff()
        } else {
            // 平台属性读不到时，用 ICarInfo 0x1C 发动机状态兜底
            val e = s.engineOn
            val prev = lastEngineOn
            lastEngineOn = e
            if (prev == true && !e) onPowerOff()
        }
    }

    private fun onPowerOff() {
        val now = SystemClock.uptimeMillis()
        if (now - lastOffMs < OFF_THROTTLE_MS) return
        lastOffMs = now
        SceneStore.setWarnedThisPowerOff(false)
        main.removeCallbacks(checkWindowsAfterOff)
        main.postDelayed(checkWindowsAfterOff, OFF_CHECK_DELAY_MS)
        log("检测到熄火 → 1500ms 后检查车窗")
    }

    private fun checkWindows() {
        if (!SceneStore.master() || !SceneStore.windowOn()) return
        if (SceneStore.warnedThisPowerOff()) return
        val n = SceneStore.openWindowCount(SignalHub.current())
        if (n <= 0) {
            log("熄火检查 → 车窗 / 天窗全部关闭，无需提醒")
            return
        }
        SceneStore.setWarnedThisPowerOff(true)
        val f = SceneStore.windowFile()
        if (VoicePlayer.hasFile(f)) {
            log("熄火未关车窗 $n 扇 → 优先播报 $f")
            VoicePlayer.playPriority(f)
        } else {
            speakWindow(n)
        }
    }

    /** 旧版 speakWindowTts()：音效缺失时用系统 TTS 兜底。 */
    private fun speakWindow(n: Int) {
        val text = if (n >= 3) "车窗未关闭，请检查" else "有车窗未关闭，请检查"
        log("熄火未关车窗 $n 扇 → 音效缺失，改用 TTS：$text")
        speakText(text, "h6win")
    }

    /**
     * 让别的场景复用同一个 TTS 实例（6.0.65 起胎压告警走这里）。
     *
     * 为什么不各自 New 一个：车机上两个 TextToSpeech 实例会抢音频焦点，
     * 后建的把前一个正在念的掐掉，表现为「同时来两条提醒，只有一条念得出来」。
     * 复用同一个实例 + QUEUE_FLUSH 就是「后来的盖掉先来的」，符合提醒场景的预期。
     *
     * 这里**不做语言设置**：沿用旧版行为，跟着车机默认 TTS 语言走。
     * 需要注意 TTS 引擎缺失时 [TextToSpeech] 初始化回调 `status != SUCCESS`，
     * 此时静默失败（车辆提醒不能因为念不出来就崩掉或卡住）。
     */
    fun speakText(text: String, tag: String = "h6scene") {
        val c = app ?: return
        main.post {
            val t = tts
            if (t == null) {
                tts = TextToSpeech(c) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, tag)
                    }
                }
            } else {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, tag)
            }
        }
    }

    /** 供其他场景（胎压告警）把一行判定写进设置页的「最近一次判定」。 */
    fun note(msg: String) = log(msg)

    // ---------------- 设置页试听 / 模拟入口 ----------------

    /** 无车机信号的手机上验证熄火检查：直接走一次熄火后的 1.5s 检查流程。 */
    fun testPowerOff() {
        lastOffMs = 0L
        onPowerOff()
    }

    fun previewDoor(index: Int, open: Boolean) = main.post { playDoor(index, open) }
    fun previewGear(g: String) = main.post {
        if (g in SceneStore.GEAR_CHARS) {
            val f = SceneStore.gearFile(g)
            log("试听挡位 $g → $f")
            VoicePlayer.play(f)
        }
    }
    fun previewWindow() = main.post {
        val f = SceneStore.windowFile()
        log("试听熄火车窗提醒 → $f")
        VoicePlayer.playPriority(f)
    }

    private fun log(msg: String) {
        lastLogText = msg
        Log.i(TAG, msg)
    }
}
