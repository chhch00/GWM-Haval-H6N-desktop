package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import com.h6n.launcher.ui.common.dp

/**
 * 「日志导出结果」弹窗（6.0.102）。
 *
 * 车机上没有文件管理器，用户唯一的诉求是「**文件到底在哪**」，所以这里不省事：
 * 完整路径直接铺在弹窗里、点一下复制，顺带说清取文件的两种办法
 * （插 U 盘 / adb pull）。样式与权限面板一致：深色圆角卡 + 金色标题。
 */
object LogDialog {

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

    private fun copy(c: Context, s: String) {
        runCatching {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("h6_log", s))
            Toast.makeText(c, "路径已复制", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * @param path   导出文件的展示路径（U 盘 / /sdcard/Download 等）
     * @param onAgain 「再导一次」回调；为 null 时不显示该按钮
     */
    fun show(act: Activity, path: String, sizeText: String = "", onAgain: (() -> Unit)? = null) {
        if (act.isFinishing || act.isDestroyed) return

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(30), act.dp(22), act.dp(30), act.dp(18))
            background = round(CARD, act.dp(16), 0x40FFC857, act.dp(1))
        }
        root.addView(text(act, "日志已导出", 26f, GOLD, true))
        root.addView(text(
            act,
            if (sizeText.isBlank()) "文件已写到下面这个位置。" else "文件已写到下面这个位置（$sizeText）。",
            15f, DIM
        ).apply { setPadding(0, act.dp(8), 0, 0) })

        // 路径块：整块可点，点一下复制
        val pathBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(12))
            background = round(0x14FFFFFF, act.dp(10), 0x334FD1FF, act.dp(1))
            isClickable = true
            setOnClickListener { copy(act, path) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = act.dp(12) }
        }
        pathBox.addView(text(act, path, 16f, ACC, true))
        pathBox.addView(text(act, "点这里复制路径", 12f, DIM).apply {
            setPadding(0, act.dp(6), 0, 0)
        })
        root.addView(pathBox)

        root.addView(text(
            act,
            "怎么取出来：① 插着 U 盘时文件在 U 盘根目录，拔下来插电脑就能打开；" +
                "② 或者用无线 adb 执行 adb pull \"$path\"",
            13.5f, DIM
        ).apply { setPadding(0, act.dp(12), 0, 0) })

        root.addView(text(
            act,
            "里面含版本 / 机型 / 权限 / 车况 / 配置 / 运行日志，车架号已打码，可直接发我。",
            13f, OK
        ).apply { setPadding(0, act.dp(8), 0, 0) })

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(16), 0, 0)
        }
        if (onAgain != null) {
            bar.addView(button(act, "再导一次") {
                dlg.dismiss()
                onAgain()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        bar.addView(button(act, "关闭", true) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (onAgain != null) leftMargin = act.dp(10)
            })
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    /** 失败弹窗：把异常原文铺出来，比 toast 有用得多。 */
    fun showFail(act: Activity, msg: String) {
        if (act.isFinishing || act.isDestroyed) return
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(30), act.dp(22), act.dp(30), act.dp(18))
            background = round(CARD, act.dp(16), 0x40FF8A80, act.dp(1))
        }
        root.addView(text(act, "导出失败", 26f, BAD, true))
        root.addView(text(act, msg, 15f, WHITE).apply {
            setPadding(0, act.dp(10), 0, 0)
        })
        root.addView(text(act, "若提示没有可写目录，先插一个 U 盘再试。", 13f, DIM).apply {
            setPadding(0, act.dp(8), 0, 0)
        })

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(16), 0, 0)
        }
        bar.addView(button(act, "知道了", true) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(680), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }
}
