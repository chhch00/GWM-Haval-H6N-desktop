package com.h6n.launcher.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * 桌面壁纸：配置 + 图片解码。
 *
 * 壁纸来源两类（6.0.96 起**包内不再打包任何壁纸**）：
 *  1) 在线图库：[WallpaperOnline] 从服务器拉的图，选中后下载到
 *     `getExternalFilesDir/wallpaper/full/`，这里只认绝对路径 —— 对桌面来说它跟本机图片没区别，
 *     断网也照常显示已下载的那张。
 *  2) 机内存储：用户从内部存储 / DCIM / Pictures / Download / U 盘挂载卷里挑的图片，
 *     存的是绝对路径，不拷贝进私有目录。
 *
 * 默认是 `MODE_THEME`（**不铺任何图，走主题渐变**）：全新安装打开桌面就是干净的渐变底，
 * 不再像 6.0.96 那样偷偷去服务器拉一张铺上 —— 用户要壁纸自己去「壁纸」面板挑。
 *
 * `MODE_BUILTIN` 只剩**兼容旧配置**的意义：6.0.95 及以前存的是 `wall_13` 这类名字，
 * 包里早已没有这张图，[decode] 对它返回 null（桌面走渐变）。
 *
 * 解码一律带 inSampleSize：车机内存小，直接 decode 一张 4000×3000 的图很容易 OOM。
 */
object WallpaperStore {

    const val PREFS = "h6_wallpaper"
    private const val K_MODE = "mode"
    private const val K_BUILTIN = "builtin"
    private const val K_PATH = "path"
    private const val K_LABEL = "label"
    private const val K_DIM = "dim"
    private const val K_SCRIM = "scrim"

    /**
     * 壁纸蒙层档位。
     *
     * 这层蒙层的颜色**跟着主题走**，不是固定白色：
     *  - 白天文字是深色 → 铺白蒙层把壁纸提亮，深字才压得住；
     *  - 夜间文字是白色 → 铺黑蒙层压暗，白字才清楚，整屏也不会被亮壁纸照亮。
     * 早期版本写死 `0x33FFFFFF`（白 20%），夜间反而把壁纸调亮，就是
     * 「换壁纸后看不清 + 深色模式太亮」的根因。
     */
    const val SCRIM_OFF = 0
    const val SCRIM_WEAK = 1
    const val SCRIM_MID = 2
    const val SCRIM_STRONG = 3

    /** 蒙层档位名称。 */
    fun scrimLabel(level: Int): String = when (level) {
        SCRIM_OFF -> "关"
        SCRIM_WEAK -> "弱"
        SCRIM_STRONG -> "强"
        else -> "中"
    }

    const val MODE_BUILTIN = "builtin"
    const val MODE_FILE = "file"
    const val MODE_THEME = "theme"

    /** 默认值：包内已无壁纸，`builtin` 只是「还没选过」的旧哨兵，由 seed 补一张在线图。 */
    const val DEFAULT_BUILTIN = ""

    data class Choice(
        val mode: String,
        val builtin: String,
        val path: String,
        val dim: Boolean,
        val scrim: Int,
        /** 在线壁纸的展示名（`在线壁纸 12`）；本机图片留空，直接显示文件名。 */
        val label: String = ""
    ) {
        /** 用来判断要不要重新解码，避免每秒 tick / 昼夜切换反复读图。 */
        fun key(): String = "$mode|$builtin|$path|$scrim"

        fun label(): String = when (mode) {
            MODE_FILE -> if (label.isNotBlank()) label
            else if (path.isBlank()) "本机图片" else File(path).name
            MODE_THEME -> "不设壁纸（主题渐变）"
            MODE_BUILTIN -> "未设置壁纸"
            else -> if (label.isNotBlank()) label else "默认（未选壁纸）"
        }
    }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context): Choice {
        val p = sp(c)
        // 兼容旧版本只存了布尔 dim：true → 中，false → 关
        val scrim = p.getInt(K_SCRIM, if (p.getBoolean(K_DIM, true)) SCRIM_MID else SCRIM_OFF)
            .coerceIn(SCRIM_OFF, SCRIM_STRONG)
        return Choice(
            p.getString(K_MODE, MODE_THEME) ?: MODE_THEME,
            p.getString(K_BUILTIN, DEFAULT_BUILTIN) ?: DEFAULT_BUILTIN,
            p.getString(K_PATH, "") ?: "",
            scrim > SCRIM_OFF,
            scrim,
            p.getString(K_LABEL, "") ?: ""
        )
    }

    fun setBuiltin(c: Context, name: String) {
        sp(c).edit().putString(K_MODE, MODE_BUILTIN).putString(K_BUILTIN, name).apply()
    }

    fun setFile(c: Context, path: String) {
        // 换图时先抹掉旧的展示名，避免「在线壁纸 12」贴到本机图片上
        sp(c).edit().putString(K_MODE, MODE_FILE).putString(K_PATH, path).remove(K_LABEL).apply()
    }

    /** 给在线壁纸起个好看的展示名（文件名是 `mmexport1783…jpg`，不能直接给用户看）。 */
    fun setLabel(c: Context, label: String) {
        sp(c).edit().putString(K_LABEL, label).apply()
    }

    fun setTheme(c: Context) {
        sp(c).edit().putString(K_MODE, MODE_THEME).remove(K_LABEL).apply()
    }

    /** 清除壁纸：包内已无内置图，等价于回到主题渐变 + 蒙层恢复「中」。 */
    fun reset(c: Context) {
        sp(c).edit().clear().apply()
        setTheme(c)
        setScrim(c, SCRIM_MID)
    }

    fun setDim(c: Context, on: Boolean) {
        setScrim(c, if (on) SCRIM_MID else SCRIM_OFF)
    }

    fun setScrim(c: Context, level: Int) {
        val lv = level.coerceIn(SCRIM_OFF, SCRIM_STRONG)
        sp(c).edit().putInt(K_SCRIM, lv).putBoolean(K_DIM, lv > SCRIM_OFF).apply()
    }

    /** 下一档（关→弱→中→强→关），面板上点一下就循环。 */
    fun nextScrim(level: Int): Int = (level + 1) % 4

    /**
     * 蒙层实际颜色。**浅色文字铺黑、深色文字铺白**。
     *
     * 判据由调用方给（`Theme.lightText()`），不能直接拿夜色：老配置白天也是白字。
     *
     * | 档位 | 深字（白蒙层） | 浅字（黑蒙层） |
     * |---|---|---|
     * | 弱 | 18% | 40% |
     * | 中 | 36% | 62% |
     * | 强 | 52% | 78% |
     */
    fun scrimColor(level: Int, lightText: Boolean): Int {
        if (level <= SCRIM_OFF) return 0x00000000
        val idx = level.coerceIn(SCRIM_OFF, SCRIM_STRONG)
        val light = intArrayOf(0, 0x2E, 0x5C, 0x85)   // 白蒙层
        val dark = intArrayOf(0, 0x66, 0x9E, 0xC7)    // 黑蒙层
        val a = if (lightText) dark[idx] else light[idx]
        val rgb = if (lightText) 0x000000 else 0xFFFFFF
        return (a shl 24) or rgb
    }

    /** 桌面当前是否铺着壁纸（选「主题渐变」时不算）。 */
    fun isActive(c: Context): Boolean = get(c).mode != MODE_THEME

    // ---------------- 左侧竖条取色（覆盖原厂导航栏的背板用） ----------------

    /**
     * 壁纸左侧竖条的上/中/下三段平均色，以及融合蒙层后的明度。
     *
     * ## 为什么要取色
     * 覆盖原厂导航栏的悬浮层必须铺**不透明**背板（否则原厂竖栏会从玻璃后面透出来），
     * 于是背板颜色直接决定观感：老版固定取 `panelNightTop/Bot`（#232528→#131415 近黑），
     * 一旦用户铺了浅色壁纸，屏幕左侧就突兀地竖着一条黑板 —— 6.0.60 实机截图正是这个。
     * 这里改成按桌面**实际铺的那张壁纸**取色：背板和壁纸同色，看上去就是壁纸透了过来。
     *
     * @param bandPct 条带宽度占整幅壁纸的比例（= 覆盖层宽度 / 屏宽）
     * @param scrim   壁纸之上的蒙层色（[scrimColor]）。取色必须把它算进去，否则背板会
     *                比旁边亮/暗一档，接缝反而更显眼。
     */
    class Band(val top: Int, val mid: Int, val bot: Int, val lum: Float)

    /** 同一张壁纸 + 同一档蒙层只算一次（每次 rebuild 都重算会白白解一遍码）。 */
    private var bandKey = ""
    private var bandVal: Band? = null

    fun leftBand(c: Context, ch: Choice, bandPct: Float, scrim: Int): Band? {
        if (ch.mode == MODE_THEME) return null
        val pct = bandPct.coerceIn(0.02f, 0.5f)
        val key = ch.key() + "|" + Math.round(pct * 1000f) + "|" + scrim
        if (key == bandKey) return bandVal
        // 只要一百多像素宽的缩略图：车机上解码 ms 级，且完全够算平均色
        val bmp = decode(c, ch, 160, 90) ?: return null
        val band = try {
            measureBand(bmp, pct, scrim)
        } catch (t: Throwable) {
            null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        bandKey = key
        bandVal = band
        return band
    }

    /** 三段取平均：上 0~30%、中 30~72%、下 72~100%，再各自叠蒙层。 */
    private fun measureBand(bmp: Bitmap, pct: Float, scrim: Int): Band {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return Band(NEUTRAL, NEUTRAL, NEUTRAL, 0.5f)
        val bw = Math.max(1, Math.round(w * pct))
        fun avg(y0: Int, y1: Int): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            val end = Math.min(h, Math.max(y0 + 1, y1))
            var y = y0.coerceIn(0, h - 1)
            while (y < end) {
                var x = 0
                while (x < bw) {
                    val px = bmp.getPixel(x, y)
                    r += (px shr 16) and 0xFF
                    g += (px shr 8) and 0xFF
                    b += px and 0xFF
                    n++
                    x++
                }
                y++
            }
            if (n == 0L) return NEUTRAL
            return (0xFF shl 24) or
                ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
        val top = over(avg(0, h * 30 / 100), scrim)
        val mid = over(avg(h * 30 / 100, h * 72 / 100), scrim)
        val bot = over(avg(h * 72 / 100, h), scrim)
        return Band(top, mid, bot, lumOf(mid))
    }

    /** src 之上叠一层 overlay（alpha 混合），返回不透明色。 */
    private fun over(src: Int, overlay: Int): Int {
        val a = Color.alpha(overlay)
        if (a == 0) return (0xFF shl 24) or (src and 0x00FFFFFF)
        val k = a / 255f
        fun mix(s: Int, o: Int): Int =
            Math.round(s * (1f - k) + o * k).coerceIn(0, 255)
        return (0xFF shl 24) or
            (mix(Color.red(src), Color.red(overlay)) shl 16) or
            (mix(Color.green(src), Color.green(overlay)) shl 8) or
            mix(Color.blue(src), Color.blue(overlay))
    }

    /** 相对亮度（0 黑 ~ 1 白）：背板亮就用深字，暗就用白字。 */
    private fun lumOf(c: Int): Float =
        0.299f * Color.red(c) / 255f + 0.587f * Color.green(c) / 255f + 0.114f * Color.blue(c) / 255f

    private const val NEUTRAL = 0xFF808080.toInt()

    fun isImage(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".webp") || n.endsWith(".bmp")
    }

    /** 存储浏览的入口目录（去重）：内部存储 / 相册 / 图片 / 下载 / 外置卷 / U 盘。 */
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun roots(c: Context): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>(8)
        val seen = HashSet<String>()
        fun add(label: String, f: File?) {
            if (f == null || !f.exists() || !f.isDirectory) return
            val k = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (seen.add(k)) out.add(label to f)
        }
        add("内部存储", Environment.getExternalStorageDirectory())
        add("相册 DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
        add("图片 Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
        add("下载 Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        runCatching { File("/storage").listFiles() }.getOrNull()?.forEach { f ->
            if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                add("外置卷 /storage/" + f.name, f)
            }
        }
        runCatching { File("/mnt").listFiles() }.getOrNull()?.forEach { f ->
            val n = f.name.lowercase(Locale.ROOT)
            val hit = n.startsWith("usb") || n.startsWith("udisk") ||
                n.startsWith("media_rw") || n.startsWith("ext") ||
                n.startsWith("external") || n.startsWith("sd")
            if (hit && f.isDirectory) add("外置卷 /mnt/" + f.name, f)
        }
        if (out.isEmpty()) add("内部存储", File("/sdcard"))
        return out
    }

    /** 有没有读存储的权限：Android 11+ 优先看「所有文件访问」，老系统看 READ_EXTERNAL_STORAGE。 */
    fun canRead(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ---------------- 解码 ----------------

    private fun sample(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var s = 1
        var w = srcW
        var h = srcH
        while ((w / 2 >= reqW || h / 2 >= reqH) && s < 16) {
            w /= 2
            h /= 2
            s *= 2
        }
        return s.coerceAtLeast(1)
    }

    /** 按当前选择解码整屏壁纸；mode=theme / 旧内置配置 / 文件失效时返回 null（调用方回退到渐变底）。 */
    @Suppress("UNUSED_PARAMETER")
    fun decode(c: Context, ch: Choice, reqW: Int, reqH: Int): Bitmap? = when (ch.mode) {
        MODE_FILE -> decodeFile(File(ch.path), reqW, reqH, false)
        else -> null
    }

    fun thumbFile(f: File, reqW: Int, reqH: Int): Bitmap? =
        decodeFile(f, reqW, reqH, true)

    private fun decodeFile(f: File, reqW: Int, reqH: Int, thumb: Boolean): Bitmap? {
        if (!f.isFile || !f.canRead()) return null
        return try {
            val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bound)
            if (bound.outWidth <= 0) return null
            val opt = BitmapFactory.Options().apply {
                inSampleSize = sample(bound.outWidth, bound.outHeight, reqW, reqH)
                if (thumb) inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(f.absolutePath, opt)
        } catch (t: Throwable) {
            null
        }
    }
}
