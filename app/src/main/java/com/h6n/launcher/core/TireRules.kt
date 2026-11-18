package com.h6n.launcher.core

/**
 * 胎压告警的判据 —— **单一来源**。
 *
 * 车模贴片颜色（[com.h6n.launcher.ui.main.CarModelView]）、告警引擎（[TireAlert]）、
 * 弹窗文案（[com.h6n.launcher.ui.main.H6Dialogs]）与设置页说明文字都从这里取，
 * 避免出现「卡片红了但不播报」或者「弹窗写的 240、实际按 230 判」这种两份真相。
 *
 * 6.0.68 起两个**触发**阈值（过低 / 极差）可以在设置页改，落在 [SceneStore] 的
 * `h6_scene` 里，默认仍是 240 / 15 —— 与 6.0.65~6.0.67 的行为逐字一致。
 * 迟滞宽度（[LOW_GAP_KPA] / [DIFF_GAP_KPA]）与上限 [HIGH_KPA] 不给用户改：
 * 前者是防抖用的，后者是传感器的物理量级，都不是「口味」问题。
 *
 * 都写成函数而不是 `const val`：draw 每秒跑一次、告警每秒判一次，
 * 直接读 SharedPreferences 是内存读（首次之后有快照），这点开销可以忽略，
 * 换来的是「设置页一改立刻生效」，不需要额外的失效通知机制。
 */
object TireRules {

    /** 默认「胎压过低」阈值，用户 2026-11-15 指定 240 kPa。 */
    const val DEFAULT_LOW_KPA = 240

    /** 默认「四轮极差过大」阈值，用户指定 15 kPa。 */
    const val DEFAULT_DIFF_KPA = 15

    /** 可调下限：再低就是真扎钉了，肉眼看得出，不需要阈值提醒。 */
    const val LOW_MIN_KPA = 180

    /** 可调上限：超过它就和 [HIGH_KPA] 的判据打架。 */
    const val LOW_MAX_KPA = 300

    /** 极差可调范围。 */
    const val DIFF_MIN_KPA = 10
    const val DIFF_MAX_KPA = 50

    /**
     * 迟滞宽度：触发阈值 N → 回升到 N + 5 才算恢复。
     * 否则 239→241→239 这种传感器抖动会反复播报。
     */
    const val LOW_GAP_KPA = 5

    /** 极差迟滞 3 kPa，同理。 */
    const val DIFF_GAP_KPA = 3

    /** 高于此值算胎压过高（沿用旧车模判据的上限，不可调）。 */
    const val HIGH_KPA = 320

    /** 有效压力下限：低于它视为该轮没有传感器读数，不参与判定。 */
    const val VALID_MIN = 1

    /** 当前「胎压过低」触发阈值（设置页可改，默认 240）。 */
    fun lowKpa(): Int = runCatching { SceneStore.tireLowKpa() }.getOrDefault(DEFAULT_LOW_KPA)

    /** 过低恢复线 = 触发阈值 + 迟滞，默认 245。 */
    fun lowResetKpa(): Int = lowKpa() + LOW_GAP_KPA

    /** 当前「四轮极差」触发阈值（设置页可改，默认 15）。 */
    fun diffKpa(): Int = runCatching { SceneStore.tireDiffKpa() }.getOrDefault(DEFAULT_DIFF_KPA)

    /** 极差恢复线 = 触发阈值 - 迟滞，默认 12。 */
    fun diffResetKpa(): Int = (diffKpa() - DIFF_GAP_KPA).coerceAtLeast(0)
}
