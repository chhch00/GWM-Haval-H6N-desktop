package com.h6n.launcher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log

/**
 * 空调「内 / 外循环」切换（6.0.100，6.0.104 加通道自检）。
 *
 * ## 通道从哪来
 * 全部对着《H6N-ICarInfo信号总表》（`SVCarInfo.apk` 五 dex 导出）里的两张表：
 * ```
 * HvacID      ID_HVAC_RECIRCULATION = 0x08   读
 * HvacID$Send ID_HVAC_RECIRCULATION = 0x09   写（直连）
 * HvacID$Send ID_VR_AIR_CIRCULATION = 0x66   写（原厂语音助手那条请求，备选）
 * 模块        ModuleID.ID_HVAC = 0x07        → funcIndex 也是 7（0..0x0F 同值）
 * ```
 * **读、写是两套编号**，不能拿读的 0x08 去写（0x08 在写表里是「风量 −」）。
 *
 * ## 为什么要回读 + 为什么要试两条
 * 车控只保证「写进了 Binder」，MCU 认不认是另一回事（值域不符、空调没上电都会静默丢弃）。
 * 6.0.100 只写 `0x09`，实车回报「点了没反应、依旧是外循环」；而同一套固件里
 * 语音助手走的是 `0x66`。所以现在是：**先写上次生效过的那条，再按顺序试另一条**，
 * 每条都等 400ms 回读，哪条回读到目标状态就把它记下来，下次直接用（省一次等待）。
 *
 * ## 值域反相
 * 总表只给了 item 编号，没写 0/1 谁是内循环。默认按业界惯例 `1 = 内循环`；
 * 万一实车相反，设置页「数值反相」一键翻过来。反相只作用于**发出去的值**，
 * 回读照原样解释。
 */
object Hvac {

    private const val TAG = "H6Hvac"

    private const val SP = "h6_hvac"

    /** 值域反相：true = 发 0 表示内循环。 */
    private const val K_REV = "rev"

    /** 上一次确认到的状态（1 内 / 0 外 / -1 不知道），读不到车况时用来决定往哪边切。 */
    private const val K_LAST = "last"

    /** 上次生效过的写通道（item 编号）；0 = 还没标定过。 */
    private const val K_CH = "ch"

    /** 直连请求（HvacID$Send.ID_HVAC_RECIRCULATION）。 */
    const val CH_DIRECT = 0x09

    /** 语音助手请求（HvacID$Send.ID_VR_AIR_CIRCULATION）。 */
    const val CH_VR = 0x66

    /** 候选写通道，顺序即尝试顺序（标定过的那条会被提到最前）。 */
    private val CHANNELS = listOf(CH_DIRECT, CH_VR)

    /** 下发后等多久回读（毫秒）。车控是异步的，太快读回来还是旧值。 */
    private const val SETTLE_MS = 400L

    private val MAIN = Handler(Looper.getMainLooper())

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun chName(item: Int): String = when (item) {
        CH_DIRECT -> "直连"
        CH_VR -> "语音"
        else -> "0x" + item.toString(16)
    }

    // ------------------------------------------------------------------ 配置

    fun inverted(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(K_REV, false) }.getOrDefault(false)

    /** 点一下翻一次，返回新值。 */
    fun toggleInverted(ctx: Context): Boolean {
        val v = !inverted(ctx)
        sp(ctx).edit().putBoolean(K_REV, v).apply()
        Log.i(TAG, "内循环值域反相 → $v")
        return v
    }

    /** 上次生效过的写通道；-1 = 还没标定。 */
    fun channel(ctx: Context): Int {
        val v = runCatching { sp(ctx).getInt(K_CH, 0) }.getOrDefault(0)
        return if (v == CH_DIRECT || v == CH_VR) v else -1
    }

    fun channelText(ctx: Context): String =
        channel(ctx).let { if (it < 0) "还没标定（会自动两条都试）" else "0x${it.toString(16)} · ${chName(it)}" }

    private fun saveChannel(ctx: Context, item: Int) {
        if (channel(ctx) == item) return
        sp(ctx).edit().putInt(K_CH, item).apply()
        Log.i(TAG, "记住可用通道：0x${item.toString(16)}（${chName(item)}）")
    }

    /** 标定过的那条排最前，避免每次都先白等 400ms。 */
    private fun ordered(ctx: Context): List<Int> {
        val ch = channel(ctx)
        return if (ch < 0) CHANNELS else listOf(ch) + CHANNELS.filter { it != ch }
    }

    /** 上次确认到的状态：1 内 / 0 外 / -1 不知道。 */
    fun last(ctx: Context): Int =
        runCatching { sp(ctx).getInt(K_LAST, -1) }.getOrDefault(-1).let { if (it == 0 || it == 1) it else -1 }

    private fun saveLast(ctx: Context, v: Int) {
        sp(ctx).edit().putInt(K_LAST, v).apply()
        // 顺手推进信号总线：刷新循环 3 秒一轮，桌面图标不用等那么久
        SignalHub.update { it.copy(hvacRecirc = v) }
    }

    /** 当前状态：1 内 / 0 外 / -1 未知。优先车况总线，读不到再退回上次确认值。 */
    fun state(ctx: Context): Int {
        val s = SignalHub.current().hvacRecirc
        return if (s == 0 || s == 1) s else last(ctx)
    }

    fun stateText(ctx: Context): String = when (state(ctx)) {
        1 -> "内循环"
        0 -> "外循环"
        else -> "未读取到（车机没连上或该车型不支持）"
    }

    // ------------------------------------------------------------------ 切换

    fun setInner(ctx: Context, inner: Boolean): String {
        if (!CarBinderSource.canControl()) return "车控通道没连上（手机上没有这个服务，属正常）"

        val rev = inverted(ctx)
        // 发出去的值可能被反相；回读值是车机原样报的，解释时要还原成「内 / 外」
        val send = if (inner) 1 else 0
        val wire = if (rev) 1 - send else send

        var lastRead: Int? = null
        var tried = 0
        for (ch in ordered(ctx)) {
            if (!CarBinderSource.setRecircItem(ch, wire)) continue
            tried++
            val got = readBack()
            if (got != null && decode(got, rev) == inner) {
                saveChannel(ctx, ch)
                return done(ctx, inner)
            }
            if (got != null) lastRead = got
            Log.i(TAG, "通道 0x${ch.toString(16)} 没生效（回读 ${got ?: "null"}）")
        }

        if (tried == 0) return "下发失败：车控通道断了"

        // 都没生效：把图标刷成实车真实状态，别骗用户
        if (lastRead != null) {
            val actual = decode(lastRead, rev)
            saveLast(ctx, if (actual) 1 else 0)
            val power = CarBinderSource.hvacPower()
            // 空调没上电时 MCU 会直接丢掉循环请求，这个提示能省掉一整轮排查
            val hint = if (power == 0) "（空调没开）" else ""
            return "没生效，仍是" + (if (actual) "内循环" else "外循环") + hint
        }
        return "车机没回应（读不到循环状态，可能这版固件不开放空调写通道）"
    }

    /** 内 ↔ 外，返回给用户看的结果文案。 */
    fun toggle(ctx: Context): String {
        val cur = state(ctx)
        // 读不到就按「切到内循环」处理（车里默认外循环，先切内循环才有体感）
        val target = cur != 1
        return setInner(ctx, target)
    }

    /**
     * 异步切一次：下发 + 回读最多两条通道（每条 400ms），**绝不能在主线程调** [toggle]，
     * UI / 按键触发一律走这里。
     *
     * @param onMsg 主线程回调结果文案（转 Toast / 刷新面板）
     */
    fun toggleAsync(ctx: Context, onMsg: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext ?: ctx
        Thread {
            val msg = runCatching { toggle(app) }
                .getOrElse { "切换失败：" + it.javaClass.simpleName }
            if (onMsg != null) MAIN.post { runCatching { onMsg(msg) } }
        }.apply { name = "h6-hvac" }.start()
    }

    // ------------------------------------------------------------------ 自检

    /**
     * 通道自检：按指定 item 写一个值，等回读，回报「写出去了但状态到底变没变」。
     *
     * 用户按的时候盯着车上的空调循环指示灯 —— 哪一条能让灯变，就把那条记住
     * （这里会顺手存下来）。异步执行，回调在主线程。
     */
    fun probeAsync(ctx: Context, item: Int, value: Int, onMsg: (String) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        Thread {
            val before = runCatching { CarBinderSource.recircState() }.getOrNull()
            val sent = runCatching { CarBinderSource.setRecircItem(item, value) }.getOrDefault(false)
            val got = readBack()
            var changed = false
            if (got != null) {
                saveLast(app, got)
                changed = before == null || before != got
                if (changed) saveChannel(app, item)
            }
            val text = buildString {
                append(if (sent) "已发出" else "发送失败")
                append(" · 回读 ")
                append(got?.toString() ?: "读不到")
                when {
                    got == null -> append("（通道没回话）")
                    changed -> append("（状态变了 ✔ 这条能用）")
                    else -> append("（状态没变 ✘ 这条不通）")
                }
            }
            Log.i(TAG, "自检 0x${item.toString(16)}=$value → $text")
            MAIN.post { runCatching { onMsg(text) } }
        }.apply { name = "h6-hvac-probe" }.start()
    }

    // ------------------------------------------------------------------ 私有

    private fun readBack(): Int? {
        runCatching { Thread.sleep(SETTLE_MS) }
        return CarBinderSource.recircState()
    }

    /** 车机原值 → 语义（true 内循环）。反相时取反。 */
    private fun decode(raw: Int, rev: Boolean): Boolean {
        val inner = raw == 1
        return if (rev) !inner else inner
    }

    private fun done(ctx: Context, inner: Boolean): String {
        saveLast(ctx, if (inner) 1 else 0)
        Log.i(TAG, "已切到" + if (inner) "内循环" else "外循环")
        return if (inner) "已切到内循环" else "已切到外循环"
    }
}
