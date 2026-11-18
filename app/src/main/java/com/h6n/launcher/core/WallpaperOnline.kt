package com.h6n.launcher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线壁纸库。
 *
 * 图源是 OpenList 上的分享目录「139云盘 / 分享 / 哈弗国潮桌面更新 / BiZi」。
 * 用户给的是浏览器地址 `/139云盘/分享/…/BiZi`，那是**本地存储路径**，匿名访问会 401
 * （`Guest user is disabled`）；真正对外的通道是同一目录的分享 token：`/@s/YOUR-SHARE-TOKEN/BiZi`。
 * 所以这里统一走：
 *  - 列表：`POST /api/fs/list`，body 里 path 用分享路径（解析器要解码后的中文）
 *  - 缩略图：列表项自带的 `thumb`（服务端生成、预签名，约 2.5KB，省流量）
 *  - 原图：`/sd/YOUR-SHARE-TOKEN/BiZi/<文件名>`（`/d/` 需要登录，用不了）
 *
 * 本地缓存（外部私有目录，卸载即清）：
 * ```
 * getExternalFilesDir/wallpaper/
 *     thumb/<name>   缩略图（2~3KB，二次打开免流量）
 *     full/<name>    原图（选中后下载，桌面从这里解码）
 * ```
 * 桌面只认 [WallpaperStore.MODE_FILE] + 绝对路径，所以「在线壁纸」对上层等于「本地文件」，
 * 断网也照常显示已下载的那张。
 */
object WallpaperOnline {

    const val BASE = "https://example.com"

    /** 列表用的分享路径（对外可匿名读）。 */
    private const val SHARE_PATH = "/@s/YOUR-SHARE-TOKEN/BiZi"

    /** 直链前缀：把 `/@s/` 换成 `/sd/` 就是原图直链。 */
    private const val SD_PATH = "/sd/YOUR-SHARE-TOKEN/BiZi"

    private const val UA = "H6N-Launcher"
    private const val TIMEOUT = 15000

    data class Item(val name: String, val size: Long, val thumb: String, val direct: String)

    // ---------------- 缓存目录 ----------------

    /** 根目录：优先外部私有目录（不占内部 data 分区），拿不到就退内部。 */
    fun root(c: Context): File {
        val d = c.getExternalFilesDir("wallpaper") ?: File(c.filesDir, "wallpaper")
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    /** 文件名消毒：去掉路径分隔符与点点，防止服务端文件名把写入带出缓存目录。 */
    private fun safe(name: String): String =
        name.replace('\\', '_').replace('/', '_').replace("..", "_").trim().ifEmpty { "wall.jpg" }

    fun fullFile(c: Context, name: String): File {
        val d = File(root(c), "full")
        if (!d.isDirectory) d.mkdirs()
        return File(d, safe(name))
    }

    fun thumbFile(c: Context, name: String): File {
        val d = File(root(c), "thumb")
        if (!d.isDirectory) d.mkdirs()
        return File(d, safe(name))
    }

    /** 已经下载到本地的原图（离线可用），按名称排序。 */
    fun cachedFiles(c: Context): List<File> =
        runCatching {
            File(root(c), "full").listFiles()
                ?.filter { it.isFile && it.length() > 0 && WallpaperStore.isImage(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    fun hasAnyFull(c: Context): Boolean = cachedFiles(c).isNotEmpty()

    // ---------------- 网络 ----------------

    private fun open(url: String, timeout: Int = TIMEOUT): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun httpText(url: String, json: String?, timeout: Int = TIMEOUT): String {
        val c = open(url, timeout)
        try {
            if (json != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("网络错误 HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun httpToFile(url: String, dst: File): Long {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("下载失败 HTTP $code")
            var n = 0L
            c.inputStream.use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        n += r
                    }
                    out.flush()
                }
            }
            return n
        } finally {
            c.disconnect()
        }
    }

    /**
     * 拉取目录里的图片列表（服务端顺序 = 最新在前）。
     * 失败抛异常，由界面提示，不要在这里吞掉。
     */
    fun fetchList(): List<Item> {
        val body = JSONObject().apply {
            put("path", SHARE_PATH)
            put("password", "")
            put("page", 1)
            put("per_page", 0)          // 0 = 全部
            put("refresh", false)
        }.toString()
        val text = httpText(BASE + "/api/fs/list", body)
        val rootObj = JSONObject(text)
        if (rootObj.optInt("code") != 200) {
            throw IllegalStateException(rootObj.optString("message", "服务端返回异常"))
        }
        val arr = rootObj.optJSONObject("data")?.optJSONArray("content") ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("is_dir")) continue
            val name = o.optString("name")
            if (name.isBlank() || !WallpaperStore.isImage(name)) continue
            val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            out.add(
                Item(
                    name = name,
                    size = o.optLong("size"),
                    thumb = o.optString("thumb"),
                    direct = BASE + SD_PATH + "/" + enc
                )
            )
        }
        return out
    }

    // ---------------- 下载 / 解码 ----------------

    private fun decodable(f: File): Boolean = runCatching {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opt)
        opt.outWidth > 0 && opt.outHeight > 0
    }.getOrDefault(false)

    /**
     * 下载原图到缓存。已存在且大小对得上、能解码就直接复用。
     * 先写 `.part` 再改名，中途断网不会留下半张图把桌面搞成花屏。
     */
    fun download(c: Context, item: Item): File {
        val f = fullFile(c, item.name)
        if (f.isFile && f.length() > 0 && decodable(f) &&
            (item.size <= 0 || f.length() == item.size)
        ) {
            return f
        }
        val tmp = File(f.parentFile, f.name + ".part")
        if (tmp.exists()) tmp.delete()
        httpToFile(item.direct, tmp)
        if (!decodable(tmp)) {
            tmp.delete()
            throw IllegalStateException("下载到的不是有效图片")
        }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        return f
    }

    /**
     * 缩略图：先看本地缓存 → 再走服务端 thumb 直链（约 2.5KB）→ 都没有就用已下载的原图。
     * 全部失败返回 null（格子里保持空底，不影响其他图）。
     */
    fun thumb(c: Context, item: Item, reqW: Int, reqH: Int): Bitmap? {
        val tf = thumbFile(c, item.name)
        if (tf.isFile && tf.length() > 0) decodeFile(tf, reqW, reqH, true)?.let { return it }
        if (item.thumb.isNotBlank()) {
            val tmp = File(tf.parentFile, tf.name + ".part")
            val ok = runCatching {
                if (tmp.exists()) tmp.delete()
                httpToFile(item.thumb, tmp)
                val bmp = decodeFile(tmp, reqW, reqH, true)
                if (bmp != null) {
                    if (tf.exists()) tf.delete()
                    if (!tmp.renameTo(tf)) {
                        tmp.copyTo(tf, overwrite = true)
                        tmp.delete()
                    }
                } else {
                    tmp.delete()
                }
                bmp
            }.getOrNull()
            if (ok != null) return ok
        }
        val full = fullFile(c, item.name)
        if (full.isFile && full.length() > 0) return decodeFile(full, reqW, reqH, true)
        return null
    }

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

    private fun decodeFile(f: File, reqW: Int, reqH: Int, thumb: Boolean): Bitmap? = runCatching {
        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bound)
        if (bound.outWidth <= 0) return null
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample(bound.outWidth, bound.outHeight, reqW, reqH)
            if (thumb) inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(f.absolutePath, opt)
    }.getOrNull()
}
