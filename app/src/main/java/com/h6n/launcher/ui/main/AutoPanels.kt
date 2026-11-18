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
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AutoActions
import com.h6n.launcher.core.BootTask
import com.h6n.launcher.core.KeyMap
import com.h6n.launcher.core.SteerKey
import com.h6n.launcher.ui.common.dp

/**
 * 自动化相关的三个自绘面板：按键映射、开机任务、以及共用的「选动作 / 选应用 / 选延迟」。
 *
 * ## 为什么全部自绘
 * 车机上 `AlertDialog.setAdapter` / `setItems` 会渲染成空列表（见 [DockPicker] 的排查记录），
 * 所以列表一律用 `LinearLayout` 手搓行；对话框也统一用透明背景 + 自绘卡片。
 *
 * ## 学习态的边界
 * 打开「按键映射」面板即进入学习（[KeyMap.beginLearn]），关闭时一定退出 ——
 * 学习态会吞掉所有非系统按键，留在后台会让音量键、切歌键全部失灵。
 */
object AutoPanels {

    private val CARD = 0xF21C1A17L.toInt()
    private val WHITE = 0xFFFFFFFFL.toInt()
    private val DIM = 0xFF9AA0A6L.toInt()
    private val GOLD = 0xFFC9A227L.toInt()
    private val ACC = 0xFF4FD1FFL.toInt()
    private val ROW_BG = 0x14FFFFFF

    // ------------------------------------------------------------------ 通用件

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
                if (strong) 0x2EFFC857.toInt() else ROW_BG,
                c.dp(10), if (strong) GOLD else 0x2EFFFFFF, c.dp(1)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 一行可点的列表项：左侧标题 + 右侧说明。 */
    private fun item(c: Context, title: String, sub: String?, onClick: () -> Unit): TextView =
        text(c, if (sub.isNullOrEmpty()) title else "$title\n$sub", 20f, WHITE).apply {
            setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
            background = round(ROW_BG, c.dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = c.dp(8) }
        }

    private fun shell(act: Activity, title: String, desc: String): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(28), act.dp(20), act.dp(28), act.dp(18))
            background = round(CARD, act.dp(16), 0x30FFFFFF, act.dp(1))
        }.also {
            it.addView(text(act, title, 26f, GOLD, true))
            it.addView(text(act, desc, 15f, DIM).apply {
                setPadding(0, act.dp(8), 0, act.dp(6))
            })
        }

    private fun show(act: Activity, root: View, widthDp: Int): AlertDialog {
        val dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        return dlg
    }

    /** 可滚动的列表容器：行数不确定时用它，避免面板高过屏幕。 */
    private fun scrollBox(act: Activity, maxDp: Int): LinearLayout {
        val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(act).apply {
            isFillViewport = false
            addView(inner, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val host = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(sc, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, act.dp(maxDp)
            ))
        }
        // 用 tag 把内层容器带出去
        host.tag = inner
        return host
    }

    private fun innerOf(host: LinearLayout): LinearLayout? = host.tag as? LinearLayout

    // ------------------------------------------------------------------ 按键映射

    fun keyMap(act: Activity, onChanged: () -> Unit) {
        KeyMap.beginLearn()
        val root = shell(
            act, "按键映射",
            "点「添加映射」→ 按一下要改的键 → 选单击还是长按 → 选动作。" +
                "学习时所有按键都被接管，关掉本面板即恢复。"
        )
        val box = scrollBox(act, 300)
        root.addView(box)

        lateinit var dlg: AlertDialog

        fun rebuild() {
            val inner = innerOf(box) ?: return
            inner.removeAllViews()
            val list = KeyMap.binds(act)
            if (list.isEmpty()) {
                inner.addView(text(act, "还没有任何映射", 17f, DIM).apply {
                    setPadding(act.dp(4), act.dp(10), 0, act.dp(6))
                })
                return
            }
            for ((i, b) in list.withIndex()) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(14), act.dp(10), act.dp(10), act.dp(10))
                    background = round(ROW_BG, act.dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = act.dp(8) }
                }
                row.addView(text(act, KeyMap.describe(act, b), 17f, WHITE).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(text(act, "删除", 16f, 0xFFFF8A80.toInt(), true).apply {
                    setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
                    setOnClickListener {
                        KeyMap.removeAt(act, i)
                        rebuild()
                        onChanged()
                    }
                })
                inner.addView(row)
            }
        }
        rebuild()

        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        actions.addView(button(act, "添加映射", true) {
            learnKey(act) { code, hold ->
                if (KeyMap.takenBySteer(act, code)) {
                    Toast.makeText(
                        act,
                        "这个键已经是「方向盘长按 → 驾驶模式」的键，请先用方向盘那一项清除，或换一个键",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    pickAction(act, null) { action ->
                        KeyMap.add(act, KeyMap.Bind(code, hold, action))
                        rebuild()
                        onChanged()
                        Toast.makeText(act, "已绑定：" + KeyMap.describe(act, KeyMap.Bind(code, hold, action)), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(act, "全部清除") {
            KeyMap.clear(act)
            rebuild()
            onChanged()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(actions)

        root.addView(button(act, "关闭") { dlg.dismiss() }.apply {
            setPadding(0, act.dp(12), 0, 0)
        })

        dlg = show(act, root, 720)
        dlg.setOnDismissListener {
            KeyMap.endLearn()
            onChanged()
        }
    }

    /**
     * 学习一个键：按一下 → 显示键值 → 让用户选触发方式。
     *
     * 学习期间按键由 [KeyMap] 吞掉（它在无障碍服务 / Activity 两个入口都被调用），
     * 这里只负责显示。对话框自己的按键不会走 `dispatchKeyEvent`，所以额外挂
     * `setOnKeyListener`（和 [SteerKeyPanel] 同一套处理）。
     */
    private fun learnKey(act: Activity, onPicked: (Int, Boolean) -> Unit) {
        val root = shell(
            act, "按一下要用的键",
            "按下方向盘 / 面板上的键，或按键盘上的任意键。系统导航键（返回 / 主页）不能映射。"
        )
        val big = text(act, "等待按键…", 30f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(10), act.dp(16), act.dp(10), act.dp(16))
            background = round(0xFF101A2A.toInt(), act.dp(14), 0x2E4FD1FF, act.dp(1))
        }
        root.addView(big, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = act.dp(10) })

        var picked = -1
        lateinit var dlg: AlertDialog

        fun paint(code: Int) {
            picked = code
            big.text = if (code < 0) "等待按键…" else "键值 $code\n" + SteerKey.keyLabel(code)
            big.setTextColor(if (code >= 0) ACC else WHITE)
        }

        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != KeyMap.ACTION_DETECTED) return
                val code = i.getIntExtra(KeyMap.EXTRA_CODE, -1)
                if (code < 0) return
                if (i.getIntExtra(KeyMap.EXTRA_REPEAT, 0) == 0) paint(code)
            }
        }
        val filter = IntentFilter(KeyMap.ACTION_DETECTED)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                act.registerReceiver(recv, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                act.registerReceiver(recv, filter)
            true
        }.getOrDefault(false)

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        row.addView(button(act, "单击触发", true) {
            if (picked < 0) toast(act, "先按一下要用的键") else {
                dlg.dismiss()
                onPicked(picked, false)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(act, "长按触发", true) {
            if (picked < 0) toast(act, "先按一下要用的键") else {
                dlg.dismiss()
                onPicked(picked, true)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(row)
        root.addView(button(act, "取消") { dlg.dismiss() }.apply { setPadding(0, act.dp(10), 0, 0) })

        dlg = show(act, root, 640)
        dlg.setOnKeyListener { _, _, ev ->
            if (ev.keyCode == KeyEvent.KEYCODE_BACK || ev.keyCode == KeyEvent.KEYCODE_HOME) {
                false
            } else {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) paint(ev.keyCode)
                // 学习态下由 KeyMap 吞掉并广播
                KeyMap.onKey(act, ev)
            }
        }
        dlg.setOnDismissListener {
            if (registered) runCatching { act.unregisterReceiver(recv) }
        }
    }

    // ------------------------------------------------------------------ 开机任务

    fun bootTask(act: Activity, onChanged: () -> Unit) {
        val root = shell(
            act, "开机任务",
            "开机后按设定的延迟逐条执行，最常用的是自动打开导航 / 音乐。" +
                "延迟范围 ${BootTask.MIN_DELAY} ~ ${BootTask.MAX_DELAY} 秒，点已有任务可以改延迟时间。" +
                "桌面本身就是开机自启的，所以这里不需要额外的自启动权限。"
        )
        val box = scrollBox(act, 300)
        root.addView(box)

        lateinit var dlg: AlertDialog

        fun rebuild() {
            val inner = innerOf(box) ?: return
            inner.removeAllViews()
            val list = BootTask.tasks(act)
            if (list.isEmpty()) {
                inner.addView(text(act, "还没有任务", 17f, DIM).apply {
                    setPadding(act.dp(4), act.dp(10), 0, act.dp(6))
                })
            } else {
                for ((i, t) in list.withIndex()) {
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(act.dp(14), act.dp(10), act.dp(10), act.dp(10))
                        background = round(ROW_BG, act.dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = act.dp(8) }
                    }
                    val title = text(act, BootTask.describe(act, t), 17f, WHITE).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(0, act.dp(2), 0, act.dp(2))
                        // 点任务本体 = 改延迟；不点「删除」就不会误删
                        setOnClickListener {
                            pickDelay(act, t.delaySec) { sec ->
                                val now = ArrayList(BootTask.tasks(act))
                                if (i in now.indices) {
                                    now[i] = now[i].copy(delaySec = BootTask.clampDelay(sec))
                                    BootTask.save(act, now)
                                }
                                rebuild()
                                onChanged()
                            }
                        }
                    }
                    row.addView(title)
                    row.addView(text(act, "删除", 16f, 0xFFFF8A80.toInt(), true).apply {
                        setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
                        setOnClickListener {
                            BootTask.removeAt(act, i)
                            rebuild()
                            onChanged()
                        }
                    })
                    inner.addView(row)
                }
            }
            val last = BootTask.lastResult(act)
            if (last.isNotEmpty()) {
                inner.addView(text(act, "上次：" + last, 14f, DIM).apply {
                    setPadding(act.dp(4), act.dp(12), 0, 0)
                })
            }
        }
        rebuild()

        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        actions.addView(button(act, "添加任务", true) {
            pickApp(act) { value ->
                pickDelay(act, 5) { sec ->
                    BootTask.add(act, BootTask.Task(sec, AutoActions.APP + value))
                    rebuild()
                    onChanged()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(act, "立即试跑") {
            BootTask.runNow(act)
            toast(act, "已开始执行，完成后回来看「上次」")
            act.window?.decorView?.postDelayed({ rebuild() }, 1500)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(actions)
        root.addView(button(act, "关闭") { dlg.dismiss() }.apply { setPadding(0, act.dp(12), 0, 0) })

        dlg = show(act, root, 720)
        dlg.setOnDismissListener { onChanged() }
    }

    // ------------------------------------------------------------------ 选择器

    /** 动作选择：内置动作 + 「打开应用…」。 */
    private fun pickAction(act: Activity, current: String?, onPicked: (String) -> Unit) {
        val root = shell(act, "选择动作", "选了就立刻生效，可以之后再回来改。")
        val box = scrollBox(act, 380)
        val inner = innerOf(box)!!
        var dlg: AlertDialog? = null
        for (a in AutoActions.LIST) {
            val on = a.first == current
            inner.addView(item(act, a.second, if (on) "当前" else null) {
                dlg?.dismiss()
                onPicked(a.first)
            })
        }
        inner.addView(item(act, "打开某个应用…", "选完之后每次都打开这个应用") {
            dlg?.dismiss()
            pickApp(act) { v -> onPicked(AutoActions.APP + v) }
        })
        root.addView(box)
        root.addView(button(act, "取消") { dlg?.dismiss() }.apply { setPadding(0, act.dp(12), 0, 0) })
        dlg = show(act, root, 640)
    }

    /** 应用选择：直接读 [DockSlots.listApps]，自绘滚动列表（图标 + 名称）。 */
    private fun pickApp(act: Activity, onPicked: (String) -> Unit) {
        val root = shell(act, "选择应用", "只列有启动入口的应用。")
        val box = scrollBox(act, 420)
        val inner = innerOf(box)!!
        var dlg: AlertDialog? = null
        val apps = runCatching { DockSlots.listApps(act) }.getOrDefault(emptyList())
        if (apps.isEmpty()) {
            inner.addView(text(act, "没读到已安装应用", 17f, DIM).apply {
                setPadding(act.dp(4), act.dp(10), 0, act.dp(6))
            })
        }
        for (e in apps) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(14), act.dp(9), act.dp(14), act.dp(9))
                background = round(ROW_BG, act.dp(10))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = act.dp(6) }
                setOnClickListener {
                    dlg?.dismiss()
                    onPicked(DockSlots.key(e))
                }
            }
            val icon = e.icon
            if (icon != null) {
                row.addView(ImageView(act).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(act.dp(36), act.dp(36))
                })
            }
            row.addView(text(act, e.label, 18f, WHITE).apply {
                setPadding(act.dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            inner.addView(row)
        }
        root.addView(box)
        root.addView(button(act, "取消") { dlg?.dismiss() }.apply { setPadding(0, act.dp(12), 0, 0) })
        dlg = show(act, root, 640)
    }

    /**
     * 延迟选择：开机后等多久再执行，**范围 0 ~ 3600 秒**。
     *
     * 为什么不是一排固定档：用户要的跨度太大（立刻 ~ 1 小时），
     * 排成按钮会横跨好几行还选不准。这里做成「数字输入 + 快捷档」：
     * 常用值一键填进输入框，特殊值直接敲数字，最后统一按「确定」——
     * 避免误触一下就生效（车上单手点错的成本很高）。
     */
    private fun pickDelay(act: Activity, current: Int, onPicked: (Int) -> Unit) {
        val root = shell(
            act, "等多久执行",
            "范围 ${BootTask.MIN_DELAY} ~ ${BootTask.MAX_DELAY} 秒。0 = 开机广播一到就跑；" +
                "导航 / 音乐这类建议 30 秒以上，等系统服务起齐再拉起。"
        )

        // 输入行：数字框 + 「秒」
        val line = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dp(12), 0, 0)
        }
        val input = EditText(act).apply {
            setText(BootTask.clampDelay(current).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
            background = round(0xFF101A2A.toInt(), act.dp(12), 0x2E4FD1FF, act.dp(1))
            isSingleLine = true
            hint = "0 - 3600"
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        line.addView(input)
        line.addView(text(act, "秒", 20f, DIM).apply {
            setPadding(act.dp(12), 0, act.dp(4), 0)
        })
        root.addView(line)

        // 快捷档：点一下填进输入框（不直接生效）
        val quick = listOf(0, 5, 10, 30, 60, 300, 1800, 3600)
        for (chunk in quick.chunked(4)) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, act.dp(10), 0, 0)
            }
            for (sec in chunk) {
                row.addView(text(act, BootTask.secText(sec), 16f, WHITE, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, act.dp(11), 0, act.dp(11))
                    background = round(ROW_BG, act.dp(10), 0x2EFFFFFF, act.dp(1))
                    isClickable = true
                    setOnClickListener {
                        input.setText(sec.toString())
                        input.setSelection(input.text.length)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = act.dp(5); rightMargin = act.dp(5) }
                })
            }
            root.addView(row)
        }

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        bar.addView(button(act, "取消") { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(act, "确定", true) {
            val raw = input.text?.toString()?.trim().orEmpty()
            val v = raw.toIntOrNull()
            if (v == null) {
                toast(act, "请输入 0 ~ 3600 之间的数字")
                return@button
            }
            val sec = BootTask.clampDelay(v)
            if (sec != v) toast(act, "超出范围，已按 ${BootTask.secText(sec)} 处理")
            dlg.dismiss()
            onPicked(sec)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(bar)

        dlg = show(act, root, 640)
    }

    private fun toast(act: Activity, s: String) {
        runCatching { Toast.makeText(act, s, Toast.LENGTH_SHORT).show() }
    }

    /** 供设置页显示「几条绑定 / 几条任务」的短摘要。 */
    fun summary(ctx: Context): String {
        val k = KeyMap.binds(ctx).size
        val b = BootTask.tasks(ctx).size
        return "按键 $k 条 · 开机任务 $b 条"
    }

    /** 未使用但保留：FrameLayout 版的居中容器（面板宽高自适应时备用）。 */
    private fun centered(act: Activity, v: View): FrameLayout =
        FrameLayout(act).apply { addView(v, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
}
