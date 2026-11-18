package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.SteerKey
import com.h6n.launcher.ui.common.dp

/**
 * 「方向盘按键学习」面板（对应应用管家的按键映射 → 键值检测页）。
 *
 * 车机方向盘键是车厂私有 CAN 注入的 KeyEvent，键码因固件而异，
 * 所以不写死：点「开始检测」→ **长按**方向盘键 → 无障碍服务 / Activity 把
 * `KeyEvent.keyCode` 广播回来 → 这里显示数字和键名 → 用户确认后保存。
 *
 * 触发方式已改为长按（见 [SteerKey.onKey]），所以这里也引导用户长按：
 * 有些固件短按 / 长按会发不同 keyCode，长按连发若换了键码会记进「最近按键」
 * 供参考，但保存的仍然是按下去那一次的键码。
 *
 * ★ 自绘不用 AlertDialog.setAdapter（车机上会渲染成空列表，见 [DockPicker]）；
 * ★ 对话框自己的按键不会走 Activity.dispatchKeyEvent，所以额外挂
 *   `Dialog.setOnKeyListener`，两条入口都汇到 [SteerKey.onKey]。
 */
object SteerKeyPanel {

    private val CARD = 0xF21C1A17L.toInt()
    private val WHITE = 0xFFFFFFFFL.toInt()
    private val DIM = 0xFF9AA0A6L.toInt()
    private val GOLD = 0xFFC9A227L.toInt()
    private val ACC = 0xFF4FD1FFL.toInt()

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

    private fun button(c: Context, s: String, strong: Boolean, onClick: () -> Unit) =
        text(c, s, 18f, if (strong) WHITE else DIM, true).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
            background = round(
                if (strong) 0x2EFFC857.toInt() else 0x14FFFFFF,
                c.dp(10), if (strong) GOLD else 0x2EFFFFFF, c.dp(1)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun show(act: Activity, onChanged: () -> Unit) {
        SteerKey.beginLearn()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(28), act.dp(20), act.dp(28), act.dp(18))
            background = round(CARD, act.dp(16), 0x30FFFFFF, act.dp(1))
        }
        root.addView(text(act, "方向盘按键 · 键值检测", 26f, GOLD, true))
        root.addView(text(
            act,
            "长按要用的方向盘按键约 1 秒再松开。键值由本页自己检测，不需要提前知道键码；" +
                "触发时长可在设置页「长按时长」里调（当前 " + SteerKey.holdLabel(act) + "）。",
            16f, DIM
        ).apply { setPadding(0, act.dp(8), 0, act.dp(12)) })

        val big = text(act, "等待长按…", 34f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(10), act.dp(16), act.dp(10), act.dp(16))
            background = round(0xFF101A2A.toInt(), act.dp(14), 0x2E4FD1FF, act.dp(1))
        }
        root.addView(big, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        val recent = text(act, "最近按键：无", 15f, DIM)
        recent.setPadding(0, act.dp(10), 0, act.dp(10))
        root.addView(recent)

        val hint = text(
            act,
            "当前已绑定：" + SteerKey.keyLabel(SteerKey.keyCode(act)), 15f, ACC
        )
        root.addView(hint)

        var picked = -1

        fun redraw(code: Int) {
            picked = code
            big.text = if (code >= 0) "检测到键值 $code\n${SteerKey.keyLabel(code)}" else "等待长按…"
            big.setTextColor(if (code >= 0) ACC else WHITE)
            val h = SteerKey.recent()
            recent.text = if (h.isEmpty()) "最近按键：无"
            else "最近按键：" + h.joinToString(" · ") { "$it" }
        }

        fun refreshRecent() {
            val h = SteerKey.recent()
            recent.text = if (h.isEmpty()) "最近按键：无"
            else "最近按键：" + h.joinToString(" · ") { "$it" }
        }

        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != SteerKey.ACTION_DETECTED) return
                val code = i.getIntExtra(SteerKey.EXTRA_CODE, -1)
                if (code < 0) return
                // 长按连发若换了键码，只记进「最近按键」，别把要保存的键码覆盖掉
                if (i.getIntExtra(SteerKey.EXTRA_REPEAT, 0) > 0) refreshRecent()
                else redraw(code)
            }
        }
        val filter = IntentFilter(SteerKey.ACTION_DETECTED)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                act.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                act.registerReceiver(recv, filter)
            true
        }.getOrDefault(false)

        val dlg = AlertDialog.Builder(act).setView(root).create().apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnShowListener {
                window?.setLayout(act.dp(720), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            // 对话框窗口自己的按键：Activity.dispatchKeyEvent 收不到，必须单独接
            setOnKeyListener { _, _, ev ->
                if (ev.keyCode == KeyEvent.KEYCODE_BACK || ev.keyCode == KeyEvent.KEYCODE_HOME)
                    return@setOnKeyListener false
                val firstDown = ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0
                // 学习态下 DOWN / repeat / UP 全部交给 SteerKey 吞掉并广播
                val consumed = SteerKey.onKey(act, ev)
                // 直接刷一次兜底：某些固件不派发广播时面板也不会永远停在「等待长按」
                if (firstDown) redraw(ev.keyCode) else refreshRecent()
                consumed
            }
        }

        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(16), 0, 0)
        }
        actions.addView(button(act, "保存这个键", true) {
            if (picked < 0) {
                Toast.makeText(act, "先长按方向盘按键约 1 秒", Toast.LENGTH_SHORT).show()
            } else {
                SteerKey.bind(act, picked)
                Toast.makeText(
                    act,
                    "已绑定键值 $picked，之后长按 ${SteerKey.holdLabel(act)} 切换标准 / 运动",
                    Toast.LENGTH_SHORT
                ).show()
                dlg.dismiss()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(act, "重新检测", false) {
            SteerKey.beginLearn()
            redraw(-1)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        actions.addView(button(act, "清除绑定", false) {
            SteerKey.clear(act)
            Toast.makeText(act, "已清除，方向盘键不再接管", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(button(act, "关闭", false) { dlg.dismiss() }.apply {
            setPadding(0, act.dp(12), 0, 0)
        })

        dlg.setOnDismissListener {
            SteerKey.endLearn()
            if (registered) runCatching { act.unregisterReceiver(recv) }
            onChanged()
        }
        redraw(-1)
        dlg.show()
    }
}
