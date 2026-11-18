package com.h6n.launcher.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开门自动看四周：非主驾车门打开 → 弹 360 后视画面 → 5 秒后自动关闭。
 *
 * ## 为什么单独一个类，而不是挂进 [SceneAnnouncer]
 *
 * `SceneAnnouncer.onFrame()` 第一行就是 `if (!SceneStore.master()) return` ——
 * 用户把「播报提醒」总开关关掉时，整帧信号都不再往下走。本功能是**画面动作**、
 * 不是播报，不能因为关掉播报就失效，所以自己订阅 [SignalHub]，开关也独立。
 *
 * ## 360 通道的出处
 *
 * 全部来自 `SVPAS.apk`(`com.desaysv.pas`, system uid) 的反编译结果，见
 * `docs/H6N-360全景主动打开可行性.md`：
 * ```
 * BroadcastManager.initAvmBtnStateReceiver() 用两参数 registerReceiver、无 permission
 *   com.desaysv.avm.control   → doAvmControl(intent.getIntExtra("CONTROL_STATE", 0xFE))
 *   com.desaysv.avm.action.DEACTIVE → controlAvmActiveState(false)
 *
 * doAvmControl: case 3 → 先 controlAvmActiveState(true)，再 setAVMCarData(0x03, 2)
 *               即「打开 + 切后视（REAR_TO_CENTER_VIEW）」一条广播搞定
 * ```
 * 所以打开用 `control` + `CONTROL_STATE=3`，关闭用 `DEACTIVE`，**不需要 Binder 直写**，
 * 也就绕开了「直接写 item 会打乱 SVPAS 内部状态机」那条风险（可行性文档第六节第 5 条）。
 *
 * ## 刻意加的三道门控（都不是可选项）
 *
 * 1. **主驾门不触发**（用户明确要求）：下车必开主驾门，每次都弹 360 会很吵；
 *    右前 / 左后 / 左后 打开才是「有乘客上下车、要看清周围」的场景。
 *    后备箱（尾门）也不算车门，同样不触发。
 * 2. **R 挡不触发**：`rvc_service` 是 early_hal，倒车时先占屏，我们再发就是抢屏；
 *    关闭时也复查一次 R 挡 —— 期间挂上 R 挡就把画面留给原厂，不掐掉倒车影像。
 * 3. **行驶中不触发**：车速 ≥ [MAX_SPEED_KMH] 一律不发，避免高速误弹全屏。
 *
 * 另外 360 是**全屏接管**，不是桌面里的小窗（`avm3d_service` 用 EGL + SurfaceControl
 * 直出，拿不到 buffer），所以显示时长必须短、必须有界 —— 这就是 5 秒自动关闭的由来。
 */
object DoorAvm {

    private const val TAG = "H6DoorAvm"

    private const val ACTION_CONTROL = "com.desaysv.avm.control"
    private const val ACTION_DEACTIVE = "com.desaysv.avm.action.DEACTIVE"
    private const val EXTRA_CONTROL_STATE = "CONTROL_STATE"

    /**
     * `doAvmControl` 的入参：0 关闭 / 1 打开（上次视图）/ 2 前 / 3 后 / 4 左 / 5 右 / 6 下一个。
     * 用 3 就是「打开并切到后视」，正是倒车画面的构图。
     */
    private const val STATE_REAR = 3

    /** 显示时长。用户指定 5 秒。 */
    private const val SHOW_MS = 5000L

    /** 同一次动作的重复触发节流（关门再开门这种真事件不受影响）。 */
    private const val RETRIGGER_GAP_MS = 1500L

    /** 进程刚起来这段时间不触发：冷启动瞬间信号帧还没对齐，容易误判成「刚开门」。 */
    private const val WARMUP_MS = 5000L

    /** 行驶中不弹全屏。 */
    private const val MAX_SPEED_KMH = 10

    /**
     * 会触发的那几路，下标对应 [CarSignal.doors]：
     * 0 左前（主驾，排除）/ 1 右前 / 2 左后 / 3 右后 / 4 后备箱（排除）。
     */
    private val TRIGGER_DOORS = intArrayOf(1, 2, 3)

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Application? = null
    @Volatile private var attached = false
    @Volatile private var lastLogText = "等待车门信号"

    /** 5 路车门上一帧的值：1 开 / 0 关 / -1 未知。只在主线程读写。 */
    private val last = IntArray(5) { -1 }
    private var startedAtMs = 0L
    private var lastFireMs = 0L

    /** 本次 360 是否由我们打开（true 才允许 5 秒后关，别去关用户手动开的）。 */
    private var showing = false

    /** 最新挡位，来自信号帧；只用来做 R 挡门控。 */
    private var lastGear = "?"

    private val hide = Runnable {
        showing = false
        val ctx = app ?: return@Runnable
        // 期间挂上 R 挡：倒车影像已经接管，这一发 DEACTIVE 会把倒车画面掐掉
        if (lastGear.equals("R", true)) {
            log("已进入 R 挡，保留 360 交给原厂倒车影像")
            return@Runnable
        }
        if (SignalHub.current().speedKmh >= MAX_SPEED_KMH) {
            log("车速已上升，保留 360 交给原厂逻辑")
            return@Runnable
        }
        send(ctx, ACTION_DEACTIVE, -1)
        log("5 秒到，已自动关闭 360")
    }

    fun attach(app: Application, scope: CoroutineScope) {
        if (attached) return
        attached = true
        this.app = app
        SceneStore.attach(app)
        startedAtMs = SystemClock.uptimeMillis()
        scope.launch(Dispatchers.Default) {
            SignalHub.state.collect { onFrame(it) }
        }
        log("开门自动 360 已就绪（主驾与后备箱不触发 · 显示 5 秒）")
    }

    /** 设置页「最近一次判定」，没接车机也能验收逻辑。 */
    fun lastLog(): String = lastLogText

    /** 设置页改开关时留一行记录。 */
    fun note(msg: String) {
        main.post { log(msg) }
    }

    /**
     * 设置页「立即试一次」：绕开车门判定直接发一次，用来确认广播出得去、
     * SVPAS 收得到（手机上没有 SVPAS，只会留下日志）。
     */
    fun testTrigger() {
        main.post {
            showing = true
            app?.let { send(it, ACTION_CONTROL, STATE_REAR) }
            main.removeCallbacks(hide)
            main.postDelayed(hide, SHOW_MS)
            log("手动试触发 → 已发 360 后视，5 秒后自动关闭")
        }
    }

    // ------------------------------------------------------------------ 判定

    private fun onFrame(s: CarSignal) {
        main.post {
            if (s.gear != "?") lastGear = s.gear
            if (s.doors.isEmpty()) return@post

            if (!SceneStore.doorAvmOn()) {
                // 关着的时候也要跟住基线：等用户打开开关时，已经开着的门不该立刻弹一次
                for (i in 0..4) s.doors.getOrNull(i)?.let { if (it >= 0) last[i] = it }
                return@post
            }

            for (i in 0..4) {
                val v = s.doors.getOrElse(i) { -1 }
                if (v < 0) continue                 // 读不到就保留上一轮，别把「读失败」当关门
                val prev = last[i]
                last[i] = v
                if (prev < 0 || prev == v) continue  // 首帧只记基线
                if (!SceneStore.isDoorOpen(v)) continue
                if (i !in TRIGGER_DOORS) {
                    // 主驾 / 后备箱：只记日志，不弹画面
                    log("${SceneStore.DOOR_NAMES[i]} 打开 · 本路不触发")
                    continue
                }
                fire(s.speedKmh, "${SceneStore.DOOR_NAMES[i]} 打开")
            }
        }
    }

    private fun fire(speedKmh: Int, why: String) {
        val ctx = app ?: return
        val now = SystemClock.uptimeMillis()
        if (now - startedAtMs < WARMUP_MS) { log("启动预热中，忽略：$why"); return }
        if (showing) { log("360 已在本次显示窗口内，忽略：$why"); return }
        if (now - lastFireMs < RETRIGGER_GAP_MS) { log("节流中，忽略：$why"); return }
        if (speedKmh >= MAX_SPEED_KMH) { log("车速 ${speedKmh}km/h，不自动开 360（$why）"); return }
        if (lastGear.equals("R", true)) { log("R 挡由原厂倒车影像接管，不重复打开（$why）"); return }

        lastFireMs = now
        showing = true
        send(ctx, ACTION_CONTROL, STATE_REAR)
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MS)
        log("$why → 打开 360 后视，5 秒后自动关闭")
    }

    // ------------------------------------------------------------------ 广播

    /**
     * 不设 `setPackage`：SVPAS 那个接收器是**动态注册**在 system uid 进程里的，
     * 带上包名反而可能因为进程归属（`android.uid.system` 共享包）收不到。
     * 隐式广播限制只约束 manifest 静态注册的接收器，动态注册不受影响。
     */
    private fun send(ctx: Context, action: String, state: Int) {
        runCatching {
            val i = Intent(action).apply {
                if (state >= 0) putExtra(EXTRA_CONTROL_STATE, state)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            ctx.sendBroadcast(i)
        }.onFailure { log("发送 $action 失败：${it.javaClass.simpleName}") }
    }

    private fun log(msg: String) {
        lastLogText = msg
        Log.i(TAG, msg)
    }
}
