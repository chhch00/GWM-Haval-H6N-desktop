package com.h6n.launcher.core

import java.util.Locale

/**
 * 机油剩余寿命 —— 桌面「机油寿命」卡与设置页「维护 → 保养」共用同一份算法。
 *
 * ## 口径（保养计划已整体删除，只剩这一条线）
 * ```
 * 周期  = 固定 200h（按需求写死，不再提供修改入口）
 * 已用  = 发动机累计小时（含用户手填的基数）
 * 剩余h = 周期 - 已用
 * 剩余% = 剩余h / 周期    ← 只在内部用于配色，不上屏
 * ```
 * 「保养计划」弹窗（里程 / 上次保养累计 / 机油周期）已从桌面与设置页一并删除，
 * 长按机油卡改为直接改 [EngineHours] 的累计运行时间，保存后这里立刻重算。
 * 之前存进 `maint.lastHours` / `maint.cycleHours` 的用户覆盖一律不再读取 ——
 * 避免旧数据（比如把上次保养累计填成 199h）把剩余时间顶成恒 200h。
 */
object OilLife {

    /** 机油更换周期：固定 200 小时。 */
    const val CYCLE_HOURS = 200

    data class State(
        /** 剩余寿命百分比 0~100（只用于配色，不显示） */
        val pct: Int,
        /** 机油周期（小时），恒为 [CYCLE_HOURS] */
        val cycleH: Int,
        /** 发动机累计小时（含用户手动基数） */
        val totalH: Float,
        /** 本轮已用小时 */
        val usedH: Float,
        /** 剩余可用小时 */
        val leftH: Float
    ) {
        val expired: Boolean get() = leftH <= 0f
    }

    fun compute(): State {
        val cycleH = CYCLE_HOURS
        val totalH = EngineHours.totalHours().coerceAtLeast(0f)
        val usedH = totalH
        val leftH = (cycleH - usedH).coerceAtLeast(0f)
        val pct = (leftH * 100f / cycleH).toInt().coerceIn(0, 100)
        return State(pct, cycleH, totalH, usedH, leftH)
    }

    /** "74h" / "9.5h" —— 剩余小时，10h 以上只留整数。 */
    fun hText(h: Float): String =
        if (h >= 10f) "${h.toInt()}h" else String.format(Locale.CHINA, "%.1fh", h)

    /** 一行摘要，设置页/卡片共用：`剩 74h（按 200h 一换）`。 */
    fun summary(st: State): String =
        if (st.expired) "已到期（按 ${st.cycleH}h 一换）"
        else "剩 ${hText(st.leftH)}（按 ${st.cycleH}h 一换）"
}
