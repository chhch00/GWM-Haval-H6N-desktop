package com.h6n.launcher.ui.main

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.h6n.launcher.core.WallpaperOnline
import com.h6n.launcher.core.WallpaperStore
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 更换壁纸面板（自绘，不用 AlertDialog.setAdapter —— 车机上会出空列表）。
 *
 * 两个来源（6.0.96 起包内不再带壁纸；**默认一张都不铺，桌面就是主题渐变**）：
 *  - 「在线壁纸」：打开面板就去服务器拉列表（[WallpaperOnline]），点缩略图下载并立即生效；
 *    缩略图走服务端生成的 2~3KB 小图，一次拉 30 多张也不心疼流量。拉取失败时退化成
 *    「已下载的本地图」列表，断网也能换回之前下载过的。
 *    图源地址在 [WallpaperOnline] 顶部改，默认占位域名，换自己的服务即可。
 *  - 「机内存储」：内部存储 / DCIM / Pictures / Download / U 盘挂载卷，逐级进目录选图片（不需要激活）。
 *
 * 选择只写一份 SharedPreferences(h6_wallpaper)，桌面在回调里重新解码，不需要重建 Activity。
 */
private val WP_GOLD = 0xFFE0B44CL.toInt()
private val WP_RED = 0xFFFF6B5BL.toInt()
private val WP_GREEN = 0xFF3ECF6EL.toInt()
private val WP_WHITE = 0xFFF5F6F8L.toInt()
private val WP_DIM = 0xFF9AA0A6L.toInt()
private val WP_CARD = 0xF21A1D24L.toInt()
private val WP_ROW = 0x14FFFFFFL.toInt()
private val WP_MAIN = Handler(Looper.getMainLooper())
private const val WP_MATCH = -1
private const val WP_WRAP = -2

private fun wpDp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

private fun wpRound(fill: Int, radius: Int, stroke: Int = 0, sw: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, sw), stroke)
    }

private fun wpTv(c: Context, s: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(c).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

private fun wpBtn(c: Context, s: String, fill: Int, fg: Int, onClick: () -> Unit): TextView =
    wpTv(c, s, 13f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(wpDp(c, 13), wpDp(c, 8), wpDp(c, 13), wpDp(c, 8))
        background = wpRound(fill, wpDp(c, 9))
        isClickable = true
        setOnClickListener { onClick() }
    }

private fun wpNewDialog(act: Activity, root: View, widthDp: Int): Dialog = Dialog(act).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(root)
    window?.let { w ->
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val lp = w.attributes
        lp.dimAmount = 0.68f
        w.attributes = lp
    }
    setOnShowListener { window?.setLayout(wpDp(act, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT) }
}

/** 网格里的一个格子：标题、是否当前选中、点击动作、缩略图加载（在 IO 线程执行）。 */
private class WpCell(
    val title: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val load: () -> Bitmap?
)

object WallpaperDialog {

    fun show(act: Activity, onChanged: () -> Unit = {}) {
        if (act.isFinishing) return
        try {
            // 这几个可变状态先声明：下面的按钮点击闭包要引用它们
            lateinit var dlg: Dialog
            lateinit var scrimBtn: TextView
            var tab = 0                 // 0 在线 / 1 机内存储
            var dir: File = WallpaperStore.roots(act).firstOrNull()?.second ?: File("/sdcard")
            var repaint: () -> Unit = {}

            // 在线列表状态
            var online: List<WallpaperOnline.Item> = emptyList()
            var onlineBusy = false
            var onlineErr = ""
            var loadedOnce = false
            var applying = ""

            // 单线程串行：缩略图、下载都排这条队，车机上并发下载只会互相拖慢
            val io = Executors.newSingleThreadExecutor { r -> Thread(r, "h6-wall") }

            /** 投递后台任务；面板关掉后线程池已 shutdown，这里吞掉拒绝，别把异常抛到主线程。 */
            fun bg(task: () -> Unit) {
                runCatching { io.execute(task) }
            }

            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(wpDp(act, 22), wpDp(act, 18), wpDp(act, 22), wpDp(act, 14))
                background = wpRound(WP_CARD, wpDp(act, 18), WP_GOLD, wpDp(act, 2))
            }
            root.addView(wpTv(act, "更换壁纸", 19f, WP_GOLD, true),
                LinearLayout.LayoutParams(WP_MATCH, WP_WRAP))

            val info = wpTv(act, "正在读取…", 13f, WP_WHITE)
            root.addView(info, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                topMargin = wpDp(act, 6)
            })
            root.addView(
                wpTv(act, "壁纸从在线图库获取，服务器更新了这里就有；也可以从内部存储、相册、下载目录或 U 盘里选图片。背景蒙层会随昼夜自动压暗/提亮，保证文字清楚。", 11f, WP_DIM),
                LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                    topMargin = wpDp(act, 4)
                    bottomMargin = wpDp(act, 10)
                }
            )

            val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            root.addView(tabs, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP))

            // 在线图库的工具条：张数 + 刷新
            val onlineRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val onlineTv = wpTv(act, "", 12f, WP_DIM)
            onlineRow.addView(onlineTv, LinearLayout.LayoutParams(0, WP_WRAP, 1f))
            onlineRow.addView(wpBtn(act, "刷新列表", 0x22FFFFFF, WP_WHITE) {
                loadedOnce = false
                repaint()
            })
            root.addView(onlineRow, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                topMargin = wpDp(act, 8)
            })

            // 机内存储的工具条
            val pathRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
            }
            val pathTv = wpTv(act, "", 12f, WP_DIM)
            pathRow.addView(pathTv, LinearLayout.LayoutParams(0, WP_WRAP, 1f))
            // 三个按钮只建一次，repaint 不再往 pathRow 里重复塞 View
            pathRow.addView(wpBtn(act, "上级", 0x22FFFFFF, WP_WHITE) {
                dir = dir.parentFile ?: dir
                repaint()
            })
            pathRow.addView(wpBtn(act, "常用目录", 0x22FFFFFF, WP_WHITE) {
                val list = WallpaperStore.roots(act)
                if (list.isNotEmpty()) {
                    dir = list[0].second
                    repaint()
                    WP_MAIN.post { Toast.makeText(act, "已回到 " + list[0].first, Toast.LENGTH_SHORT).show() }
                }
            }, LinearLayout.LayoutParams(WP_WRAP, WP_WRAP).apply { leftMargin = wpDp(act, 6) })
            pathRow.addView(wpBtn(act, "刷新", 0x22FFFFFF, WP_WHITE) { repaint() },
                LinearLayout.LayoutParams(WP_WRAP, WP_WRAP).apply { leftMargin = wpDp(act, 6) })
            root.addView(pathRow, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                topMargin = wpDp(act, 8)
            })

            val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val sv = ScrollView(act).apply {
                addView(content, ViewGroup.LayoutParams(WP_MATCH, WP_WRAP))
                background = wpRound(0x0CFFFFFF, wpDp(act, 10))
                setPadding(wpDp(act, 8), wpDp(act, 8), wpDp(act, 8), wpDp(act, 8))
            }
            root.addView(sv, LinearLayout.LayoutParams(WP_MATCH, wpDp(act, 320)).apply {
                topMargin = wpDp(act, 8)
                bottomMargin = wpDp(act, 6)
            })

            val status = wpTv(act, "", 11f, WP_DIM)
            root.addView(status, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP))
            val bottom = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            root.addView(bottom, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                topMargin = wpDp(act, 8)
            })

            fun toast(s: String) = WP_MAIN.post {
                Toast.makeText(act, s, Toast.LENGTH_SHORT).show()
            }

            fun pollPermission(left: Int) {
                if (left <= 0 || !dlg.isShowing) return
                WP_MAIN.postDelayed({
                    if (!dlg.isShowing) return@postDelayed
                    if (WallpaperStore.canRead(act)) {
                        toast("已获得存储权限，正在重新扫描")
                        repaint()
                    } else {
                        pollPermission(left - 1)
                    }
                }, 900L)
            }

            fun applyFile(f: File) {
                bg {
                    val ok = runCatching { WallpaperStore.thumbFile(f, 200, 90) != null }.getOrDefault(false)
                    WP_MAIN.post {
                        if (!ok) {
                            toast("这张图片读不出来或格式不支持")
                            return@post
                        }
                        WallpaperStore.setFile(act, f.absolutePath)
                        onChanged()
                        toast("已应用 " + f.name)
                        repaint()
                    }
                }
            }

            fun addRow(name: String, sub: String, action: String, iv: ImageView?, onClick: () -> Unit) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(wpDp(act, 8), wpDp(act, 6), wpDp(act, 8), wpDp(act, 6))
                    background = wpRound(WP_ROW, wpDp(act, 9))
                    isClickable = true
                    setOnClickListener { onClick() }
                }
                if (iv != null) {
                    r.addView(iv, LinearLayout.LayoutParams(wpDp(act, 96), wpDp(act, 36)))
                }
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col.addView(wpTv(act, name, 13f, WP_WHITE, true))
                col.addView(wpTv(act, sub, 10f, WP_DIM))
                r.addView(col, LinearLayout.LayoutParams(0, WP_WRAP, 1f).apply {
                    leftMargin = wpDp(act, 8)
                })
                r.addView(wpTv(act, action, 12f, WP_GOLD, true))
                content.addView(r, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                    bottomMargin = wpDp(act, 5)
                })
            }

            /**
             * 一行 5 格的缩略图网格。缩略图在 IO 线程按顺序加载，
             * 关掉面板或切了选项卡就停（车机 CPU 慢，别让它空跑）。
             */
            fun paintGrid(cells: List<WpCell>) {
                val cols = 5
                val tw = wpDp(act, 156)
                val th = wpDp(act, 60)
                var row: LinearLayout? = null
                val jobs = ArrayList<Pair<ImageView, WpCell>>(cells.size)
                for ((i, cell) in cells.withIndex()) {
                    if (i % cols == 0) {
                        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                        content.addView(r, LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                            bottomMargin = wpDp(act, 6)
                        })
                        row = r
                    }
                    val item = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    val iv = ImageView(act).apply {
                        scaleType = ImageView.ScaleType.FIT_XY
                        setBackgroundColor(0xFF242933L.toInt())
                        if (cell.selected) {
                            background = wpRound(0x00000000, wpDp(act, 6), WP_GOLD, wpDp(act, 2))
                        }
                    }
                    item.addView(iv, LinearLayout.LayoutParams(tw, th))
                    item.addView(
                        wpTv(act, cell.title, 10f, if (cell.selected) WP_GOLD else WP_DIM, cell.selected)
                    )
                    item.isClickable = true
                    item.setOnClickListener { cell.onClick() }
                    row?.addView(item, LinearLayout.LayoutParams(tw, WP_WRAP).apply {
                        if (i % cols != 0) leftMargin = wpDp(act, 6)
                    })
                    jobs.add(iv to cell)
                }
                val myTab = tab
                bg {
                    for ((iv, cell) in jobs) {
                        if (!dlg.isShowing || tab != myTab) break
                        val bmp = runCatching { cell.load() }.getOrNull() ?: continue
                        WP_MAIN.post {
                            if (dlg.isShowing && tab == myTab) iv.setImageBitmap(bmp)
                        }
                    }
                }
            }

            fun applyOnline(item: WallpaperOnline.Item, idx: Int) {
                if (applying.isNotBlank()) {
                    toast("正在应用上一张，稍等一下")
                    return
                }
                applying = item.name
                status.text = "正在下载第 ${idx + 1} 张…"
                bg {
                    val r = runCatching { WallpaperOnline.download(act, item) }
                    WP_MAIN.post {
                        applying = ""
                        val f = r.getOrNull()
                        if (f == null) {
                            toast("下载失败：" + (r.exceptionOrNull()?.message ?: "网络异常"))
                            if (dlg.isShowing) repaint()
                            return@post
                        }
                        WallpaperStore.setFile(act, f.absolutePath)
                        WallpaperStore.setLabel(act, "在线壁纸 " + (idx + 1))
                        onChanged()
                        toast("已应用在线壁纸 " + (idx + 1))
                        repaint()
                    }
                }
            }

            fun loadOnline() {
                if (onlineBusy) return
                onlineBusy = true
                onlineErr = ""
                if (dlg.isShowing) repaint()
                bg {
                    val r = runCatching { WallpaperOnline.fetchList() }
                    WP_MAIN.post {
                        onlineBusy = false
                        loadedOnce = true
                        r.onSuccess { online = it }
                        r.onFailure { onlineErr = it.message ?: "网络异常" }
                        if (dlg.isShowing) repaint()
                    }
                }
            }

            fun paintOnline() {
                if (onlineBusy) {
                    content.addView(
                        wpTv(act, "正在从服务器读取壁纸列表…", 13f, WP_WHITE),
                        LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply { bottomMargin = wpDp(act, 8) }
                    )
                    status.text = "加载中…"
                    return
                }
                if (online.isEmpty()) {
                    content.addView(
                        wpTv(
                            act,
                            if (onlineErr.isBlank()) "服务器上还没有图片" else "读取失败：$onlineErr",
                            13f, if (onlineErr.isBlank()) WP_DIM else WP_RED
                        ),
                        LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply { bottomMargin = wpDp(act, 6) }
                    )
                    content.addView(
                        wpTv(act, "换壁纸要联网（example.com）。已下载过的图仍可用，见下方列表。", 11f, WP_DIM),
                        LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply { bottomMargin = wpDp(act, 8) }
                    )
                    content.addView(wpBtn(act, if (onlineErr.isBlank()) "重新读取" else "重试", 0x33E0B44C, WP_GOLD) {
                        loadedOnce = false
                        loadOnline()
                    })
                    val local = WallpaperOnline.cachedFiles(act)
                    if (local.isEmpty()) {
                        status.text = "在线壁纸 0 张 · 本机没有已下载的壁纸"
                        return
                    }
                    content.addView(
                        wpTv(act, "已下载到本机（离线可用）", 12f, WP_WHITE, true),
                        LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply {
                            topMargin = wpDp(act, 10)
                            bottomMargin = wpDp(act, 6)
                        }
                    )
                    val curPath = WallpaperStore.get(act).path
                    paintGrid(local.mapIndexed { i, f ->
                        WpCell(
                            "本地 ${i + 1}", curPath == f.absolutePath,
                            { applyFile(f) },
                            { WallpaperStore.thumbFile(f, 300, 110) }
                        )
                    })
                    status.text = "离线：本机已下载 ${local.size} 张"
                    return
                }
                val curPath = WallpaperStore.get(act).path
                paintGrid(online.mapIndexed { i, item ->
                    val f = WallpaperOnline.fullFile(act, item.name)
                    WpCell(
                        "第 ${i + 1} 张",
                        curPath == f.absolutePath,
                        { applyOnline(item, i) },
                        { WallpaperOnline.thumb(act, item, 300, 110) }
                    )
                })
                status.text = "在线壁纸 ${online.size} 张 · 点缩略图下载并应用" +
                    (if (applying.isBlank()) "" else " · 正在下载…")
            }

            fun paintStorage() {
                pathTv.text = "目录：" + dir.absolutePath
                if (!WallpaperStore.canRead(act)) {
                    content.addView(
                        wpTv(act, "还没有读取存储的权限，点下面按钮授权后会自动重新扫描。", 13f, WP_WHITE),
                        LinearLayout.LayoutParams(WP_MATCH, WP_WRAP).apply { bottomMargin = wpDp(act, 10) }
                    )
                    content.addView(wpBtn(act, "申请读取权限", 0x33E0B44C, WP_GOLD) {
                        runCatching {
                            act.requestPermissions(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ), 1902
                            )
                        }
                        pollPermission(6)
                    })
                    if (Build.VERSION.SDK_INT >= 30) {
                        content.addView(wpBtn(act, "打开「所有文件访问」", 0x22FFFFFF, WP_WHITE) {
                            runCatching {
                                act.startActivity(Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + act.packageName)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            pollPermission(8)
                        }, LinearLayout.LayoutParams(WP_WRAP, WP_WRAP).apply { topMargin = wpDp(act, 8) })
                    }
                    status.text = "授权后点「刷新」也可以重新扫描"
                    return
                }
                val kids = runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
                val dirs = kids.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                val imgs = kids.filter { it.isFile && WallpaperStore.isImage(it.name) }
                    .sortedBy { it.name.lowercase() }
                if (dirs.isEmpty() && imgs.isEmpty()) {
                    content.addView(wpTv(act, "这个目录里没有子目录或图片（jpg / png / webp / bmp）", 13f, WP_DIM))
                    status.text = "0 张图片"
                    return
                }
                for (d in dirs.take(40)) {
                    addRow(d.name, "目录", "进入", null) { dir = d; repaint() }
                }
                val thumbs = ArrayList<Pair<ImageView, File>>()
                for (f in imgs.take(60)) {
                    val iv = ImageView(act).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFF242933L.toInt())
                    }
                    addRow(f.name, fmtSize(f.length()), "应用", iv) { applyFile(f) }
                    thumbs.add(iv to f)
                }
                val myTab = tab
                bg {
                    for ((iv, f) in thumbs) {
                        if (!dlg.isShowing || tab != myTab) break
                        val bmp = runCatching { WallpaperStore.thumbFile(f, 180, 80) }.getOrNull()
                        WP_MAIN.post {
                            if (dlg.isShowing && tab == myTab) iv.setImageBitmap(bmp)
                        }
                    }
                }
                status.text = "当前目录 ${dirs.size} 个子目录 · ${imgs.size} 张图片"
            }

            repaint = {
                val cur = WallpaperStore.get(act)
                info.text = "当前壁纸：" + cur.label()
                tabs.removeAllViews()
                val b0 = wpBtn(act, "在线壁纸", if (tab == 0) 0x44E0B44C else 0x18FFFFFF,
                    if (tab == 0) WP_GOLD else WP_WHITE) { tab = 0; repaint() }
                tabs.addView(b0)
                val b1 = wpBtn(act, "机内存储", if (tab == 1) 0x44E0B44C else 0x18FFFFFF,
                    if (tab == 1) WP_GOLD else WP_WHITE) { tab = 1; repaint() }
                tabs.addView(b1, LinearLayout.LayoutParams(WP_WRAP, WP_WRAP).apply {
                    leftMargin = wpDp(act, 8)
                })
                val b2 = wpBtn(act, "不用壁纸", 0x22FF6B5B, WP_RED) {
                    WallpaperStore.setTheme(act)
                    onChanged()
                    toast("已切回主题渐变背景")
                    repaint()
                }
                tabs.addView(b2, LinearLayout.LayoutParams(WP_WRAP, WP_WRAP).apply {
                    leftMargin = wpDp(act, 8)
                })
                onlineRow.visibility = if (tab == 0) View.VISIBLE else View.GONE
                pathRow.visibility = if (tab == 1) View.VISIBLE else View.GONE
                onlineTv.text = when {
                    onlineBusy -> "正在读取列表…"
                    online.isNotEmpty() -> "共 ${online.size} 张 · 服务器更新后点刷新"
                    else -> "在线图库"
                }
                scrimBtn.text = "背景蒙层：" + WallpaperStore.scrimLabel(cur.scrim)
                content.removeAllViews()
                if (tab == 0) {
                    // 第一次进来（或刚点刷新）就去拉列表；还没拉过就先显示加载态
                    if (!loadedOnce && !onlineBusy) loadOnline() else paintOnline()
                } else {
                    paintStorage()
                }
            }

            scrimBtn = wpBtn(act, "", 0x223ECF6E, WP_GREEN) {
                val cur = WallpaperStore.get(act)
                val next = WallpaperStore.nextScrim(cur.scrim)
                WallpaperStore.setScrim(act, next)
                onChanged()
                toast(
                    if (next == WallpaperStore.SCRIM_OFF) "已关闭背景蒙层"
                    else "背景蒙层：" + WallpaperStore.scrimLabel(next) +
                        "（夜间压暗、白天提亮；字看不清就再调高一档）"
                )
                repaint()
            }
            bottom.addView(scrimBtn)
            bottom.addView(wpBtn(act, "关闭", 0x22FFFFFF, WP_WHITE) { dlg.dismiss() }.apply {
                val lp = LinearLayout.LayoutParams(WP_WRAP, WP_WRAP)
                lp.leftMargin = wpDp(act, 8)
                layoutParams = lp
            })

            dlg = wpNewDialog(act, root, 900)
            dlg.setOnDismissListener { io.shutdownNow() }
            dlg.show()
            repaint()
        } catch (t: Throwable) {
            Toast.makeText(act, "打开壁纸面板失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmtSize(n: Long): String = when {
        n >= 1024L * 1024L -> String.format("%.1f MB", n / 1024f / 1024f)
        n >= 1024L -> String.format("%.0f KB", n / 1024f)
        else -> "$n B"
    }
}
