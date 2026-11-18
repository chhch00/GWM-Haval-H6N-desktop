package com.h6n.launcher.ui.main

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.Storage
import kotlin.math.roundToInt

/**
 * 存储清理面板 VC377：**本机全部路径**。
 *
 * 两种模式：
 *  - 全盘路径：枚举所有可访问分区，逐级进入任意目录，递归显示真实占用；
 *    任意文件 / 目录都能勾选（可跨目录累积），删除前二次确认。
 *  - 垃圾一键清：参考车机「应用管家」的规则与三级分组（放心清理 / 推荐清理 / 慎用），
 *    九类垃圾按等级分组勾选后一次清掉；慎用类默认不勾。
 *
 * 系统关键分区、挂载点根、桌面自身配置目录只读保护，见 [Storage.locked]。
 */
private val FC_GOLD = 0xFFC9A227L.toInt()
private val FC_RED = 0xFFFF453AL.toInt()
private val FC_GREEN = 0xFF3ECF6EL.toInt()
private val FC_WHITE = 0xFFFFFFFFL.toInt()
private val FC_DIM = 0xFF9AA0A6L.toInt()
private val FC_CARD = 0xF21C1A17L.toInt()
private val FC_INK = 0xFF201A10L.toInt()

private val FC_MAIN = Handler(Looper.getMainLooper())
private const val FC_MATCH = -1
private const val FC_WRAP = -2

private fun fcDp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

private fun fcRound(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radius.toFloat()
    setColor(fill)
}

private fun fcTv(c: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(c).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

private fun fcBtn(c: Context, s: String, fill: Int, fg: Int, onClick: () -> Unit): TextView =
    fcTv(c, s, 15f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(fcDp(c, 18), fcDp(c, 9), fcDp(c, 18), fcDp(c, 9))
        background = fcRound(fill, fcDp(c, 10))
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun fcLp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)
private fun fcLpw(weight: Float): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, FC_WRAP, weight)

private fun fcNewDialog(act: Activity, root: View, widthDp: Int): Dialog = Dialog(act).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(root)
    window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val lp = w.attributes
        lp.dimAmount = 0.65f
        w.attributes = lp
    }
    setOnShowListener { window?.setLayout(fcDp(act, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT) }
}

object FullCleanDialog {

    private const val MAX_ROWS = 80

    fun show(act: Activity, onDone: () -> Unit = {}) {
        if (act.isFinishing) return
        ensurePermission(act)
        try {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(fcDp(act, 26), fcDp(act, 18), fcDp(act, 26), fcDp(act, 16))
                background = fcRound(FC_CARD, fcDp(act, 20))
            }
            val box = arrayOfNulls<Dialog>(1)

            // 标题行
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(fcTv(act, "存储清理 · 本机全部路径", 23f, FC_WHITE, true), fcLpw(1f))
            if (Build.VERSION.SDK_INT >= 30) {
                head.addView(fcTv(act, "全文件权限", 12f, FC_GOLD).apply {
                    setPadding(fcDp(act, 10), 0, fcDp(act, 10), 0)
                    setOnClickListener { openAllFiles(act) }
                })
            }
            head.addView(fcTv(act, "✕", 22f, FC_DIM).apply {
                setPadding(fcDp(act, 14), fcDp(act, 2), fcDp(act, 4), fcDp(act, 8))
                setOnClickListener { box[0]?.dismiss() }
            })
            root.addView(head, fcLp(FC_MATCH, FC_WRAP))

            val availTv = fcTv(act, "正在读取容量…", 15f, FC_DIM)
            root.addView(availTv, fcLp(FC_MATCH, FC_WRAP).apply {
                topMargin = fcDp(act, 8); bottomMargin = fcDp(act, 6)
            })

            // 模式切换
            val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val pathTab = fcBtn(act, "全盘路径", FC_GOLD, FC_INK) {}
            val junkTab = fcBtn(act, "垃圾一键清", 0x22FFFFFF, FC_DIM) {}
            tabs.addView(pathTab, fcLpw(1f))
            tabs.addView(junkTab, fcLpw(1f).apply { leftMargin = fcDp(act, 8) })
            root.addView(tabs, fcLp(FC_MATCH, FC_WRAP))

            // 当前路径 / 导航
            val nav = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pathTv = fcTv(act, "本机全部分区", 13f, FC_GOLD)
            nav.addView(pathTv, fcLpw(1f))
            val upBtn = fcBtn(act, "返回上级", 0x22FFFFFF, FC_WHITE) {}
            val rootsBtn = fcBtn(act, "全部分区", 0x22FFFFFF, FC_WHITE) {}
            nav.addView(upBtn)
            nav.addView(rootsBtn, fcLp(FC_WRAP, FC_WRAP).apply { leftMargin = fcDp(act, 6) })
            root.addView(nav, fcLp(FC_MATCH, FC_WRAP).apply { topMargin = fcDp(act, 6) })

            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val sv = ScrollView(act).apply { addView(list, ViewGroup.LayoutParams(FC_MATCH, FC_WRAP)) }
            root.addView(sv, fcLp(FC_MATCH, fcDp(act, 250)))

            val selTv = fcTv(act, "正在扫描…", 13f, FC_GOLD)
            root.addView(selTv, fcLp(FC_MATCH, FC_WRAP).apply {
                topMargin = fcDp(act, 6); bottomMargin = fcDp(act, 2)
            })

            val autoTv = fcTv(act, "", 13f, FC_DIM).apply {
                setPadding(0, fcDp(act, 6), 0, fcDp(act, 2))
            }
            root.addView(autoTv, fcLp(FC_MATCH, FC_WRAP))
            root.addView(
                fcTv(act, "⚠️ 删除后不可恢复；系统关键分区与桌面配置已只读保护", 12f, FC_RED),
                fcLp(FC_MATCH, FC_WRAP)
            )

            val goBtn = fcBtn(act, "删除选中", FC_RED, FC_WHITE) {}
            root.addView(goBtn, fcLp(FC_MATCH, FC_WRAP).apply { topMargin = fcDp(act, 8) })

            val dlg = fcNewDialog(act, root, 640)
            box[0] = dlg
            dlg.setOnDismissListener { onDone() }
            dlg.show()

            // ---- 状态（先声明，后面用 var lambda 互相引用，避开局部函数前向引用） ----
            var mode = 0                       // 0 全盘路径 / 1 垃圾一键清
            var curPath: String? = null
            var curParent: String? = null
            var label = "本机全部分区"
            var rows: List<Storage.PathItem> = emptyList()
            var items: List<Storage.Item> = emptyList()
            var busy = false
            val selected = LinkedHashMap<String, Storage.PathItem>()

            var paintHeader: () -> Unit = {}
            var paintSel: () -> Unit = {}
            var paintRows: () -> Unit = {}
            var loadPaths: (String?) -> Unit = {}
            var loadJunk: () -> Unit = {}
            var refresh: () -> Unit = {}
            var confirmDelete: () -> Unit = {}
            var cleanJunk: () -> Unit = {}

            fun paintAuto() {
                autoTv.text = if (Storage.autoEnabled())
                    "自动清理：开（低于 1.5G 时清全盘安全项）· 点此关闭"
                else "自动清理：关 · 点此开启"
            }
            autoTv.setOnClickListener {
                Storage.setAuto(!Storage.autoEnabled())
                paintAuto()
            }
            paintAuto()

            fun paintAvail() {
                val a = Storage.availBytes()
                availTv.text = "剩余 ${Storage.gb(a)} / 共 ${Storage.gb(Storage.totalBytes())}" +
                    if (Storage.isLow()) " · ⚠️ 低于 1.5G，必须清理，否则将不能开机" else " · 空间充足"
                availTv.setTextColor(if (Storage.isLow()) FC_RED else FC_DIM)
            }
            paintAvail()

            fun paintTabs() {
                pathTab.background = fcRound(if (mode == 0) FC_GOLD else 0x22FFFFFF, fcDp(act, 10))
                pathTab.setTextColor(if (mode == 0) FC_INK else FC_DIM)
                junkTab.background = fcRound(if (mode == 1) FC_GOLD else 0x22FFFFFF, fcDp(act, 10))
                junkTab.setTextColor(if (mode == 1) FC_INK else FC_DIM)
            }
            paintTabs()

            paintHeader = {
                paintAvail()
                if (mode == 0) {
                    pathTv.text = if (curPath == null) "当前：本机全部分区" else "当前：$label"
                    upBtn.visibility = if (curParent != null) View.VISIBLE else View.GONE
                    rootsBtn.visibility = View.VISIBLE
                } else {
                    pathTv.text = "垃圾一键清 · 应用管家规则（放心 / 推荐 / 慎用）· ${Storage.scanInfo()}"
                    upBtn.visibility = View.GONE
                    rootsBtn.visibility = View.GONE
                }
            }

            paintSel = {
                if (mode == 0) {
                    val n = selected.size
                    val b = selected.values.sumOf { if (it.bytes > 0) it.bytes else 0L }
                    selTv.text = "已勾选 $n 个路径 · 预计释放 ${Storage.fmt(b)} · 本层 ${rows.size} 项（按占用排序）"
                    goBtn.text = if (n > 0) "删除选中 ($n)" else "删除选中"
                } else {
                    val picked = items.filter { it.checked }
                    selTv.text = "已勾 ${picked.size} 类 · 预计释放 ${Storage.summary(picked)} · ${Storage.scanInfo()}"
                    goBtn.text = if (picked.isEmpty()) "一键清理" else "一键清理（${picked.size} 类）"
                }
            }

            paintRows = {
                list.removeAllViews()
                if (mode == 0) {
                    if (rows.isEmpty()) {
                        list.addView(fcTv(act, "该路径下没有可访问的内容", 15f, FC_DIM), fcLp(FC_MATCH, FC_WRAP))
                    }
                    for (row in rows.take(MAX_ROWS)) {
                        fun toggle(target: Storage.PathItem, mark: TextView) {
                            if (target.locked) {
                                Toast.makeText(act, "分区根 / 系统保护路径，不允许删除", Toast.LENGTH_SHORT).show()
                                return
                            }
                            target.checked = !target.checked
                            if (target.checked) selected[target.path] = target
                            else selected.remove(target.path)
                            mark.text = if (target.checked) "☑" else "☐"
                            mark.setTextColor(if (target.checked) FC_GREEN else FC_DIM)
                            paintSel()
                        }
                        val r = LinearLayout(act).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, fcDp(act, 7), 0, fcDp(act, 7))
                            background = fcRound(0x0FFFFFFF, fcDp(act, 10))
                        }
                        val pickedAlready = selected.containsKey(row.path)
                        val mark = fcTv(
                            act,
                            when {
                                row.locked -> "🔒"
                                row.checked || pickedAlready -> "☑"
                                else -> "☐"
                            },
                            18f,
                            if (pickedAlready) FC_GREEN else FC_DIM
                        )
                        r.addView(mark)
                        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(fcTv(act, row.name + "   " + row.sizeText, 15f, FC_WHITE, true))
                        col.addView(
                            fcTv(
                                act,
                                row.path + " · " + (if (row.files > 0) "${row.files} 文件 · " else "") +
                                    row.kind + (if (!row.writable) " · 只读" else ""),
                                11f, FC_DIM
                            )
                        )
                        r.addView(col, fcLpw(1f).apply { leftMargin = fcDp(act, 10) })
                        if (row.dir) r.addView(fcTv(act, "›", 20f, FC_GOLD))
                        mark.setOnClickListener { toggle(row, mark) }
                        r.setOnClickListener {
                            if (row.dir) loadPaths(row.path) else toggle(row, mark)
                        }
                        list.addView(r, fcLp(FC_MATCH, FC_WRAP))
                    }
                    if (rows.size > MAX_ROWS) {
                        list.addView(
                            fcTv(act, "… 仅显示占用最大的前 $MAX_ROWS 项（共 ${rows.size} 项）", 12f, FC_DIM),
                            fcLp(FC_MATCH, FC_WRAP)
                        )
                    }
                } else {
                    if (items.isEmpty()) {
                        list.addView(
                            fcTv(act, "全盘没有扫到可清理的内容（应用管家规则：缓存 / 日志 / 临时碎片 / 残留 / 空目录）", 15f, FC_DIM),
                            fcLp(FC_MATCH, FC_WRAP)
                        )
                    }
                    // 参考应用管家的「放心清理 / 推荐清理 / 慎用」三级分组
                    var lastLevel = -1
                    for (item in items.sortedBy { it.level }) {
                        val lv = item.level.coerceIn(0, Storage.LEVEL_NAMES.size - 1)
                        if (lv != lastLevel) {
                            lastLevel = lv
                            val mark = Storage.LEVEL_MARKS[lv]
                            val head = fcTv(
                                act,
                                "$mark ${Storage.LEVEL_NAMES[lv]} · " + when (lv) {
                                    0 -> "删了自动重建，默认勾选"
                                    1 -> "建议清理，默认勾选"
                                    else -> "可能误删，默认不勾"
                                },
                                13f,
                                when (lv) { 0 -> FC_GREEN; 1 -> FC_GOLD; else -> FC_RED },
                                true
                            )
                            head.setPadding(fcDp(act, 4), fcDp(act, 10), 0, fcDp(act, 4))
                            list.addView(head, fcLp(FC_MATCH, FC_WRAP))
                        }
                        val r = LinearLayout(act).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, fcDp(act, 8), 0, fcDp(act, 8))
                            background = fcRound(0x0FFFFFFF, fcDp(act, 10))
                        }
                        val dot = fcTv(act, if (item.checked) "☑" else "☐", 19f,
                            if (item.checked) FC_GREEN else FC_DIM)
                        r.addView(dot)
                        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                        col.addView(fcTv(act, item.title + "   " + item.sizeText, 15f, FC_WHITE, true))
                        col.addView(
                            fcTv(
                                act,
                                item.desc + " · ${item.files} 文件 / ${item.targets.size} 处" +
                                    if (item.truncated) " · 扫描已截断" else "",
                                12f, FC_DIM
                            )
                        )
                        r.addView(col, fcLpw(1f).apply { leftMargin = fcDp(act, 10) })
                        r.setOnClickListener {
                            if (!item.checked && lv == 2) {
                                Toast.makeText(act, "慎用项：确认这些文件确实不需要再勾选", Toast.LENGTH_SHORT).show()
                            }
                            item.checked = !item.checked
                            dot.text = if (item.checked) "☑" else "☐"
                            dot.setTextColor(if (item.checked) FC_GREEN else FC_DIM)
                            paintSel()
                        }
                        list.addView(r, fcLp(FC_MATCH, FC_WRAP))
                    }
                }
                paintSel()
            }

            loadPaths = { path ->
                busy = true
                selTv.text = "正在扫描本机路径…"
                Thread {
                    val b = runCatching { Storage.browse(path) }.getOrNull()
                    FC_MAIN.post {
                        if (act.isFinishing) return@post
                        busy = false
                        if (b == null) {
                            selTv.text = "路径不可读：$path"
                            return@post
                        }
                        curPath = b.path
                        curParent = b.parent
                        label = b.label
                        rows = b.children
                        paintHeader()
                        paintRows()
                    }
                }.start()
            }

            loadJunk = {
                busy = true
                selTv.text = "正在全盘扫描所有路径…"
                Thread {
                    val r = runCatching { Storage.scan(true) }.getOrDefault(emptyList())
                    FC_MAIN.post {
                        if (act.isFinishing) return@post
                        busy = false
                        items = r
                        paintHeader()
                        paintRows()
                    }
                }.start()
            }

            refresh = { if (mode == 0) loadPaths(curPath) else loadJunk() }

            upBtn.setOnClickListener { if (curParent != null) loadPaths(curParent) }
            rootsBtn.setOnClickListener { loadPaths(null) }
            pathTab.setOnClickListener {
                if (mode != 0) {
                    mode = 0
                    paintTabs(); paintHeader(); paintSel(); loadPaths(curPath)
                }
            }
            junkTab.setOnClickListener {
                if (mode != 1) {
                    mode = 1
                    paintTabs(); paintHeader(); paintSel(); loadJunk()
                }
            }

            confirmDelete = {
                val paths = selected.keys.toList()
                if (paths.isEmpty()) {
                    Toast.makeText(act, "先勾选要删除的路径", Toast.LENGTH_SHORT).show()
                } else {
                    val total = selected.values.sumOf { if (it.bytes > 0) it.bytes else 0L }
                    val cbox = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(fcDp(act, 24), fcDp(act, 18), fcDp(act, 24), fcDp(act, 16))
                        background = fcRound(FC_CARD, fcDp(act, 18))
                    }
                    cbox.addView(fcTv(act, "⚠️ 确认删除", 23f, FC_RED, true))
                    cbox.addView(
                        fcTv(act, "以下 ${paths.size} 个路径将被彻底删除，不可恢复：", 15f, FC_WHITE),
                        fcLp(FC_MATCH, FC_WRAP).apply { topMargin = fcDp(act, 8) }
                    )
                    val l2 = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    for (p in paths.take(12)) l2.addView(fcTv(act, "• $p", 12f, FC_DIM))
                    if (paths.size > 12) {
                        l2.addView(fcTv(act, "… 其余 ${paths.size - 12} 项", 12f, FC_DIM))
                    }
                    val sv2 = ScrollView(act).apply {
                        addView(l2, ViewGroup.LayoutParams(FC_MATCH, FC_WRAP))
                    }
                    cbox.addView(sv2, fcLp(FC_MATCH, fcDp(act, 150)))
                    cbox.addView(
                        fcTv(act, "预计释放 ${Storage.fmt(total)} · 系统关键分区已只读保护", 14f, FC_GOLD),
                        fcLp(FC_MATCH, FC_WRAP).apply { bottomMargin = fcDp(act, 10) }
                    )
                    val cdlg = arrayOfNulls<Dialog>(1)
                    val br = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    br.addView(fcBtn(act, "取消", 0x33FFFFFF, FC_WHITE) { cdlg[0]?.dismiss() }, fcLpw(1f))
                    br.addView(
                        fcBtn(act, "确认删除", FC_RED, FC_WHITE) {
                            cdlg[0]?.dismiss()
                            busy = true
                            goBtn.text = "删除中…"
                            Thread {
                                val freed = runCatching { Storage.deletePaths(paths) }.getOrDefault(0L)
                                FC_MAIN.post {
                                    if (act.isFinishing) return@post
                                    busy = false
                                    goBtn.text = "删除选中"
                                    selected.clear()
                                    paintHeader()
                                    Toast.makeText(
                                        act,
                                        "已删除 ${paths.size} 个路径，释放 ${Storage.fmt(freed)}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refresh()
                                }
                            }.start()
                        },
                        fcLpw(1f).apply { leftMargin = fcDp(act, 8) }
                    )
                    cbox.addView(br, fcLp(FC_MATCH, FC_WRAP))
                    cdlg[0] = fcNewDialog(act, cbox, 560)
                    cdlg[0]?.show()
                }
            }

            cleanJunk = {
                val picked = items.filter { it.checked }
                if (picked.isEmpty()) {
                    Toast.makeText(act, "先勾选要清理的分类", Toast.LENGTH_SHORT).show()
                } else {
                    busy = true
                    goBtn.text = "清理中…"
                    Thread {
                        val freed = runCatching { Storage.clean(picked) }.getOrDefault(0L)
                        FC_MAIN.post {
                            if (act.isFinishing) return@post
                            busy = false
                            goBtn.text = "一键清理"
                            paintHeader()
                            Toast.makeText(act, "清理完成，释放 ${Storage.fmt(freed)} · 已重新扫描", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }.start()
                }
            }

            goBtn.setOnClickListener {
                if (busy) {
                    Toast.makeText(act, "正在扫描 / 清理，请稍候", Toast.LENGTH_SHORT).show()
                } else if (mode == 0) {
                    confirmDelete()
                } else {
                    cleanJunk()
                }
            }

            paintHeader()
            paintSel()
            loadPaths(null)
        } catch (t: Throwable) {
            onDone()
        }
    }

    /** targetSdk=27 也要运行时申请外部存储读写，否则手机上扫到的路径会少一大半。 */
    private fun ensurePermission(act: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            val need = ArrayList<String>()
            for (p in arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )) {
                if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p)
            }
            if (need.isNotEmpty()) act.requestPermissions(need.toTypedArray(), 1901)
        }
    }

    /** Android 11+ 要读 Android/data 必须开「所有文件访问」，给个直达开关。 */
    private fun openAllFiles(act: Activity) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(act, "已拥有所有文件访问权限", Toast.LENGTH_SHORT).show()
            } else {
                val i = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.packageName)
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
            }
        }.onFailure {
            Toast.makeText(act, "请在系统设置里开启「所有文件访问」", Toast.LENGTH_SHORT).show()
        }
    }
}
