package com.h6n.launcher.ui.common

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.h6n.launcher.core.KeyMap
import com.h6n.launcher.core.SteerKey
import kotlin.math.roundToInt

/**
 * 以「720 高设计稿」为基准等比缩放的 Activity 基类。
 *
 * ## 为什么必须这样做
 * `ui_config.json` 里的 colors/sizes 是**像素设计值**：windowH=720、cardH=632、mapWidth=1252、
 * 车速数字 118（sp）……它们只在 `density = screenH / 720` 时才成立。
 *
 * 原版桌面跑在 1920×720 / density=1.0 的车机上，所以 `dp(v) == v`，看起来"就是设计稿"。
 * 换到 2400×1080 的车机（density≈3.0）后，如果照常 `dp(632)` 就变成 1896px —— 比整个屏幕还高，
 * 于是卡片被压扁、118sp 的车速数字高 354px 直接顶出卡片，表现为：
 * 标题压住内容、数字只剩顶部一段弧、km/h 被挤成竖排。
 *
 * 这里把 density 改写成 `heightPixels / 720`，等价于把整个界面当成一张 720 高的设计稿等比放大：
 * ```
 * 1920×720  → k=1.0  （与原版完全一致，dp == px）
 * 2400×1080 → k=1.5  （所有尺寸 ×1.5，比例不变，正好铺满）
 * ```
 * 由于 dp 与 sp 都随 density 走，调用方一行都不用改。
 */
abstract class ScaledActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val dm = newBase.resources.displayMetrics
        // 只按高度归一：宽度方向本来就有 layoutAuto 自适应，不能再乘一次
        val k = (dm.heightPixels / DESIGN_H).coerceIn(0.6f, 3.5f)
        val conf = Configuration(newBase.resources.configuration)
        conf.densityDpi = (160f * k).roundToInt()
        // 必须把字体放大比例也归零：本车的"字体大小"设置一旦不是 1，sp 会再乘一次，
        // 表现就是卡片框没变、字全撑出框外（标题压内容、数字只剩顶部一截）。
        // 缩放统一由 density 负责，fontScale 固定 1.0，dp 与 sp 才能同比例。
        conf.fontScale = 1f
        super.attachBaseContext(newBase.createConfigurationContext(conf))
    }

    /**
     * 方向盘按键的**前台兜底**：无障碍服务没开时，桌面 / 设置页照样能按键切换模式，
     * 「按键学习」面板也是从设置页拉起来的（对话框的按键走它自己的 OnKeyListener）。
     *
     * 没轮到我们处理的键一律交回 super，音量 / 返回 / 方向键行为不变。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 按键映射学习期间优先给它，否则已绑到方向盘的那个键会被 SteerKey 先吃掉
        if (KeyMap.learning) return KeyMap.onKey(this, event) || super.dispatchKeyEvent(event)
        if (SteerKey.onKey(this, event)) return true
        if (KeyMap.onKey(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** 设计稿高度：ui_config.sizes.windowH 的默认值。 */
        const val DESIGN_H = 720f
    }
}
