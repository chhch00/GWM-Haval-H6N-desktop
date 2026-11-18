package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AdbGrant
import com.h6n.launcher.core.PermGuide
import com.h6n.launcher.ui.common.dp

/**
 * 「权限激活」面板 + 缺失提示。
 *
 * 结构照车机「应用管家」的 `ActivityPermissions` 列表：
 * 每行一项权限，左边名称 / 说明，右边状态胶囊；点状态去系统授权页。
 * 全部自绘（车机上 `AlertDialog.setItems` 会渲染空列表，见 [DockPicker] 的排查记录）。
 *
 * ## 为什么要有 [onHostResume]
 * 用户在系统设置里授权完按返回，我们的面板还开着，但状态是打开时算的。
 * 宿主 Activity 的 `onResume` 里调一次 [onHostResume]，把面板重画一遍，
 * 这样「授完回来看一眼」能立刻变成绿色的「已激活」。
 */
object PermPanel {

    private val CARD = 0xF21C1A17L.toInt()
    private val WHITE = 0xFFFFFFFFL.toInt()
    private val DIM = 0xFF9AA0A6L.toInt()
    private val GOLD = 0xFFC9A227L.toInt()
    private val OK = 0xFF7BD88FL.toInt()
    private val BAD = 0xFFFF8A80.toInt()
    private val ACC = 0xFF4FD1FFL.toInt()
    private val ROW = 0x14FFFFFF

    /** 当前还开着的面板刷新函数（同一时间只会有一个）。 */
    private var live: (() -> Unit)? = null

    // ------------------------------------------------------------------ 自绘件

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
                if (strong) 0x2EFFC857.toInt() else ROW,
                c.dp(10), if (strong) GOLD else 0x2EFFFFFF, c.dp(1)
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toast(act: Activity, s: String) {
        runCatching { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }
    }

    /**
     * 打开车机「应用管家」：车机上只有它有权限把网络调试口拉起来，
     * 桌面自己开不了（无 root、无系统签名）。打开后由用户在它页面里点开关。
     */
    private fun openManager(act: Activity): Boolean = runCatching {
        val i = act.packageManager.getLaunchIntentForPackage(AdbGrant.MANAGER_PKG)
            ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 权限列表

    /**
     * 打开权限激活页。
     *
     * @param onChanged 状态可能变了（宿主可以据此刷新自己的摘要行）
     */
    fun show(act: Activity, onChanged: () -> Unit = {}) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(28), act.dp(20), act.dp(28), act.dp(18))
            background = round(CARD, act.dp(16), 0x30FFFFFF, act.dp(1))
        }
        root.addView(text(act, "权限激活", 26f, GOLD, true))
        val head = text(act, "", 15f, DIM).apply { setPadding(0, act.dp(8), 0, act.dp(6)) }
        root.addView(head)

        // 无线 ADB：整页唯一能「自己补齐」的一项，放最显眼的位置
        val adbBox = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(12))
            background = round(0x14FFFFFF, act.dp(10), 0x334FD1FF, act.dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = act.dp(6) }
        }
        root.addView(adbBox)

        // 局部函数只能后向引用，用变量把 renderAdb 塞给 rebuild
        var refreshAdb: (() -> Unit)? = null

        val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(act).apply {
            isFillViewport = false
            addView(inner, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(sc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, act.dp(420)
        ))

        lateinit var dlg: AlertDialog

        fun rebuild() {
            head.text = "点一行去系统里授权，授完按返回这里会自动刷新。" +
                "当前：" + PermGuide.summary(act)
            inner.removeAllViews()
            for (p in PermGuide.ALL) {
                val on = PermGuide.granted(act, p.id)
                val adbOnly = p.adb != null
                // 6.0.101：可选且系统不给的（READ_LOGS）不再显示成红色「去激活」，
                // 否则用户会以为权限没给全 —— 它压根不参与「全部已激活」的判定。
                val skippable = p.optional && !on

                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(14), act.dp(10), act.dp(12), act.dp(10))
                    background = round(ROW, act.dp(10), if (p.required && !on) 0x40FF8A80 else 0, act.dp(1))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = act.dp(8) }
                    setOnClickListener {
                        toast(act, PermGuide.open(act, p.id))
                        // 系统页可能瞬间返回（车机没有该页），排一拍再刷
                        inner.postDelayed({ rebuild(); onChanged() }, 600)
                    }
                }

                val col = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(text(
                    act,
                    p.title + if (p.required) "（必需）" else if (p.optional) "（可跳过）" else "",
                    18f, if (p.required && !on) BAD else WHITE, true
                ))
                col.addView(text(act, p.desc, 13f, DIM).apply {
                    setPadding(0, act.dp(4), 0, 0)
                })
                if (adbOnly) {
                    val cmd = PermGuide.adbLine(act, p.id).orEmpty()
                    col.addView(text(act, cmd, 12f, ACC).apply {
                        setPadding(0, act.dp(5), 0, 0)
                        setOnClickListener {
                            val ok = PermGuide.copy(act, cmd)
                            toast(act, if (ok) "命令已复制，粘到电脑终端执行" else cmd)
                        }
                    })
                }
                row.addView(col)

                val pillTxt = when {
                    on -> "已激活"
                    skippable -> "可跳过"
                    adbOnly -> "命令"
                    else -> "去激活"
                }
                val pillColor = when {
                    on -> OK
                    skippable -> DIM
                    adbOnly -> ACC
                    else -> BAD
                }
                val pillEdge = when {
                    on -> 0x667BD88F
                    skippable -> 0x33FFFFFF
                    adbOnly -> 0x664FD1FF
                    else -> 0x66FF8A80
                }
                val pill = text(act, pillTxt, 15f, pillColor, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(act.dp(14), act.dp(7), act.dp(14), act.dp(7))
                    background = round(
                        if (on) 0x227BD88F else 0x22000000,
                        act.dp(14), pillEdge, act.dp(1)
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = act.dp(10) }
                }
                row.addView(pill)
                inner.addView(row)
            }
            refreshAdb?.invoke()
        }
        rebuild()

        // ---- 无线 ADB 卡片：状态 + 一键打通
        fun renderAdb(busy: Boolean = false) {
            adbBox.removeAllViews()
            // ★ 6.0.51：车机是 Android 8.1，根本没有 adb_wifi_enabled 这个键，
            //   旧判定恒为「未开」。改按 TCP 端口实际状态 + 权限是否齐来判。
            val tcp = AdbGrant.wirelessOn(act)
            // 6.0.101：与授权链 [AdbGrant.CORE] 同口径 —— 可选项（READ_LOGS）
            // 没授绝不能把这里判成「没给全」，否则按钮永远停在「一键授权全部」。
            val permsOk = PermGuide.blocking(act).isEmpty()
            val on = tcp && permsOk

            val titleRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(text(act, "无线 ADB", 18f, WHITE, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            titleRow.addView(button(act, if (busy) "授权中…" else if (on) "已全部授权" else "一键授权全部", !on) {
                if (busy) {
                    toast(act, "正在后台授权，稍等")
                } else {
                    renderAdb(true)
                    toast(act, "后台执行：系统属性直写 → root → 本机 adb，无需点任何开关")
                    AdbGrant.ensure(act) { _, msg ->
                        toast(act, msg)
                        renderAdb(false)
                        rebuild()
                        onChanged()
                    }
                }
            })
            adbBox.addView(titleRow)

            adbBox.addView(text(act, AdbGrant.summary(act), 14f, if (on) OK else ACC).apply {
                setPadding(0, act.dp(6), 0, 0)
            })
            // 6.0.106：把「本机 adb 自连」走到哪一步写出来。
            // 实车上连不上有三种完全不同的原因（没端口 / 没授权 / 连上但命令被拒），
            // 以前只能让用户导日志，现在这行直接看得到。
            if (AdbGrant.authNote().isNotEmpty()) {
                adbBox.addView(
                    text(act, "本机自连：" + AdbGrant.authNote(), 12f, DIM)
                        .apply { setPadding(0, act.dp(4), 0, 0) }
                )
            }
            // 6.0.106：桌面以前只有「开」没有「关」—— 连过一次 adb 就再也关不掉，
            // 调试口一直晾在局域网里。这里按 tcp 实际状态给（不是 on：
            // 权限没给全但端口已经开着，也得能关）。
            if (tcp) {
                val offRow = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, act.dp(8), 0, 0)
                }
                offRow.addView(button(act, if (busy) "正在关闭…" else "关闭无线调试") {
                    if (busy) {
                        toast(act, "正在后台关闭，稍等")
                    } else {
                        renderAdb(true)
                        toast(act, "正在关端口并重启 adbd…")
                        AdbGrant.disableWireless(act) { _, msg ->
                            toast(act, msg)
                            renderAdb(false)
                            rebuild()
                            onChanged()
                        }
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                adbBox.addView(offRow)
                adbBox.addView(
                    text(act, "关掉后手机上的 adb 就连不上了；想再连点上面的「一键授权全部」。", 12f, DIM)
                        .apply { setPadding(0, act.dp(4), 0, 0) }
                )
            }
            if (!on) {
                // 6.0.85：桌面先照抄应用管家「临时开启无线adb(公签)」那两条
                // （service.adb.tcp.port + ctl.restart adbd），不成再退厂商整组；
                // 能写通就不用求人，写不通才退回应用管家那一步。
                val tip = if (busy) {
                    "正在写调试属性、重启 adbd 并自连授权……"
                } else {
                    "桌面先照应用管家「临时开启」的做法写 service.adb.tcp.port 并重启 adbd，" +
                        "写不通再用下面的按钮：应用管家 → 临时开启无线adb → 回桌面点「一键授权」。"
                }
                adbBox.addView(text(act, tip, 12f, DIM).apply {
                    setPadding(0, act.dp(6), 0, 0)
                })
                adbBox.addView(text(act, AdbGrant.propSummary(), 12f, if (AdbGrant.propOk()) ACC else DIM).apply {
                    setPadding(0, act.dp(4), 0, 0)
                })
                if (!busy) {
                    val row = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, act.dp(8), 0, 0)
                    }
                    row.addView(button(act, "去应用管家开调试") {
                        if (openManager(act)) {
                            toast(act, "打开「无线调试」后返回桌面，再点这里的「一键授权全部」")
                        } else {
                            toast(act, "这台车机上找不到应用管家，请手动打开它的「无线调试」")
                        }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    adbBox.addView(row)
                }
            }
        }
        refreshAdb = { renderAdb(false) }
        renderAdb(false)

        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(14), 0, 0)
        }
        bar.addView(button(act, "重新检查", true) {
            rebuild()
            onChanged()
            toast(act, PermGuide.summary(act))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(act, "关闭") { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = act.dp(10)
            })
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(760), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        live = { runCatching { rebuild() } }
        dlg.setOnDismissListener {
            live = null
            onChanged()
        }
        dlg.show()
    }

    /** 宿主 `onResume` 调：面板还开着就把状态重算一遍。 */
    fun onHostResume() {
        live?.invoke()
    }

    // ------------------------------------------------------------------ 缺失提示

    /**
     * 关键权限缺失时的提示（一天一次）。
     *
     * 只在桌面（HOME）回归时调：用户刚开机、刚装完，第一眼就该看到「还差什么」，
     * 而不是点了悬浮球没反应才来翻设置。
     *
     * @return 是否真的弹了
     */
    fun promptIfNeeded(act: Activity): Boolean {
        val miss = PermGuide.criticalMissing(act)
        if (miss.isEmpty()) return false
        if (!PermGuide.promptAllowed(act)) return false
        PermGuide.markPrompted(act)

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(30), act.dp(22), act.dp(30), act.dp(18))
            background = round(CARD, act.dp(16), 0x40FFC857, act.dp(1))
        }
        root.addView(text(act, "还差 " + miss.size + " 项权限", 26f, GOLD, true))
        root.addView(text(
            act,
            "悬浮球、侧边手势、按键映射、开机任务都依赖无障碍服务；" +
                "无障碍没开时，悬浮窗权限用来兜底显示。",
            15f, DIM
        ).apply { setPadding(0, act.dp(8), 0, act.dp(4)) })

        for (m in miss) {
            root.addView(text(act, "· " + m.title + "　" + m.desc, 14f, WHITE).apply {
                setPadding(0, act.dp(6), 0, 0)
            })
        }

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(16), 0, 0)
        }
        bar.addView(button(act, "今天先不管") { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(act, "去激活", true) {
            dlg.dismiss()
            show(act)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(700), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        return true
    }

    /**
     * 冷启动静默授权失败：直接弹「打开应用管家给权限」。
     *
     * 与 [promptIfNeeded] 的区别：那个是一天一次、只提示缺什么、还要再点进权限页；
     * 这个是新装 / 更新后真跑完授权链仍缺权限时才出现，主按钮一步拉起应用管家，
     * 给完权限回到桌面后，在权限面板点「一键授权全部」手动补即可。
     */
    fun promptManager(
        act: Activity,
        miss: List<PermGuide.Item>,
        detail: String = ""
    ): Boolean {
        if (act.isFinishing || act.isDestroyed) return false

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(30), act.dp(22), act.dp(30), act.dp(18))
            background = round(CARD, act.dp(16), 0x40FFC857, act.dp(1))
        }
        root.addView(text(act, "权限没给全", 26f, GOLD, true))
        root.addView(text(
            act,
            "新装 / 更新后已经试着静默授权，下面这些还没生效。" +
                "点下面的按钮打开应用管家给它权限，返回桌面后点「一键授权全部」即可。",
            15f, DIM
        ).apply { setPadding(0, act.dp(8), 0, act.dp(4)) })

        for (m in miss.take(6)) {
            root.addView(text(act, "· " + m.title + "　" + m.desc, 14f, WHITE).apply {
                setPadding(0, act.dp(6), 0, 0)
            })
        }
        if (miss.size > 6) {
            root.addView(text(act, "还有 " + (miss.size - 6) + " 项，进应用管家一次就能全给",
                14f, DIM).apply { setPadding(0, act.dp(6), 0, 0) })
        }
        if (detail.isNotBlank()) {
            root.addView(text(act, detail, 12f, DIM).apply {
                setPadding(0, act.dp(8), 0, 0)
            })
        }

        lateinit var dlg: AlertDialog
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dp(16), 0, 0)
        }
        bar.addView(button(act, "稍后再说") { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button(act, "打开应用管家给权限", true) {
            dlg.dismiss()
            if (!openManager(act)) {
                toast(act, "这台车机上找不到应用管家，请手动打开它给权限")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = act.dp(10)
        })
        root.addView(bar)

        dlg = AlertDialog.Builder(act).setView(root).create()
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnShowListener {
            dlg.window?.setLayout(act.dp(720), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        return true
    }

    /** 设置页摘要行用的一行字。 */
    fun barText(ctx: Context): String = PermGuide.summary(ctx)
}
