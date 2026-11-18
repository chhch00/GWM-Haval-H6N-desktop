package com.h6n.launcher.core

import android.content.Context
import android.os.SystemClock

/**
 * 发动机累计运行时间（原版 `EngineHours` 的等价实现）。
 *
 * ## 为什么需要它
 * 机油寿命按"跑了多少公里"算并不准：怠速、堵车、长时间着车吹空调都会让机油老化，
 * 但里程几乎不动。原版保养卡就是按**发动机运行小时**折算的。
 *
 * ## 计时方式
 * 判据是 ICarInfo `fx=0x15 item=0x1C`（`CAR_BASIC_ENGINE_STATE`，>0 = 运转），
 * 由 [CarSignal.engineOn] 每秒喂进来：
 * ```
 * 点火 → runAt = now（开始在跑）
 * 熄火 → sec += now - runAt；runAt = 0
 * ```
 * 用 `elapsedRealtime()` 而不是墙钟：车机休眠/对时都不会把计时算错。
 *
 * 数据落 SharedPreferences（`h6_engine_hours`），每 30 秒和每次状态切换各写一次，
 * 不会因为桌面被杀丢一大段。
 */
object EngineHours {

    private const val PREFS = "h6_engine_hours"
    private const val K_SEC = "sec"
    private const val K_RUN_AT = "runAt"
    private const val K_OFFSET = "offsetSec"

    private var ctx: Context? = null

    /** 已结算的累计秒数（本机自动计时部分）。 */
    @Volatile private var sec = 0L

    /**
     * 用户手动填的基数（秒）。
     *
     * 车机不一定从"新车 0 小时"开始用我们的桌面 —— 装上前已经跑了几百小时，
     * 或者换了仪表/刷了机。设置页「维护 → 保养 → 发动机累计时间」写这个值：
     * ```
     * 累计 = offsetSec + sec + 当前这段运行时间
     * ```
     * 改基数不会动 `sec`，所以保存之后仍然按秒继续累加，只是起点变了。
     */
    @Volatile private var offsetSec = 0L

    /** 本次点火开始计时的 elapsedRealtime；0 表示没在计时。 */
    @Volatile private var runAt = 0L

    @Volatile private var lastSaveAt = 0L
    @Volatile private var lastEngineOn: Boolean? = null
    @Volatile private var loaded = false

    fun attach(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        load()
    }

    private fun load() {
        val p = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        sec = p.getLong(K_SEC, 0L).coerceAtLeast(0L)
        offsetSec = p.getLong(K_OFFSET, 0L).coerceAtLeast(0L)
        runAt = p.getLong(K_RUN_AT, 0L)
        // 上次进程是在"运行中"被杀掉的：把这段补进累计值
        if (runAt > 0L) {
            val now = SystemClock.elapsedRealtime()
            if (now > runAt) sec += (now - runAt) / 1000L
            runAt = 0L
            save()
        }
        loaded = true
        lastSaveAt = SystemClock.elapsedRealtime()
    }

    /** 每秒喂一次即可；内部只在状态变化 / 30 秒时落盘。 */
    fun tick(engineOn: Boolean) {
        if (!loaded) load()
        val now = SystemClock.elapsedRealtime()
        val prev = lastEngineOn
        if (prev != engineOn) {
            if (engineOn) {
                runAt = now
            } else if (runAt > 0L) {
                sec += ((now - runAt) / 1000L).coerceAtLeast(0L)
                runAt = 0L
            }
            lastEngineOn = engineOn
            save()
            return
        }
        if (engineOn && runAt == 0L) {
            // 进程刚起、上一帧没喂到：从这一秒开始算
            runAt = now
        }
        if (now - lastSaveAt > 30_000L) save()
    }

    /** 累计秒数（用户基数 + 自动计时 + 当前正在跑的这一段）。 */
    fun totalSeconds(): Long {
        val now = SystemClock.elapsedRealtime()
        val running = if (runAt > 0L && now > runAt) (now - runAt) / 1000L else 0L
        return offsetSec + sec + running
    }

    fun totalHours(): Float = totalSeconds() / 3600f

    fun isRunning(): Boolean = runAt > 0L

    /**
     * 手动把"发动机累计时间"设成某个值（设置页保存时调用）。
     *
     * 只挪基数：`offsetSec = 目标 - 本机已计 - 当前这段`，之后照常每秒累加。
     * 保存这一刻读数就是用户填的值，桌面机油寿命随即按新值重算。
     */
    fun setTotal(hours: Int, minutes: Int) {
        if (!loaded) load()
        val target = (hours.coerceAtLeast(0) * 3600L + minutes.coerceIn(0, 59) * 60L)
        val now = SystemClock.elapsedRealtime()
        val running = if (runAt > 0L && now > runAt) (now - runAt) / 1000L else 0L
        offsetSec = (target - sec - running).coerceAtLeast(0L)
        save()
    }

    /**
     * 配置备份恢复：把累计读数精确还原到 [total] 秒。
     *
     * 直接落 offsetSec（清掉本机自动计时），并把正在跑的这一段从基数里扣掉，
     * 保证恢复后 [totalSeconds] 立刻等于备份值，之后仍按秒继续累加。
     */
    fun restoreSeconds(total: Long) {
        if (!loaded) load()
        val target = total.coerceAtLeast(0L)
        val now = SystemClock.elapsedRealtime()
        val running = if (runAt > 0L && now > runAt) (now - runAt) / 1000L else 0L
        sec = 0L
        offsetSec = (target - running).coerceAtLeast(0L)
        save()
    }

    /** "126h 20m" / "24m"（机油卡与设置页共用）。 */
    fun text(): String {
        val s = totalSeconds()
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** 小时 + 分钟拆开，给"手动填写"的输入框回填用。 */
    fun hoursPart(): Int = (totalSeconds() / 3600).toInt()
    fun minutesPart(): Int = ((totalSeconds() % 3600) / 60).toInt()

    fun reset() {
        offsetSec = 0L
        sec = 0L
        runAt = if (lastEngineOn == true) SystemClock.elapsedRealtime() else 0L
        save()
    }

    private fun save() {
        lastSaveAt = SystemClock.elapsedRealtime()
        val p = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        // runAt 一起写：进程被杀后下次启动能续上"运行中"的这一段
        p.edit()
            .putLong(K_SEC, sec)
            .putLong(K_OFFSET, offsetSec)
            .putLong(K_RUN_AT, runAt)
            .apply()
    }
}
