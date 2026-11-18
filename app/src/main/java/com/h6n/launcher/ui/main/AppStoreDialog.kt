package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AppStore
import com.h6n.launcher.core.UpdateManager
import java.io.File
import kotlin.math.roundToInt

/**
 * 应用商店面板（自绘，不用 AlertDialog.setAdapter —— 车机上会出空列表）。
 *
 * 每次打开都调 [AppStore.load] 实时解析分享链接，不缓存清单；后期往文件夹加 / 换 APK，
 * 下次打开就能看到。每行：文件名 + 大小 + 状态按钮（下载 / 安装 / 已下载）。
 * 安装复用 [UpdateManager.installFile] 那条已验证的应用管家通道。
 *
 * 分享目录地址在设置页里填（默认是占位域名），本仓库不带任何私有服务端。
 */
object AppStoreDialog {

    private val GOLD = 0xFFE0B44C.toInt()
    private val WHITE = 0xFFF5F6F8.toInt()
    private val DIM = 0xFF9AA0A6.toInt()
    private val CARD = 0xF21A1D24.toInt()
    private val ROW = 0x16FFFFFF.toInt()
    private val GREEN = 0xFF3ECF6E.toInt()

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

    private fun round(fill: Int, r: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r.toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(maxOf(1, sw), stroke)
        }

    private fun tv(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    private fun btn(c: Context, s: String, fill: Int, fg: Int, onClick: () -> Unit): TextView =
        tv(c, s, 21f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
            background = round(fill, dp(c, 10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.CHINA, "%.2f GB", b / 1073741824.0)
        b >= 1024L * 1024 -> String.format(java.util.Locale.CHINA, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.CHINA, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun show(act: Activity) {
        AppStore.attach(act)
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x12, 0x14, 0x18))
        }

        // 状态与列表先声明，标题栏的按钮 lambda 要引用它们（Kotlin 不允许前向引用）
        val status = tv(act, "正在读取应用列表…", 21f, DIM).apply {
            setPadding(dp(act, 20), 0, dp(act, 20), dp(act, 10))
        }
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        // ---------- 标题栏 ----------
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 20), dp(act, 16), dp(act, 20), dp(act, 12))
        }
        head.addView(tv(act, "应用商店", 33f, GOLD, true))
        head.addView(View(act).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        // 6.0.91：按用户要求去掉「地址」按钮，商店固定读内置分享目录，不再露出地址入口
        val refreshBtn = btn(act, "刷新", 0x22FFFFFF, WHITE) { refresh(act, dlg, listBox, status) }
        head.addView(refreshBtn, LinearLayout.LayoutParams(dp(act, 118), dp(act, 56)).apply { leftMargin = dp(act, 10) })
        val closeBtn = btn(act, "关闭", 0x2EFFFFFF, WHITE) { dlg.dismiss() }
        head.addView(closeBtn, LinearLayout.LayoutParams(dp(act, 118), dp(act, 56)).apply { leftMargin = dp(act, 10) })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        // ---------- 列表 ----------
        val scroll = ScrollView(act).apply {
            setPadding(dp(act, 14), 0, dp(act, 14), dp(act, 14))
            addView(listBox, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = w.attributes
            lp.dimAmount = 0.72f
            w.attributes = lp
        }
        dlg.setOnShowListener { dlg.window?.setLayout(dp(act, 900), dp(act, 660)) }
        dlg.show()
        refresh(act, dlg, listBox, status)
    }

    /** 实时拉列表并重建每一行。 */
    private fun refresh(act: Activity, dlg: Dialog, listBox: LinearLayout, status: TextView) {
        status.text = "正在读取应用列表…"
        listBox.removeAllViews()
        Thread(Runnable {
            val apps = try {
                AppStore.load()
            } catch (t: Throwable) {
                act.runOnUiThread {
                    status.text = "读取失败：${AppStore.brief(t)}（点「刷新」重试）"
                }
                return@Runnable
            }
            act.runOnUiThread {
                if (!dlg.isShowing) return@runOnUiThread
                if (apps.isEmpty()) {
                    status.text = "文件夹里没有 APK"
                    return@runOnUiThread
                }
                status.text = "共 ${apps.size} 个应用 · 点「下载」后选「安装」"
                for (a in apps) listBox.addView(buildRow(act, a))
            }
        }, "h6-appstore-list").start()
    }

    private fun buildRow(act: Activity, a: AppStore.App): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14))
            background = round(ROW, dp(act, 14))
        }
        val info = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        info.addView(tv(act, a.label, 24f, WHITE, true))
        info.addView(tv(act, fmtSize(a.size), 18f, DIM).apply { setPadding(0, dp(act, 5), 0, 0) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val action = btn(act, "下载", 0x22FFFFFF, GOLD) {}
        row.addView(action, LinearLayout.LayoutParams(dp(act, 136), dp(act, 56)))

        if (AppStore.cached(a) != null) {
            action.text = "安装"
            action.setTextColor(GREEN)
        }

        /** 本次点击真正要做的事：有缓存就装，没有就下。 */
        fun proceed() {
            val cached = AppStore.cached(a)
            if (cached != null) install(act, cached) else download(act, a, action)
        }

        action.setOnClickListener { proceed() }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2))
            setPadding(0, 0, 0, dp(act, 8))
        }
    }

    private fun download(act: Activity, a: AppStore.App, action: TextView) {
        action.isEnabled = false
        action.text = "0%"
        action.setTextColor(DIM)
        AppStore.download(
            a,
            onProgress = { pct -> action.text = "$pct%" },
            onDone = { file ->
                action.isEnabled = true
                if (file != null) {
                    action.text = "安装"
                    action.setTextColor(GREEN)
                    Toast.makeText(act, "已下载：${a.label}", Toast.LENGTH_SHORT).show()
                } else {
                    action.text = "重试"
                    action.setTextColor(0xFFFF6B5B.toInt())
                    Toast.makeText(act, "下载失败：${AppStore.lastError}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun install(act: Activity, apk: File) {
        try {
            val msg = UpdateManager.installFile(act, apk)
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(act, "拉起安装失败：${AppStore.brief(t)}", Toast.LENGTH_LONG).show()
        }
    }

}