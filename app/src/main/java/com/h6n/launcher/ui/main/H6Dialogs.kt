package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.AudioRoute
import com.h6n.launcher.core.SceneAnnouncer
import com.h6n.launcher.core.SceneStore
import com.h6n.launcher.core.SimSignals
import com.h6n.launcher.core.Storage
import com.h6n.launcher.core.TireAlert
import com.h6n.launcher.core.TireRules
import com.h6n.launcher.core.UpdateManager
import com.h6n.launcher.core.VoicePlayer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 桌面 / 设置页用到的自绘弹窗集合 —— 全部移植自未重写版的 Java 面板：
 *
 * - [StorageAlert]  `StorageGuard.java` 剩余空间 < 1.5G 的红色强警报 + 清理入口
 * - [CleanDialog]   `CleanPanel.java`   存储清理（分类勾选 / 立即清理 / 自动清理）
 * - [UpdateDialog]  `UpdatePanel.java`  在线检查更新 / 更新源 / 下载 / 安装
 *
 * 一律用自绘 LinearLayout，不用 AlertDialog.setAdapter：车机上系统 Adapter
 * 会渲染成空列表；列表高度给固定 px，`height=0 + weight` 在 wrap_content 对话框里会被量成 0。
 */
private val GOLD = 0xFFC9A227L.toInt()
private val RED = 0xFFFF453AL.toInt()
private val GREEN = 0xFF3ECF6EL.toInt()
private val WHITE = 0xFFFFFFFFL.toInt()
private val DIM = 0xFF9AA0A6L.toInt()
private val CARD = 0xF21C1A17L.toInt()
private val INK = 0xFF201A10L.toInt()
private val LINE = 0x1FFFFFFF

private val MAIN = Handler(Looper.getMainLooper())

private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

private fun round(fill: Int, radius: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, sw), stroke)
    }

private fun tv(c: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(c).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

private fun btn(c: Context, s: String, fill: Int, fg: Int, onClick: () -> Unit): TextView =
    tv(c, s, 15f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10))
        background = round(fill, dp(c, 10))
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun newDialog(act: Activity, root: View, widthDp: Int): Dialog =
    Dialog(act).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(root)
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = w.attributes
            lp.dimAmount = 0.65f
            w.attributes = lp
        }
        setOnShowListener { window?.setLayout(dp(act, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT) }
    }

private const val MATCH = -1
private const val WRAP = -2

// ---------------------------------------------------------------------------
// ③ 存储红色强警报（StorageGuard）
// ---------------------------------------------------------------------------

object StorageAlert {

    private const val TIP_GAP_MS = 30 * 60 * 1000L
    private const val CHECK_GAP_MS = 60 * 1000L
    @Volatile private var lastCheckMs = 0L

    /**
     * 回桌面 / 冷启动时调：低于 1.5G 先尝试自动清理，再弹强警报。
     * 扫盘放后台线程，30 分钟内不重复弹；[force] 用于手动点击时强制弹。
     */
    fun check(act: Activity, force: Boolean = false, onDone: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_GAP_MS) return
        lastCheckMs = now
        Thread {
            val freed = if (Storage.isLow() && Storage.autoEnabled()) {
                runCatching { Storage.autoClean() }.getOrDefault(0L)
            } else 0L
            val low = Storage.isLow()
            val avail = Storage.availBytes()
            MAIN.post {
                if (act.isFinishing) return@post
                if (!low) { onDone(); return@post }
                if (!force && now - Storage.lastTipMs() < TIP_GAP_MS) { onDone(); return@post }
                Storage.markTipped()
                show(act, freed, avail, onDone)
            }
        }.start()
    }

    /** 直接弹出（机油卡右侧 / 设置页手动点）。 */
    @Synchronized
    fun show(act: Activity, freed: Long = 0L, avail: Long = Storage.availBytes(), onDone: () -> Unit = {}) {
        if (act.isFinishing) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 26), dp(act, 20), dp(act, 26), dp(act, 20))
                background = round(0xFF2A1113.toInt(), dp(act, 20), RED, dp(act, 3))
            }
            root.addView(tv(act, "⚠️ 强烈警报：存储空间不足", 23f, RED, true).apply {
                gravity = Gravity.CENTER
                background = round(0x33FF453A.toInt(), dp(act, 12), RED, dp(act, 2))
                setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(tv(act, "剩余 ${Storage.gb(avail)} / 共 ${Storage.gb(Storage.totalBytes())}", 26f, WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(act, 16), 0, dp(act, 6))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(tv(act, "低于 1.5G 安全线，请立即清理空间，否则将不能开机！", 17f, RED, true).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(act, 3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            if (freed > 0) {
                root.addView(tv(act, "已自动清理 ${Storage.fmt(freed)}，仍需手动清理", 14f, GREEN).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(act, 10), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            root.addView(tv(act, "清理范围：本机所有路径（内置存储 / U 盘 / 外置卡 / 系统可写区），可逐路径勾选删除；系统关键分区只读保护。", 13f, DIM).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(act, 12), 0, 0)
                setLineSpacing(dp(act, 2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(act, 18), 0, 0)
            }
            val d = arrayOfNulls<Dialog>(1)
            buttons.addView(btn(act, "立即清理", RED, WHITE) {
                d[0]?.dismiss()
                FullCleanDialog.show(act, onDone)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            buttons.addView(btn(act, "稍后处理", 0x33FFFFFF, DIM) {
                d[0]?.dismiss()
                onDone()
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(act, 12) })
            root.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))

            val dlg = newDialog(act, root, 520)
            d[0] = dlg
            dlg.setOnDismissListener { onDone() }
            dlg.show()
        } catch (t: Throwable) {
            onDone()
        }
    }
}

// ---------------------------------------------------------------------------
// ④ 存储清理面板（CleanPanel）
// ---------------------------------------------------------------------------

object CleanDialog {

    fun show(act: Activity, onDone: () -> Unit = {}) {
        if (act.isFinishing) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 26), dp(act, 20), dp(act, 26), dp(act, 18))
                background = round(CARD, dp(act, 20), 0x59C9A227, dp(act, 1))
            }
            val d = arrayOfNulls<Dialog>(1)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv(act, "存储清理", 25f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(tv(act, "✕", 22f, DIM).apply {
                setPadding(dp(act, 14), dp(act, 2), dp(act, 4), dp(act, 8))
                setOnClickListener { d[0]?.dismiss() }
            })
            root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            val availTv = tv(act, "正在扫描…", 16f, DIM)
            root.addView(availTv, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(act, 8); bottomMargin = dp(act, 8)
            })

            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val sv = ScrollView(act).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) }
            // 固定高度：wrap_content 对话框里 weight 会被量成 0（DockPicker 踩过）
            root.addView(sv, LinearLayout.LayoutParams(MATCH, dp(act, 230)))

            val autoTv = tv(act, "", 13f, DIM).apply {
                setPadding(0, dp(act, 10), 0, dp(act, 6))
            }
            fun paintAuto() {
                autoTv.text = if (Storage.autoEnabled()) "自动清理：开（低于 1.5G 时后台清一次）· 点此关闭"
                else "自动清理：关 · 点此开启"
            }
            autoTv.setOnClickListener {
                Storage.setAuto(!Storage.autoEnabled())
                paintAuto()
            }
            paintAuto()
            root.addView(autoTv, LinearLayout.LayoutParams(MATCH, WRAP))

            val goBtn = btn(act, "立即清理", GOLD, INK) {}
            root.addView(goBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 8) })

            val dlg = newDialog(act, root, 560)
            d[0] = dlg
            dlg.setOnDismissListener { onDone() }
            dlg.show()

            var items: List<Storage.Item> = emptyList()
            fun paintAvail() {
                val a = Storage.availBytes()
                availTv.text = "剩余 ${Storage.gb(a)} / 共 ${Storage.gb(Storage.totalBytes())}" +
                    if (Storage.isLow()) " · ⚠️ 低于 1.5G，必须清理" else " · 空间充足"
                availTv.setTextColor(if (Storage.isLow()) RED else DIM)
            }
            paintAvail()

            Thread {
                items = runCatching { Storage.scan(true) }.getOrDefault(emptyList())
                MAIN.post {
                    if (act.isFinishing) return@post
                    list.removeAllViews()
                    if (items.isEmpty()) {
                        list.addView(tv(act, "没有扫到可清理的内容", 15f, DIM),
                            LinearLayout.LayoutParams(MATCH, WRAP))
                    }
                    for (item in items) {
                        val r = LinearLayout(act).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(act, 8), 0, dp(act, 8))
                            background = round(0x0FFFFFFF, dp(act, 10))
                        }
                        val dot = tv(act, if (item.checked) "☑" else "☐", 19f,
                            if (item.checked) GREEN else DIM)
                        r.addView(dot)
                        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(tv(act, item.title + "   " + item.sizeText, 15f, WHITE, true))
                        col.addView(tv(act, item.desc + if (item.truncated) "（扫描已截断）" else "", 12f, DIM))
                        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            leftMargin = dp(act, 10)
                        })
                        r.setOnClickListener { _ ->
                            item.checked = !item.checked
                            dot.text = if (item.checked) "☑" else "☐"
                            dot.setTextColor(if (item.checked) GREEN else DIM)
                        }
                        list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
                    }
                    availTv.text = "可清理 ${Storage.summary(items)} · " +
                        "剩余 ${Storage.gb(Storage.availBytes())}" +
                        if (Storage.isLow()) " · ⚠️ 低于 1.5G" else ""
                    availTv.setTextColor(if (Storage.isLow()) RED else DIM)
                }
            }.start()

            goBtn.setOnClickListener {
                if (items.isEmpty()) return@setOnClickListener
                val picked = items.filter { it.checked }
                if (picked.isEmpty()) {
                    Toast.makeText(act, "先勾选要清理的项", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                goBtn.text = "清理中…"
                Thread {
                    val freed = runCatching { Storage.clean(picked) }.getOrDefault(0L)
                    MAIN.post {
                        if (act.isFinishing) return@post
                        goBtn.text = "立即清理"
                        paintAvail()
                        availTv.text = "已释放 ${Storage.fmt(freed)} · 剩余 ${Storage.gb(Storage.availBytes())}" +
                            if (Storage.isLow()) " · 仍低于 1.5G" else " · 已回到安全线"
                        availTv.setTextColor(if (Storage.isLow()) RED else GREEN)
                        Toast.makeText(act, "清理完成，释放 ${Storage.fmt(freed)}", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        } catch (t: Throwable) {
            onDone()
        }
    }
}

// ---------------------------------------------------------------------------
// ⑤ 在线检查更新面板（UpdatePanel）
// ---------------------------------------------------------------------------

object UpdateDialog {

    fun show(act: Activity) {
        if (act.isFinishing) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 26), dp(act, 20), dp(act, 26), dp(act, 18))
                background = round(CARD, dp(act, 20), 0x59C9A227, dp(act, 1))
            }
            val d = arrayOfNulls<Dialog>(1)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv(act, "检查更新", 24f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(tv(act, "✕", 22f, DIM).apply {
                setPadding(dp(act, 14), dp(act, 2), dp(act, 4), dp(act, 8))
                setOnClickListener { d[0]?.dismiss() }
            })
            root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(tv(act, "当前版本 ${UpdateManager.curName()}（VC${UpdateManager.curCode()}）", 13f, DIM).apply {
                setPadding(0, dp(act, 4), 0, dp(act, 10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            val state = tv(act, "点「检查」看看有没有新版本", 14f, DIM).apply {
                setLineSpacing(dp(act, 2).toFloat(), 1f)
            }
            val checkBtn = btn(act, "检查", GOLD, INK) {}
            root.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(state, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(checkBtn)
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            val autoTv = tv(act, "", 12f, DIM).apply {
                setPadding(0, dp(act, 8), 0, dp(act, 4))
            }
            fun paint() {
                autoTv.text = if (UpdateManager.auto()) "自动检查：开（桌面启动 5 秒后检查，有新版本弹窗提醒）· 点此关闭"
                else "自动检查：关 · 点此开启"
            }
            autoTv.setOnClickListener {
                UpdateManager.setAuto(!UpdateManager.auto())
                paint()
            }
            paint()
            root.addView(autoTv, LinearLayout.LayoutParams(MATCH, WRAP))

            root.addView(tv(act, "更新源（version.json 完整地址）", 13f, GOLD).apply {
                setPadding(0, dp(act, 8), 0, dp(act, 4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val urlEdit = EditText(act).apply {
                setText(UpdateManager.baseUrl())
                setTextColor(WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setPadding(dp(act, 10), dp(act, 6), dp(act, 10), dp(act, 6))
                background = round(0x22FFFFFF, dp(act, 8), 0x40C9A227, dp(act, 1))
            }
            root.addView(urlEdit, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(btn(act, "保存更新源", 0x33C9A227, GOLD) {
                UpdateManager.setBaseUrl(urlEdit.text.toString())
                Toast.makeText(act, "更新源已保存", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 6) })

            val notesBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val notesSv = ScrollView(act).apply { addView(notesBox, ViewGroup.LayoutParams(MATCH, WRAP)) }
            root.addView(notesSv, LinearLayout.LayoutParams(MATCH, dp(act, 130)))

            val goBtn = btn(act, "下载并安装", RED, WHITE) {}
            root.addView(goBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 10) })

            val dlg = newDialog(act, root, 580)
            d[0] = dlg
            dlg.show()

            var found: UpdateManager.Ver? = null
            fun notes(v: UpdateManager.Ver?) {
                notesBox.removeAllViews()
                if (v == null) return
                for (n in v.notes) {
                    notesBox.addView(tv(act, "· $n", 12.5f, DIM).apply {
                        setLineSpacing(dp(act, 2).toFloat(), 1f)
                    }, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
            val cb = UpdateManager.Cb { st, text, v ->
                if (!act.isFinishing) {
                    state.text = text
                    state.setTextColor(
                        when (st) {
                            UpdateManager.FAILED -> RED
                            UpdateManager.FOUND, UpdateManager.READY -> GREEN
                            else -> DIM
                        }
                    )
                    if (v != null) { found = v; notes(v) }
                    if (st == UpdateManager.READY) goBtn.text = "安装更新"
                }
            }
            checkBtn.setOnClickListener {
                found = null
                goBtn.text = "下载并安装"
                UpdateManager.check(act, false, cb)
            }
            goBtn.setOnClickListener {
                val v = found
                if (v == null) {
                    UpdateManager.check(act, false, cb)
                    return@setOnClickListener
                }
                if (UpdateManager.hasCachedApk(v)) {
                    val r = UpdateManager.install(act, v.code)
                    state.text = r
                } else {
                    UpdateManager.download(act, v, cb)
                }
            }
            val pending = UpdateManager.pendingCode()
            if (pending > UpdateManager.curCode()) {
                state.text = "上次检查到新版本 v${UpdateManager.pendingName()}（$pending）"
                state.setTextColor(GREEN)
                notesBox.addView(tv(act, "· " + UpdateManager.pendingNote(), 12.5f, DIM),
                    LinearLayout.LayoutParams(MATCH, WRAP))
            }
        } catch (t: Throwable) {
            Toast.makeText(act, "打开更新面板失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- 自动检查发现新版本时的主动弹窗 ----------------

    private var promptDlg: Dialog? = null

    /** 桌面退到后台 / 销毁时收掉：绝不让更新弹窗盖在别的应用上面。 */
    fun dismissPrompt() {
        runCatching { promptDlg?.dismiss() }
        promptDlg = null
    }

    /** 更新提醒弹窗是否正显示（桌面主动提醒要错开，别叠两层）。 */
    fun promptVisible(): Boolean = promptDlg?.isShowing == true

    /**
     * 「发现新版本」主动弹窗（移植自未重写版 `UpdatePrompt`）。
     *
     * 与 [show] 的分工：`show()` 是设置页里的完整面板（带更新源输入框、检查按钮），
     * 这里是自动检查撞到新版本时弹的最小决策框 —— 版本对比 + 前 4 条更新说明 +
     * 「立即更新 / 稍后」，点「立即更新」下载完**自动接着装**，结果写回提示行，
     * 不再用一闪而过的 toast。
     *
     * 只在桌面处于前台且拿到焦点时调用（`MainActivity.promptUpdate` 里判断），
     * 同一版本一次启动只主动弹一次，避免用户点了「稍后」又被反复骚扰。
     */
    fun prompt(act: Activity, v: UpdateManager.Ver) {
        if (act.isFinishing) return
        if (promptDlg?.isShowing == true) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 26), dp(act, 20), dp(act, 26), dp(act, 18))
                background = round(CARD, dp(act, 20), 0x59C9A227, dp(act, 1))
            }

            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv(act, "发现新版本", 24f, GOLD, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(tv(act, "✕", 22f, DIM).apply {
                setPadding(dp(act, 14), dp(act, 2), dp(act, 4), dp(act, 8))
                setOnClickListener { dismissPrompt() }
            })
            root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            root.addView(
                tv(act, "v${UpdateManager.curName()}  →  v${v.name}", 18f, WHITE, true).apply {
                    setPadding(0, dp(act, 10), 0, dp(act, 4))
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )

            val hint = tv(act, "点「立即更新」自动下载并安装，装完桌面会重启一次", 12.5f, DIM).apply {
                setLineSpacing(dp(act, 2).toFloat(), 1f)
            }
            root.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

            for (n in v.notes.take(4)) {
                if (n.isBlank()) continue
                root.addView(tv(act, "· $n", 12f, 0xFFD0D4DA.toInt()).apply {
                    setLineSpacing(dp(act, 2).toFloat(), 1f)
                    setPadding(0, dp(act, 5), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            val foot = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(act, 16), 0, 0)
            }
            val go = btn(act, "立即更新", GREEN, INK) {}
            foot.addView(go)
            foot.addView(btn(act, "稍后", 0x00000000, DIM) { dismissPrompt() }.apply {
                setPadding(dp(act, 20), dp(act, 11), dp(act, 12), dp(act, 11))
            })
            root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

            root.addView(
                tv(act, "状态栏会一直留着更新提示，也可以到 设置 → 关于 → 检查更新", 11f, DIM).apply {
                    setPadding(0, dp(act, 10), 0, 0)
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )

            val cb = UpdateManager.Cb { st, txt, _ ->
                when (st) {
                    UpdateManager.DOWNLOADING -> go.text = txt
                    UpdateManager.READY -> {
                        val r = UpdateManager.install(act, v.code)
                        hint.setTextColor(
                            when {
                                r == UpdateManager.NEED_PERM -> GOLD
                                r.startsWith("已交给") -> GREEN
                                else -> RED
                            }
                        )
                        hint.text = if (r == UpdateManager.NEED_PERM) {
                            "已经打开系统设置：允许「安装未知应用」后回来会自动接着装，不用再点一次"
                        } else r
                        // 弹窗随后会被 onPause 收掉（拉起了安装界面），toast 再提醒一遍
                        if (r == UpdateManager.NEED_PERM || !r.startsWith("已交给")) {
                            Toast.makeText(act, r, Toast.LENGTH_LONG).show()
                        }
                        go.isEnabled = true
                        go.text = "重新安装"
                    }
                    UpdateManager.FAILED -> {
                        go.isEnabled = true
                        go.text = "重试下载"
                        hint.setTextColor(RED)
                        hint.text = txt
                    }
                }
            }
            go.setOnClickListener {
                go.isEnabled = false
                go.text = "准备下载…"
                // download() 里已经处理过「包已下好」：命中缓存会直接回 READY 走安装
                UpdateManager.download(act, v, cb)
            }

            val dlg = newDialog(act, root, 560).apply {
                setOnDismissListener { promptDlg = null }
                show()
            }
            promptDlg = dlg
        } catch (t: Throwable) {
            Toast.makeText(act, "更新提示打开失败", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------------------
// ⑥ 场景播报控制面板（重写版新增，设置页用）
// ---------------------------------------------------------------------------

object VoicePanel {

    fun show(act: Activity) {
        if (act.isFinishing) return
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 26), dp(act, 20), dp(act, 26), dp(act, 18))
                background = round(CARD, dp(act, 20), 0x59C9A227, dp(act, 1))
            }
            val d = arrayOfNulls<Dialog>(1)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(tv(act, "场景播报提醒", 24f, WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(tv(act, "✕", 22f, DIM).apply {
                setPadding(dp(act, 14), dp(act, 2), dp(act, 4), dp(act, 8))
                setOnClickListener { d[0]?.dismiss() }
            })
            root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            val state = tv(act, SceneAnnouncer.lastLog(), 14f, GREEN).apply {
                setPadding(0, dp(act, 10), 0, dp(act, 10))
            }
            root.addView(state, LinearLayout.LayoutParams(MATCH, WRAP))

            val paintList = ArrayList<() -> Unit>()
            fun addSwitch(title: String, read: () -> Boolean, write: (Boolean) -> Unit) {
                val v = tv(act, "", 15f, DIM).apply {
                    setPadding(0, dp(act, 7), 0, dp(act, 7))
                }
                val paint = {
                    v.text = "$title：${if (read()) "开" else "关"} · 点此切换"
                    v.setTextColor(if (read()) GREEN else DIM)
                }
                v.setOnClickListener { write(!read()); paint(); paintList.forEach { it() } }
                paintList += paint
                paint()
                root.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            addSwitch("播报总开关", { SceneStore.master() }) {
                SceneAnnouncer.setEnabled(it)
            }
            addSwitch("车门开关播报", { SceneStore.doorsOn() }) { SceneStore.setDoorsOn(it) }
            addSwitch("换挡档位播报", { SceneStore.gearOn() }) { SceneStore.setGearOn(it) }
            addSwitch("熄火未关车窗提醒", { SceneStore.windowOn() }) { SceneStore.setWindowOn(it) }

            // 音色包只剩「经典」一套（6.0.70 删掉女生/男生两套 38 个 wav）。
            // 不再画按钮：一个切不到别处的按钮既要占掉一排高度，又容易被当成坏掉的控件；
            // 车机只有 480dp 高，弹窗每多一行都得从别处挤。
            root.addView(tv(act, "音色包：${SceneStore.packName()} · 唯一音色", 13f, GOLD).apply {
                setPadding(0, dp(act, 10), 0, dp(act, 6))
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            root.addView(tv(act, "播报音频通道 · 提示音量（移植旧版 AudioRoute）", 13f, GOLD).apply {
                setPadding(0, dp(act, 10), 0, dp(act, 4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            val audio = tv(act, "", 14f, WHITE, true).apply {
                setPadding(0, dp(act, 6), 0, dp(act, 6))
                setOnClickListener {
                    pickAudio(act) {
                        text = "播报音频通道：${AudioRoute.name()} · ${AudioRoute.desc()} ›"
                        state.text = SceneAnnouncer.lastLog()
                    }
                }
                text = "播报音频通道：${AudioRoute.name()} · ${AudioRoute.desc()} ›"
            }
            root.addView(audio, LinearLayout.LayoutParams(MATCH, WRAP))

            // 旧版 AudioRoute.row()：0~100 提示音量 SeekBar，默认 25，松手立刻试听
            val volRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pct = tv(act, "${SceneStore.volume()}%", 14f, GOLD, true)
            val bar = SeekBar(act).apply {
                max = 100
                progress = SceneStore.volume()
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        SceneStore.setVolume(p)
                        pct.text = "$p%"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { AudioRoute.playTest(act) }
            })
            volRow.addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f))
            volRow.addView(pct, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(act, 10) })
            root.addView(volRow, LinearLayout.LayoutParams(MATCH, WRAP))
            root.addView(
                tv(act, "提示音量（默认 25%）· 只影响本 App 播报，不改系统音量", 11f, DIM),
                LinearLayout.LayoutParams(MATCH, WRAP)
            )

            val tests = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            tests.addView(btn(act, "试听开门", 0x33FFFFFF, WHITE) {
                SceneAnnouncer.previewDoor(0, true)
                state.text = SceneAnnouncer.lastLog()
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            tests.addView(btn(act, "试听 R 档", 0x33FFFFFF, WHITE) {
                SceneAnnouncer.previewGear("R")
                state.text = SceneAnnouncer.lastLog()
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(act, 6) })
            tests.addView(btn(act, "模拟熄火检查", RED, WHITE) {
                SimSignals.pulsePowerOff()
                state.text = "已模拟一次熄火，1.5 秒后检查车窗"
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(act, 6) })
            root.addView(tests, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 12) })

            root.addView(tv(act, "最近播报文件：" + VoicePlayer.lastPlayed.ifEmpty { "（还没有触发）" }, 12f, DIM).apply {
                setPadding(0, dp(act, 8), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            val dlg = newDialog(act, root, 560)
            d[0] = dlg
            dlg.show()
        } catch (t: Throwable) {
            Toast.makeText(act, "打开播报面板失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 旧版 AudioRoute.pick()：六条音频总线列成自绘列表，选完立刻按新通道试听。 */
    private fun pickAudio(act: Activity, onPicked: () -> Unit) {
        if (act.isFinishing) return
        try {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 22), dp(act, 18), dp(act, 22), dp(act, 14))
                background = round(CARD, dp(act, 20), 0x59C9A227, dp(act, 1))
            }
            val d = arrayOfNulls<Dialog>(1)
            box.addView(tv(act, "播报音频通道", 22f, WHITE, true))
            box.addView(
                tv(act, "车门 / 档位 / 车窗提醒从这条总线出声；选完点「试听」确认车机哪条真的响", 12f, DIM).apply {
                    setPadding(0, dp(act, 6), 0, dp(act, 8))
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val cur = AudioRoute.index()
            for (i in AudioRoute.NAMES.indices) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 8))
                    background = round(if (i == cur) 0x333ECF6E else 0x0FFFFFFF, dp(act, 10))
                    setOnClickListener {
                        AudioRoute.set(i)
                        onPicked()
                        AudioRoute.playTest(act)
                        d[0]?.dismiss()
                    }
                }
                r.addView(
                    tv(act, (if (i == cur) "✓ " else "   ") + AudioRoute.NAMES[i], 15f,
                        if (i == cur) GREEN else WHITE, true)
                )
                r.addView(tv(act, AudioRoute.DESC[i], 11f, DIM))
                list.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(act, 4) })
            }
            val sv = ScrollView(act).apply { addView(list) }
            box.addView(sv, LinearLayout.LayoutParams(MATCH, dp(act, 230)))
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(
                btn(act, "试听当前通道", 0x33FFFFFF, WHITE) { AudioRoute.playTest(act) },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            btns.addView(
                btn(act, "完成", GOLD, INK) { d[0]?.dismiss() },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(act, 8) }
            )
            box.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 10) })
            val dlg = newDialog(act, box, 520)
            d[0] = dlg
            dlg.show()
        } catch (t: Throwable) {
            Toast.makeText(act, "打开通道面板失败", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------------------
// ⑧ 胎压告警（TireAlarm）
// ---------------------------------------------------------------------------

/**
 * 胎压异常自动弹窗（6.0.65）。
 *
 * ## 触发方
 * `TireAlert` 判定出来以后通过 `MainActivity` 注册的回调调这里，本对象不监听信号，
 * 也不做判定 —— 阈值逻辑只有 [TireRules] + `TireAlert` 一份，避免"弹窗红了但车模没红"。
 *
 * ## 10 秒自动关闭
 * 用户要求「无操作 10 秒后自动取消」。车机停在那儿弹个窗挡住导航很烦，所以必须自己走，
 * 不能等用户点。计时器挂在主线程 Handler 上，[dismiss] 时一并 removeCallbacks，
 * 否则 Activity 关了还会往已销毁的 Dialog 上打 dismiss（偶发 WindowLeaked）。
 *
 * ## 为什么不用 AlertDialog
 * 车机上 `AlertDialog.setItems/setAdapter` 会渲染成空列表（6.0.x 反复踩过），
 * 一律自绘 LinearLayout。四轮数值也不用 GridView，直接四个 weight=1 的格子 ——
 * 这个宽度下 Grid 只会多一层测量，没有收益。
 */
object TireAlarm {

    /** 无操作自动关闭的秒数（用户指定 10 秒）。 */
    private const val AUTO_CLOSE_SEC = 10

    private var dlg: Dialog? = null
    private var ticker: Runnable? = null
    private var remain = 0

    fun show(act: Activity, a: TireAlert.Alarm) {
        if (act.isFinishing) return
        // 已经弹着就换掉：新告警应该带新数据、重新计时，不能让旧倒计时把新内容关掉
        dismiss()
        try {
            val isLow = a.kind == TireAlert.Kind.LOW
            val accent = RED

            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(act, 24), dp(act, 18), dp(act, 24), dp(act, 16))
                background = round(CARD, dp(act, 20), withAlpha(accent, 0x59), dp(act, 1))
            }
            val d = arrayOfNulls<Dialog>(1)

            // ---- 标题行 ----
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val titleBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            titleBox.addView(
                tv(act, if (isLow) "胎压过低" else "胎压差值过大", 26f, accent, true),
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            titleBox.addView(
                tv(
                    act,
                    // 阈值取自 TireRules（设置页可改），不写死 240 —— 否则用户把阈值调到
                    // 230 时，弹窗还写着「低于 240」，等于自己打自己
                    if (isLow) "低于 ${TireRules.lowKpa()} kPa，请尽快补气"
                    else "四轮极差 ${a.diff} kPa，超过 ${TireRules.diffKpa()} kPa",
                    14f, DIM
                ).apply { setPadding(0, dp(act, 4), 0, 0) },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            head.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(tv(act, "✕", 22f, DIM).apply {
                setPadding(dp(act, 14), 0, dp(act, 4), dp(act, 8))
                setOnClickListener { dismiss() }
            })
            root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

            // ---- 四轮数值 ----
            val grid = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(act, 14), 0, 0)
            }
            for (i in 0 until 4) {
                val v = a.kpa.getOrElse(i) { -1 }
                val bad = a.wheels.contains(i)
                val cell = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(act, 8), 0, dp(act, 8))
                    background = round(
                        if (bad) withAlpha(RED, 0x22) else 0x14FFFFFF,
                        dp(act, 12),
                        if (bad) withAlpha(RED, 0x66) else 0x0F000000,
                        dp(act, 1)
                    )
                }
                cell.addView(tv(act, TireAlert.WHEEL_NAMES[i], 13f, if (bad) accent else DIM, bad)
                    .apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP))
                cell.addView(
                    tv(act, if (v > 0) v.toString() else "--", 27f,
                        if (bad) accent else WHITE, true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(MATCH, WRAP)
                )
                val temp = a.temp.getOrElse(i) { -1 }
                cell.addView(
                    tv(act, if (v > 0) "kPa · ${if (temp > -40) "$temp℃" else "—"}" else "无读数",
                        12f, DIM).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(MATCH, WRAP)
                )
                grid.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i > 0) leftMargin = dp(act, 8)
                })
            }
            root.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))

            // ---- 底部：说明 + 倒计时 + 按钮 ----
            val foot = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(act, 16), 0, 0)
            }
            val count = tv(act, "", 13f, DIM)
            foot.addView(tv(
                act,
                if (isLow) "标准冷态胎压一般 240–280 kPa" else "差值过大常见于单轮慢漏气",
                13f, DIM
            ), LinearLayout.LayoutParams(0, WRAP, 1f))
            foot.addView(count, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                rightMargin = dp(act, 12)
            })
            foot.addView(btn(act, "知道了", accent, WHITE) { dismiss() })
            root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

            val dialog = newDialog(act, root, 480)
            d[0] = dialog
            dlg = dialog
            dialog.setOnDismissListener {
                stopTick()
                if (dlg === dialog) dlg = null
            }
            dialog.show()
            startTick(dialog, count)
        } catch (t: Throwable) {
            dlg = null
        }
    }

    /** 关掉当前弹窗（Activity 销毁 / 用户点「知道了」/ 倒计时到点都会走这里）。 */
    fun dismiss() {
        stopTick()
        val d = dlg
        dlg = null
        runCatching { d?.dismiss() }
    }

    private fun startTick(d: Dialog, label: TextView) {
        remain = AUTO_CLOSE_SEC
        label.text = "${remain}s 后自动关闭"
        val r = object : Runnable {
            override fun run() {
                remain--
                if (remain <= 0) {
                    runCatching { d.dismiss() }
                    return
                }
                label.text = "${remain}s 后自动关闭"
                MAIN.postDelayed(this, 1000L)
            }
        }
        ticker = r
        MAIN.postDelayed(r, 1000L)
    }

    private fun stopTick() {
        ticker?.let { MAIN.removeCallbacks(it) }
        ticker = null
    }

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)
}
