package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AutoBackup
import com.h6n.launcher.core.ConfigBackup
import com.h6n.launcher.core.Storage
import java.io.File
import kotlin.math.roundToInt

/** 配置备份 / 恢复面板：导出、浏览、恢复、删除备份、恢复出厂。 */
private val BK_GOLD = 0xFFC9A227L.toInt()
private val BK_RED = 0xFFFF453AL.toInt()
private val BK_GREEN = 0xFF3ECF6EL.toInt()
private val BK_WHITE = 0xFFFFFFFFL.toInt()
private val BK_DIM = 0xFF9AA0A6L.toInt()
private val BK_CARD = 0xF21C1A17L.toInt()
private val BK_INK = 0xFF201A10L.toInt()
private val BK_MAIN = Handler(Looper.getMainLooper())
private const val BK_MATCH = -1
private const val BK_WRAP = -2

private fun bkDp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()
private fun bkRound(fill: Int, radius: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, sw), stroke)
    }

private fun bkTv(c: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(c).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

private fun bkBtn(c: Context, s: String, fill: Int, fg: Int, onClick: () -> Unit): TextView =
    bkTv(c, s, 14f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(bkDp(c, 16), bkDp(c, 9), bkDp(c, 16), bkDp(c, 9))
        background = bkRound(fill, bkDp(c, 10))
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun bkNewDialog(act: Activity, root: View, widthDp: Int): Dialog = Dialog(act).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(root)
    window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val lp = w.attributes
        lp.dimAmount = 0.65f
        w.attributes = lp
    }
    setOnShowListener { window?.setLayout(bkDp(act, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT) }
}

object BackupDialog {

    fun show(act: Activity) {
        if (act.isFinishing) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(bkDp(act, 24), bkDp(act, 20), bkDp(act, 24), bkDp(act, 18))
                background = bkRound(BK_CARD, bkDp(act, 20), BK_GOLD, bkDp(act, 2))
            }
            root.addView(
                bkTv(act, "配置备份与恢复", 19f, BK_GOLD, true),
                LinearLayout.LayoutParams(BK_MATCH, BK_WRAP)
            )
            val info = bkTv(act, "正在读取配置…", 13f, BK_DIM)
            root.addView(info, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP).apply {
                topMargin = bkDp(act, 8)
            })
            root.addView(
                bkTv(
                    act,
                    "备份含：界面开关 / 颜色 / 布局、播报开关与通道音量、发动机累计、dock 栏、模拟信号、更新源、壁纸、方向盘按键，以及悬浮球位置、按键映射、开机任务、导航悬浮窗位置等 18 组偏好。" +
                        "\n存放：/sdcard/Download/哈弗桌面配置*.json（也认旧版 H6N桌面配置*.json）；最多保留 ${ConfigBackup.MAX_VERSIONS} 个版本，超出自动删最旧。",
                    12f, BK_DIM
                ),
                LinearLayout.LayoutParams(BK_MATCH, BK_WRAP).apply {
                    topMargin = bkDp(act, 8)
                    bottomMargin = bkDp(act, 10)
                }
            )

            // 冷启动自动备份开关：与 设置 → 维护 → 配置 里的是同一个开关。
            val autoRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(bkDp(act, 10), bkDp(act, 6), bkDp(act, 10), bkDp(act, 6))
                background = bkRound(0x14FFFFFF, bkDp(act, 10))
            }
            autoRow.addView(
                bkTv(act, "冷启动自动备份", 14f, BK_WHITE, true),
                LinearLayout.LayoutParams(0, BK_WRAP, 1f)
            )
            var autoBtn: TextView? = null
            fun paintAuto() {
                val on = AutoBackup.enabled(act)
                autoBtn?.text = if (on) "开" else "关"
                autoBtn?.setTextColor(if (on) BK_GOLD else BK_DIM)
            }
            val btn = bkBtn(act, "", 0x22FFFFFF, BK_WHITE) {
                val next = !AutoBackup.enabled(act)
                AutoBackup.setEnabled(act, next)
                Toast.makeText(
                    act,
                    if (next) "已开启：下次冷启动自动备份一次" else "已关闭冷启动自动备份",
                    Toast.LENGTH_LONG
                ).show()
                paintAuto()
            }
            autoBtn = btn
            paintAuto()
            autoRow.addView(btn)
            root.addView(autoRow, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP).apply {
                bottomMargin = bkDp(act, 10)
            })

            val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(actions, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP))

            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val sv = ScrollView(act).apply {
                addView(list, ViewGroup.LayoutParams(BK_MATCH, BK_WRAP))
                background = bkRound(0x10FFFFFF, bkDp(act, 12))
                setPadding(bkDp(act, 10), bkDp(act, 8), bkDp(act, 10), bkDp(act, 8))
            }
            root.addView(sv, LinearLayout.LayoutParams(BK_MATCH, bkDp(act, 250)).apply {
                topMargin = bkDp(act, 10)
                bottomMargin = bkDp(act, 10)
            })

            val status = bkTv(act, "", 12f, BK_DIM)
            root.addView(status, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP))
            val bottom = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            root.addView(bottom, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP).apply {
                topMargin = bkDp(act, 12)
            })

            val dlg = bkNewDialog(act, root, 620)
            var files: List<File> = emptyList()
            // 局部函数不能前向引用，用变量承载刷新闭包，供列表行与按钮共同调用
            var refresh: () -> Unit = {}

            fun toast(s: String) = BK_MAIN.post {
                Toast.makeText(act, s, Toast.LENGTH_LONG).show()
            }

            fun paint() {
                info.text = runCatching { ConfigBackup.summary(act) }.getOrDefault("配置读取失败")
                list.removeAllViews()
                if (files.isEmpty()) {
                    list.addView(
                        bkTv(act, "还没有备份，点「导出配置」先存一份", 14f, BK_DIM),
                        LinearLayout.LayoutParams(BK_MATCH, BK_WRAP)
                    )
                    return
                }
                for (f in files.take(30)) {
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, bkDp(act, 7), 0, bkDp(act, 7))
                        background = bkRound(0x0FFFFFFF, bkDp(act, 10))
                    }
                    val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(bkTv(act, f.name, 14f, BK_WHITE, true))
                    col.addView(
                        bkTv(
                            act,
                            ConfigBackup.time(f.lastModified()) + " · " + Storage.fmt(f.length()) +
                                " · " + ConfigBackup.shown(f),
                            11f, BK_DIM
                        )
                    )
                    row.addView(col, LinearLayout.LayoutParams(0, BK_WRAP, 1f))
                    val use = bkBtn(act, "恢复", 0x22C9A227, BK_GOLD) { confirmRestore(act, f) { refresh() } }
                    row.addView(use)
                    val del = bkBtn(act, "删", 0x22FF453A, BK_RED) {
                        confirm(act, "删除备份", "确定删除 ${f.name}？删除后无法找回。", "删除") {
                            Thread {
                                val ok = ConfigBackup.delete(f)
                                BK_MAIN.post {
                                    toast(if (ok) "已删除 ${f.name}" else "删除失败")
                                    refresh()
                                }
                            }.start()
                        }
                    }
                    row.addView(del)
                    list.addView(row, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP))
                }
            }

            fun loadFiles() {
                Thread {
                    val got = runCatching { ConfigBackup.list(act) }.getOrDefault(emptyList())
                    BK_MAIN.post { files = got; paint() }
                }.start()
            }

            refresh = {
                status.text = "已列出 ${files.size} 份备份"
                loadFiles()
            }

            actions.addView(bkBtn(act, "导出配置", 0x33C9A227, BK_GOLD) {
                Thread {
                    val r = runCatching { ConfigBackup.export(act) }
                    BK_MAIN.post {
                        r.onSuccess { f -> toast("已导出到 " + ConfigBackup.shown(f) + "\n" + ConfigBackup.summary(act)) }
                        r.onFailure { t -> toast("导出失败：${t.message}") }
                        refresh()
                    }
                }.start()
            })
            actions.addView(bkBtn(act, "刷新列表", 0x22FFFFFF, BK_WHITE) { refresh() }.apply {
                val lp = LinearLayout.LayoutParams(BK_WRAP, BK_WRAP)
                lp.leftMargin = bkDp(act, 8)
                layoutParams = lp
            })
            actions.addView(bkBtn(act, "恢复出厂", 0x22FF453A, BK_RED) {
                confirm(act, "恢复出厂配置", "清空所有界面自定义项，回到打包内置默认值。" +
                    "\n发动机累计、dock 栏、播报、按键映射、开机任务、悬浮球位置不会清掉。", "确定") {
                    Thread {
                        runCatching { ConfigBackup.reset(act) }
                        BK_MAIN.post {
                            toast("已恢复出厂配置，界面正在重建")
                            dlg.dismiss()
                            act.recreate()
                        }
                    }.start()
                }
            }.apply {
                val lp = LinearLayout.LayoutParams(BK_WRAP, BK_WRAP)
                lp.leftMargin = bkDp(act, 8)
                layoutParams = lp
            })

            bottom.addView(bkBtn(act, "关闭", 0x22FFFFFF, BK_WHITE) { dlg.dismiss() })

            dlg.show()
            refresh()
        } catch (t: Throwable) {
            Toast.makeText(act, "打开备份面板失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRestore(act: Activity, f: File, done: () -> Unit) {
        confirm(
            act, "恢复配置",
                "用这份备份覆盖当前桌面配置：\n${f.name}\n\n" +
                    "包含界面设置、播报、发动机累计、dock 栏、按键映射、开机任务、悬浮球与导航悬浮窗位置等。" +
                "\n恢复前会自动把当前配置另存一份「_导入前」备份。",
            "恢复"
        ) {
            Thread {
                val r = runCatching { ConfigBackup.restore(act, f) }
                BK_MAIN.post {
                    r.onSuccess { msg ->
                        Toast.makeText(act, msg + "\n界面正在重建", Toast.LENGTH_LONG).show()
                        done()
                        act.recreate()
                    }
                    r.onFailure { t ->
                        Toast.makeText(act, "恢复失败：${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    /** 自绘确认框：车机上系统 AlertDialog 的按钮常被挤出屏幕。 */
    private fun confirm(
        act: Activity, title: String, msg: String, ok: String, onOk: () -> Unit
    ) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(bkDp(act, 24), bkDp(act, 20), bkDp(act, 24), bkDp(act, 18))
            background = bkRound(BK_CARD, bkDp(act, 18), BK_GOLD, bkDp(act, 2))
        }
        root.addView(bkTv(act, title, 18f, BK_GOLD, true))
        root.addView(bkTv(act, msg, 14f, BK_WHITE), LinearLayout.LayoutParams(BK_MATCH, BK_WRAP).apply {
            topMargin = bkDp(act, 12)
            bottomMargin = bkDp(act, 16)
        })
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val d = bkNewDialog(act, root, 520)
        row.addView(bkBtn(act, "取消", 0x22FFFFFF, BK_WHITE) { d.dismiss() })
        row.addView(bkBtn(act, ok, 0x33C9A227, BK_GOLD) { d.dismiss(); onOk() }.apply {
            val lp = LinearLayout.LayoutParams(BK_WRAP, BK_WRAP)
            lp.leftMargin = bkDp(act, 10)
            layoutParams = lp
        })
        root.addView(row, LinearLayout.LayoutParams(BK_MATCH, BK_WRAP))
        d.show()
    }
}
