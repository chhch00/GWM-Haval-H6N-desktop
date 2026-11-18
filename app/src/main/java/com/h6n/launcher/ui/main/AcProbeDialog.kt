package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.h6n.launcher.core.CarBinderSource
import com.h6n.launcher.core.Hvac
import com.h6n.launcher.ui.common.dp

/**
 * 「空调通道自检」弹窗（6.0.104）。
 *
 * ## 为什么需要它
 * 6.0.100 只能写 `HvacID$Send.ID_HVAC_RECIRCULATION(0x09)`，实车回报「点了没反应」。
 * 同一套固件里原厂语音助手走的是 `ID_VR_AIR_CIRCULATION(0x66)`，而总表只给了编号、
 * **没写值域**（0/1 谁是内循环）。这两件事只能实车标定：
 * 用户按下按钮、盯住车上空调面板的循环灯，哪一条让灯变，就说明那条通道 + 那个值对。
 *
 * 每次按下都会写 → 等 400ms 回读 → 把结果摊在下面；**状态真的变了的那条会被记住**
 * （[Hvac] 存进 SharedPreferences），之后主页图标和方向盘按键直接用这条，不再试第二条。
 *
 * 纯 Kotlin 自绘，不用 `AlertDialog.setItems`（车机红线）。
 */
object AcProbeDialog {

    private val CARD = 0xF21C1A17L.toInt()
    private val WHITE = 0xFFFFFFFFL.toInt()
    private val DIM = 0xFF9AA0A6L.toInt()
    private val GOLD = 0xFFC9A227L.toInt()
    private val OK = 0xFF7BD88FL.toInt()
    private val ACC = 0xFF4FD1FFL.toInt()
    private val BAD = 0xFFFF8A80.toInt()

    private fun text(c: Context, s: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    private fun round(fill: Int, radius: Int, stroke: Int = 0, sw: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, sw), stroke)
        }

    private fun button(c: Context, s: String, strong: Boolean = false, onClick: () -> Unit) =
        text(c, s, 17f, if (strong) WHITE else ACC, true).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(10), c.dp(11), c.dp(10), c.dp(11))
            background = round(
                if (strong) 0x2EFFC857.toInt() else 0x14FFFFFF,
                c.dp(10), if (strong) GOLD else 0x334FD1FF, c.dp(1)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun show(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(30), act.dp(20), act.dp(30), act.dp(16))
            background = round(CARD, act.dp(16), 0x40FFC857, act.dp(1))
        }

        val title = text(act, "空调通道自检", 24f, GOLD, true)
        root.addView(title)

        val read = text(act, readText(act), 15f, ACC, true).apply {
            setPadding(0, act.dp(8), 0, 0)
        }
        root.addView(read)

        root.addView(text(
            act,
            "按下面的按钮时，盯住车上的空调面板：哪个按钮能让「内循环 / 外循环」灯真的变，" +
                "就是那条通道 + 那个值对。状态真变了会自动记住，之后主页图标和方向盘按键直接用它。",
            14f, DIM
        ).apply { setPadding(0, act.dp(8), 0, 0) })

        // 结果行（先占位，按一次刷一次）
        val result = text(act, "还没试过", 15f, DIM, true).apply {
            setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
            background = round(0x14FFFFFF, act.dp(9), 0x22FFFFFF, act.dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(10) }
        }
        root.addView(result)

        fun probe(item: Int, value: Int) {
            result.setTextColor(WHITE)
            result.text = "0x${item.toString(16)} 写 $value：发送中…"
            Hvac.probeAsync(act, item, value) { msg ->
                result.setTextColor(if (msg.contains("✔")) OK else if (msg.contains("✘")) BAD else DIM)
                result.text = "0x${item.toString(16)} 写 $value：$msg"
                read.text = readText(act)
            }
        }

        val row1 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(12) }
        }
        row1.addView(button(act, "直连 0x09 写 1") { probe(Hvac.CH_DIRECT, 1) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(button(act, "直连 0x09 写 0") { probe(Hvac.CH_DIRECT, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = act.dp(10)
            })
        root.addView(row1)

        val row2 = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(10) }
        }
        row2.addView(button(act, "语音 0x66 写 1") { probe(Hvac.CH_VR, 1) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(button(act, "语音 0x66 写 0") { probe(Hvac.CH_VR, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = act.dp(10)
            })
        root.addView(row2)

        root.addView(text(
            act,
            "提示：空调完全没开时，MCU 常会直接丢掉循环请求 —— 先把风量打开再试。" +
                "如果「写 1 反而变外循环」，到上一页把「数值反相」打开即可。",
            13f, DIM
        ).apply { setPadding(0, act.dp(10), 0, 0) })

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        bar.addView(button(act, "刷新读数") {
            read.text = readText(act)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(act, "关闭", true) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = act.dp(10)
            })
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(820), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    private fun readText(act: Context): String {
        val raw = runCatching { CarBinderSource.hvacDebug() }
            .getOrElse { "读取失败 ${it.message}" }
        return "当前：$raw · ${Hvac.stateText(act)}"
    }
}
