package com.h6n.launcher.core

import android.content.Context
import com.h6n.launcher.core.H6Log as Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 应用商店 —— 实时解析 OpenList / AList 分享目录里的 APK（6.0.90）。
 *
 * ## 为什么不内置清单
 * 用户后期会往那个文件夹里加 / 换 APK，商店每次打开都**重新解析链接**，
 * 不缓存、不写死包名。这样文件夹改了，商店里立刻就变。
 *
 * ## 两个接口（都无需登录，分享 token 走公开只读）
 * 1. 列表：`POST /api/fs/list`，body `{"path":"/@s/<token>/<dir>","password":"","page":1,"per_page":0,"refresh":false}`
 *    → `data.content[]` 每项取 `name` / `size` / `is_dir`，只留 `.apk`。
 * 2. 下载：拼直链 `<base>/sd/<token>/<dir>/<urlencode(name)>`，GET 就是 APK 字节流
 *    （实测 `Content-Type: application/vnd.android.package-archive`，与 version.json 同一台机器）。
 *    以前还要先 `POST /api/fs/get` 换 `raw_url`，现在拼出来的一样能用，少一跳。
 *
 * 地址可通过 [setUrl] 改（设置页里填），默认即用户给的分享链接；
 * [parseShareUrl] 会把「分享页 URL」自动拆成 base + 列表用的完整 path。
 */
object AppStore {

    private const val TAG = "H6AppStore"
    private const val SP = "h6_appstore"
    private const val K_URL = "url"
    private const val DEF_URL =
        "https://example.com/@s/YOUR-SHARE-TOKEN/%E5%BA%94%E7%94%A8%E5%95%86%E5%BA%97"

    @Volatile private var app: Context? = null

    fun attach(ctx: Context) {
        if (app == null) {
            app = ctx.applicationContext
            // 6.0.91：清掉旧版误下到内部私有目录的包。那个目录外部安装器读不到，
            // 留着只会在改目录前被 cached() 误命中、又拉起安装报 packageInfo is null。
            Thread(Runnable {
                runCatching {
                    java.io.File(app!!.filesDir, "store").listFiles()?.forEach {
                        runCatching { it.delete() }
                    }
                }
            }, "h6-appstore-migrate").start()
        }
    }

    private fun sp() = app!!.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun url(): String = sp().getString(K_URL, DEF_URL) ?: DEF_URL
    fun setUrl(u: String) = sp().edit().putString(K_URL, u.trim()).apply()
    fun isDefault(): Boolean = url() == DEF_URL

    data class App(
        val name: String,
        val size: Long,
        val path: String,       // 列表接口里的完整 path（下载时拼 /sd/ 用）
        val modified: String = ""
    ) {
        /** 文件名去掉 .apk，做显示名。 */
        val label: String get() = name.removeSuffix(".apk").removeSuffix(".APK")
    }

    /**
     * 把分享 URL 拆成 (baseOrigin, 列表 path)。
     * - 输入 `http://host/@s/token/应用商店`（可能已 URL 编码）→ base=`http://host`，path=`/@s/token/应用商店`
     * - 输入已经是 path → 原样返回，base 取默认机器。
     */
    data class Location(val base: String, val path: String)

    fun parseShareUrl(raw: String): Location {
        val u = raw.trim()
        val full = if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "https://example.com$u"
        val schemeEnd = full.indexOf("://") + 3
        val slash = full.indexOf('/', schemeEnd)
        val base = if (slash > 0) full.substring(0, slash) else full
        val path = if (slash > 0) full.substring(slash) else "/"
        return Location(base, decode(path))
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    /** 列表用的 path：`/@s/<token>/应用商店`（解码后的中文，交给 JSONObject 编码）。 */
    private fun listPath(): String = parseShareUrl(url()).path

    /** 下载直链：`base + /sd/<token>/应用商店/<编码文件名>`。 */
    fun downloadUrl(app: App): String {
        val loc = parseShareUrl(url())
        // 列表 path 形如 /@s/<token>/<dir>（目录本身）；直链把 /@s/ 换成 /sd/ 后直接接文件名
        val sdDir = loc.path.replaceFirst("/@s/", "/sd/").trimEnd('/')
        val enc = URLEncoder.encode(app.name, "UTF-8").replace("+", "%20")
        return loc.base + sdDir + "/" + enc
    }

    /** 实时拉列表：网络在后台线程，异常往上抛由调用方显示。 */
    fun load(): List<App> {
        val loc = parseShareUrl(url())
        val body = JSONObject().apply {
            put("path", loc.path)
            put("password", "")
            put("page", 1)
            put("per_page", 0)
            put("refresh", false)
        }.toString()
        val conn = post(loc.base + "/api/fs/list", body)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            if (root.optInt("code", -1) != 200) {
                throw IllegalStateException(root.optString("message", "接口返回异常"))
            }
            val arr = root.optJSONObject("data")?.optJSONArray("content")
                ?: throw IllegalStateException("列表为空或格式不对")
            val out = ArrayList<App>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("is_dir", false)) continue
                val name = o.optString("name", "")
                if (!name.endsWith(".apk", true)) continue
                out.add(
                    App(
                        name = name,
                        size = o.optLong("size", 0),
                        path = loc.path.trimEnd('/') + "/" + name,
                        modified = o.optString("modified", "")
                    )
                )
            }
            // 名称排序，和文件管理器一致；同名大小写不敏感
            out.sortBy { it.name.lowercase() }
            Log.i(TAG, "列出 ${out.size} 个 APK @ ${loc.path}")
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun post(u: String, body: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.setRequestProperty("User-Agent", "HavalGuochao-Launcher")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c
    }

    /**
     * 下载目录：应用的**外部** files 目录 `Android/data/<包名>/files/store`。
     *
     * 6.0.91 修复「解析软件包时出现问题 / packageInfo is null」：之前用 [Context.getFilesDir]
     * （内部私有目录），外部安装器（应用管家 / 系统安装界面）是另一个进程，读不到这个
     * 0700 私有目录，`Uri.fromFile` 传过去的 file:// 对它无效 → 解析到空包。
     * 改用外部 files 目录后，安装器能直接读到文件（和在线更新 `getExternalFilesDir("update")`
     * 同一策略，那条通道车机实测能装）。无需存储权限，卸载应用时系统一并清理。
     */
    fun apkDir(): String {
        val base = app!!.getExternalFilesDir("store")
            ?: java.io.File(app!!.filesDir, "store")
        if (!base.exists()) base.mkdirs()
        return base.absolutePath
    }

    /** 已下载（且大小对得上）就返回缓存文件，否则 null。 */
    fun cached(app_: App): java.io.File? {
        val f = java.io.File(apkDir(), safeName(app_.name))
        if (!f.isFile || f.length() < 1024) return null
        if (app_.size > 0 && kotlin.math.abs(f.length() - app_.size) > 1024) return null
        return f
    }

    /** 文件名里可能有中文/括号，落到文件系统只保留安全字符。 */
    private fun safeName(name: String): String {
        val base = name.removeSuffix(".apk").removeSuffix(".APK")
        val cleaned = base.map { if (it.isLetterOrDigit() || it in "-_.（）() ") it else '_' }.joinToString("")
        return (cleaned.ifBlank { "app" }) + ".apk"
    }

    /**
     * 后台下载，进度/结果切回主线程回调。
     * @param onProgress 0..100
     * @param onDone 成功返回本地文件，失败返回 null（错误信息在 [lastError]）
     */
    fun download(
        app_: App,
        onProgress: (Int) -> Unit,
        onDone: (java.io.File?) -> Unit
    ) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            var tmp: java.io.File? = null
            try {
                cached(app_)?.let { f ->
                    mainHandler.post { onProgress(100); onDone(f) }
                    return@Thread
                }
                val dir = java.io.File(apkDir())
                val name = safeName(app_.name)
                tmp = java.io.File(dir, name + ".part")
                val c = URL(downloadUrl(app_)).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "HavalGuochao-Launcher")
                c.instanceFollowRedirects = true
                try {
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    val total = c.contentLength.toLong()
                    c.inputStream.use { input ->
                        java.io.FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(65536)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        mainHandler.post { onProgress(pct) }
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (app_.size > 0 && kotlin.math.abs(tmp.length() - app_.size) > 1024) {
                    throw IllegalStateException("文件大小对不上，可能没下完整")
                }
                val final = java.io.File(dir, name)
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) throw IllegalStateException("保存失败")
                Log.i(TAG, "下载完成 ${app_.name} → ${final.length()} B")
                mainHandler.post { onProgress(100); onDone(final) }
            } catch (t: Throwable) {
                tmp?.delete()
                Log.w(TAG, "下载失败 ${app_.name} → ${brief(t)}")
                lastError = brief(t)
                mainHandler.post { onDone(null) }
            }
        }, "h6-appstore-dl").start()
    }

    @Volatile var lastError: String = ""

    fun brief(t: Throwable): String = (t.message ?: t.javaClass.simpleName).take(80)
}
