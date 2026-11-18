package com.h6n.launcher.core

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.StrictMode
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * 在线更新 —— 移植自未重写版 `UpdateChecker`（Java，1389 行）。
 *
 * ## 服务端 version.json（与旧版完全兼容）
 * `{ "versionCode":376, "versionName":"6.0.0-alpha21", "apkUrl":"xxx.apk",
 *    "size":5781000, "sha256":"…", "notes":["…"] }`
 * `apkUrl` 可为完整地址 / 站点根路径 / 与 version.json 同目录的文件名。
 *
 * ## 安装顺序（照旧版，车机上真能装进去的路优先）
 * 1. 车机「应用管家」`com.yunpan.appmanage` 的显式安装界面
 * 2. 系统 ACTION_VIEW 安装界面
 * 3. PackageInstaller 会话（不依赖 FileProvider）
 *
 * 未开「安装未知应用」权限先跳设置页并记下待装版本号，回来 `resumeInstall()` 续装。
 * 装完的安装包由 [purgeInstalledApks] 删除：会话安装成功时立即删，
 * 交给应用管家 / 系统安装界面时进程会被覆盖安装杀掉，新进程启动与回桌面时按已安装版本号补删。
 */
object UpdateManager {

    private const val TAG = "H6Update"
    private const val SP = "h6_update"
    private const val K_URL = "url"
    private const val K_AUTO = "auto"
    private const val K_USER = "userSet"
    private const val K_P_CODE = "pendingCode"
    private const val K_P_NAME = "pendingName"
    private const val K_P_NOTE = "pendingNote"
    private const val K_WAIT_CODE = "installWaitCode"
    private const val K_INSTALL_MSG = "installMsg"
    private const val K_CLEAN_CODE = "cleanupCode"
    private const val K_CLEAN_NOTE = "cleanupNote"
    private const val K_INSTALLING_CODE = "installingCode"
    private const val DEF_URL = "https://example.com/sd/YOUR-SHARE-TOKEN/version.json"
    private const val FRONT_GAP_MS = 30L * 1000L

    const val IDLE = 0
    const val CHECKING = 1
    const val LATEST = 2
    const val FOUND = 3
    const val FAILED = 4
    const val DOWNLOADING = 5
    const val READY = 6
    const val NEED_PERM = "NEED_PERMISSION"

    private const val MGR_PKG = "com.yunpan.appmanage"
    private const val MGR_INSTALLER = "com.yunpan.appmanage.ui.ActivityInstaller"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var app: Context? = null
    @Volatile private var attached = false
    @Volatile private var defUrl = DEF_URL
    @Volatile private var busy = false
    @Volatile private var lastFrontMs = 0L

    data class Ver(
        val code: Int = 0,
        val name: String = "",
        val apk: String = "",
        val size: Long = 0,
        val sha: String = "",
        val notes: List<String> = emptyList()
    )

    /** 面板回调，一律切回主线程。 */
    fun interface Cb {
        fun on(state: Int, text: String, ver: Ver?)
    }

    private fun ctx(): Context = app
        ?: throw IllegalStateException("UpdateManager 未 attach")

    private fun sp() = ctx().getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun attach(ctx: Context) {
        if (attached) return
        attached = true
        app = ctx.applicationContext
        defUrl = runCatching {
            val txt = ctx.assets.open("ui_config.json").bufferedReader().use { it.readText() }
            JSONObject(txt).optJSONObject("update")?.optString("url", DEF_URL) ?: DEF_URL
        }.getOrDefault(DEF_URL).ifBlank { DEF_URL }
        registerResultReceiver()
        Thread {
            // 覆盖装完系统会重拉桌面进程，这一趟就把刚用掉的安装包删掉
            runCatching { purgeInstalledApks() }
            runCatching { Storage.cleanOldApks() }
        }.start()
    }

    /** MainActivity.onResume 兜底：版本号比上次清理时新，再补删一次（30 秒节流）。 */
    fun onFront(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFrontMs < FRONT_GAP_MS) return
        lastFrontMs = now
        val cur = runCatching { curCode() }.getOrDefault(0)
        if (cur <= 0 || cur <= sp().getInt(K_CLEAN_CODE, 0)) return
        Thread { runCatching { purgeInstalledApks() } }.start()
    }

    /** 供设置页显示的上次清理结果，只读 SP，不扫盘。 */
    fun cleanNote(): String = sp().getString(K_CLEAN_NOTE, "尚无清理记录") ?: "尚无清理记录"

    // ---------------- 配置 ----------------

    fun baseUrl(): String = sp().getString(K_URL, defUrl) ?: defUrl
    fun setBaseUrl(u: String) {
        sp().edit().putString(K_URL, u.trim()).putBoolean(K_USER, true).apply()
    }

    fun auto(): Boolean = sp().getBoolean(K_AUTO, true)
    fun setAuto(v: Boolean) = sp().edit().putBoolean(K_AUTO, v).apply()

    fun curCode(): Int = runCatching {
        @Suppress("DEPRECATION")
        ctx().packageManager.getPackageInfo(ctx().packageName, 0).versionCode
    }.getOrDefault(0)

    fun curName(): String = runCatching {
        ctx().packageManager.getPackageInfo(ctx().packageName, 0).versionName ?: "-"
    }.getOrDefault("-")

    // ---------------- 待更新缓存 ----------------

    fun savePending(v: Ver) {
        sp().edit().putInt(K_P_CODE, v.code).putString(K_P_NAME, v.name)
            .putString(K_P_NOTE, v.notes.firstOrNull().orEmpty()).apply()
    }

    fun clearPending() = sp().edit().putInt(K_P_CODE, 0)
        .remove(K_P_NAME).remove(K_P_NOTE).apply()

    fun pendingCode(): Int = sp().getInt(K_P_CODE, 0)
    fun pendingName(): String = sp().getString(K_P_NAME, "") ?: ""
    fun pendingNote(): String = sp().getString(K_P_NOTE, "") ?: ""

    /**
     * 从 SP 还原「待更新版本」，没有（或已装上）返回 null。
     *
     * 状态栏徽标和「回前台补弹」都靠它 —— 自动检查的结果必须能跨 Activity 重建、
     * 跨进程重启留存，否则退出设置页回来提示就没了。
     * 只带 versionCode / 版本名 / 首条说明：完整 notes 在检查那次才拿得到。
     */
    fun pendingVer(): Ver? {
        val c = runCatching { pendingCode() }.getOrDefault(0)
        if (c <= 0) return null
        if (c <= runCatching { curCode() }.getOrDefault(0)) return null
        val note = pendingNote()
        return Ver(
            code = c,
            name = pendingName(),
            notes = if (note.isBlank()) emptyList() else listOf(note)
        )
    }

    fun installResult(): String = sp().getString(K_INSTALL_MSG, "") ?: ""
    fun clearInstallResult() = sp().edit().remove(K_INSTALL_MSG).apply()

    // ---------------- 检查 ----------------

    /**
     * 冷启动 5 秒后的自动检查，**结果会回调出去**。
     *
     * 旧版（未重写版）这条路径是有提醒的：`UpdateChecker.postVisual()` 会刷状态栏常驻
     * 徽标（`UpdateBadge`）并在桌面前台时弹窗（`UpdatePrompt`）。重写时这里只剩
     * `check(act, silent = true, cb = null)` —— 而 `fire()` 在 cb 为空时直接 return，
     * 检查结果没有任何人接收，于是"有更新也不弹窗、状态栏也没提示"。
     *
     * 现在把状态回传给调用方（桌面），由界面层决定弹窗还是只留徽标：
     * 后台不弹（绝不盖在别的应用上），回前台再补一次。
     */
    fun onStart(act: Activity, onResult: ((Int, Ver?) -> Unit)? = null) {
        if (!auto()) return
        bootCheck(WeakReference(act), onResult, allowRetry = true, delayMs = 5000L)
    }

    /** 启动那次失败就排一次 10 分钟后的补查；补查再失败就不排了（照旧版）。 */
    private fun bootCheck(
        ref: WeakReference<Activity>,
        onResult: ((Int, Ver?) -> Unit)?,
        allowRetry: Boolean,
        delayMs: Long
    ) {
        main.postDelayed({
            val act = ref.get()
            if (act == null || act.isFinishing) return@postDelayed
            check(act, silent = true, cb = Cb { st, _, v ->
                onResult?.invoke(st, v)
                if (st == FAILED && allowRetry) {
                    Log.w(TAG, "启动检查更新失败，10 分钟后补查一次")
                    bootCheck(ref, onResult, allowRetry = false, delayMs = 10 * 60 * 1000L)
                }
            })
        }, delayMs)
    }

    fun check(act: Activity, silent: Boolean = false, cb: Cb?) {
        if (busy) return
        busy = true
        if (!silent) fire(cb, CHECKING, "正在检查更新…", null)
        val url = baseUrl()
        Thread({
            try {
                val v = parse(get(url, 256 * 1024))
                if (v.code <= 0 || v.apk.isBlank()) {
                    throw IllegalArgumentException("version.json 缺少 versionCode 或 apkUrl")
                }
                if (v.code > curCode()) {
                    savePending(v)
                    fire(cb, FOUND, "发现新版本 v${v.name}（${v.code}）", v)
                } else {
                    // 服务器版本已经装上了：顺手清掉下载目录里躺着的旧安装包
                    if (pendingCode() > 0) {
                        clearPending()
                        Thread { runCatching { purgeInstalledApks() } }.start()
                    }
                    fire(cb, LATEST, "已是最新版本 ${curName()}", null)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "检查更新失败 $url → ${brief(t)}")
                fire(cb, FAILED, "检查失败：${brief(t)}", null)
            } finally {
                busy = false
            }
        }, "h6-update-check").start()
    }

    private fun parse(txt: String): Ver = try {
        val o = JSONObject(txt)
        val arr = o.optJSONArray("notes")
        val notes = if (arr != null) {
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } else emptyList()
        Ver(
            o.optInt("versionCode", 0), o.optString("versionName", ""),
            o.optString("apkUrl", ""), o.optLong("size", 0),
            o.optString("sha256", ""), notes
        )
    } catch (e: Exception) {
        // 旧版 salvage：json 被截断在半截时，只要 versionCode / apkUrl 齐全就还能读
        Ver(
            Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(txt)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            field(txt, "versionName"), field(txt, "apkUrl"),
            Regex("\"size\"\\s*:\\s*(\\d+)").find(txt)?.groupValues?.get(1)?.toLongOrNull() ?: 0,
            field(txt, "sha256"), emptyList()
        )
    }

    private fun field(txt: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(txt)?.groupValues?.get(1) ?: ""

    /** apkUrl 补成完整地址：完整 URL / 站点根 / 同目录文件名。 */
    fun abs(base: String, apk: String): String {
        if (apk.startsWith("http://", true) || apk.startsWith("https://", true)) return apk
        val b = base.substringBeforeLast('?')
        if (apk.startsWith("/")) {
            val u = URL(b)
            return "${u.protocol}://${u.authority}$apk"
        }
        return b.substringBeforeLast('/') + "/" + apk.removePrefix("./")
    }

    // ---------------- 下载 ----------------

    fun apkFile(code: Int): File = File(apkDir(), "h6-$code.apk")

    private fun apkDir(): File {
        val d = ctx().getExternalFilesDir("update") ?: File(ctx().filesDir, "update")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun hasCachedApk(v: Ver): Boolean {
        val f = apkFile(v.code)
        if (!f.isFile || f.length() < 1024) return false
        if (v.size > 0 && abs(f.length() - v.size) > 1024) return false
        return v.sha.length < 32 || v.sha.equals(sha256(f), true)
    }

    fun download(act: Activity, v: Ver, cb: Cb?) {
        if (v.apk.isBlank()) { fire(cb, FAILED, "服务器没给下载地址", v); return }
        if (hasCachedApk(v)) { fire(cb, READY, "安装包已下载，可以直接安装", v); return }
        val url = abs(baseUrl(), v.apk)
        Thread({
            var tmp: File? = null
            try {
                tmp = apkFile(v.code)
                val c = open(url)
                val rc = c.responseCode
                if (rc != 200) {
                    c.disconnect()
                    throw IllegalStateException("下载地址打不开（HTTP $rc）")
                }
                val total = c.contentLength.toLong()
                c.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(65536)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    fire(cb, DOWNLOADING, "下载中 $pct%", v)
                                }
                            }
                        }
                        out.flush()
                    }
                }
                c.disconnect()
                if (v.size > 0 && abs(tmp.length() - v.size) > 1024) {
                    throw IllegalStateException("文件大小对不上")
                }
                if (v.sha.length >= 32 && !v.sha.equals(sha256(tmp), true)) {
                    throw IllegalStateException("校验不通过，可能下载不完整")
                }
                fire(cb, READY, "下载完成 · ${Storage.fmt(tmp.length())}", v)
            } catch (t: Throwable) {
                Log.w(TAG, "下载失败 → ${brief(t)}")
                tmp?.delete()
                fire(cb, FAILED, "下载失败：${brief(t)}", v)
            }
        }, "h6-update-dl").start()
    }

    // ---------------- 安装 ----------------

    /** 从「安装未知应用」设置页回来后续装。 */
    fun resumeInstall(act: Activity): String {
        val code = sp().getInt(K_WAIT_CODE, 0)
        return if (code > 0) install(act, code) else ""
    }

    fun install(act: Activity, code: Int): String {
        val apk = apkFile(code)
        if (!apk.isFile || apk.length() < 1024) return "安装包不在了，请重新下载"
        var r = openInstaller(act, apk)
        if (r == null) r = installBySession(act, code, apk)
        if (r != null && r != NEED_PERM) {
            sp().edit().remove(K_WAIT_CODE).putInt(K_INSTALLING_CODE, code).apply()
        }
        clearInstallResult()
        val base = r ?: "这台车机上找不到能装 APK 的程序，请用应用管家手动安装"
        val suffix = if (r == NEED_PERM) "" else "\n装完会自动删除这个下载的安装包"
        return base + suffix
    }

    /**
     * 应用商店专用：安装任意路径的 APK（不走 versionCode / 待更新缓存那套）。
     * 复用同一条已验证的安装通道（应用管家 → 系统安装界面）。
     */
    fun installFile(act: Activity, apk: File): String {
        if (!apk.isFile || apk.length() < 1024) return "安装包不在了，请重新下载"
        clearInstallResult()
        val r = openInstaller(act, apk)
            ?: return "这台车机上找不到能装 APK 的程序，请用应用管家手动安装"
        return r
    }

    private fun openInstaller(act: Activity, apk: File): String? {
        if (pkgInstalled(MGR_PKG)) {
            startInstaller(act, apk, MGR_PKG, MGR_INSTALLER)?.let { return it }
            startInstaller(act, apk, MGR_PKG, null)?.let { return it }
        }
        return startInstaller(act, apk, null, null)
    }

    private fun startInstaller(act: Activity, apk: File, pkg: String?, cls: String?): String? = try {
        // Android 7+ 跨应用传 file:// 会被 StrictMode 拦，先放宽 VmPolicy
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (cls != null) setClassName(pkg!!, cls) else if (pkg != null) setPackage(pkg)
        }
        act.startActivity(i)
        if (MGR_PKG == pkg) "已交给应用管家，按它的界面点「安装」（装完桌面会自动重启）"
        else "已交给系统安装，请在安装界面点「安装」"
    } catch (t: Throwable) {
        Log.w(TAG, "拉起安装界面失败 → ${brief(t)}")
        null
    }

    private fun installBySession(act: Activity, code: Int, apk: File): String = try {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            sp().edit().putInt(K_WAIT_CODE, code).apply()
            runCatching {
                act.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.packageName)
                    )
                )
            }
            NEED_PERM
        } else {
            val pi = act.packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = pi.createSession(p)
            val s = pi.openSession(id)
            FileInputStream(apk).use { input ->
                s.openWrite("base.apk", 0L, apk.length()).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    s.fsync(out)
                }
            }
            val cb = Intent(ctx().packageName + ".INSTALL_DONE").setPackage(ctx().packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pend = PendingIntent.getBroadcast(ctx(), id, cb, flags)
            s.commit(pend.intentSender)
            "已交给系统安装，请在弹出的确认框点「安装」"
        }
    } catch (t: Throwable) {
        "装不了：${brief(t)}，可以打开应用管家手动安装"
    }

    private fun pkgInstalled(pkg: String): Boolean = runCatching {
        ctx().packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    // ---------------- 安装包清理 ----------------

    /**
     * 删掉「已经装上的」下载安装包，与自动清理开关无关。
     *
     * 判据是文件名里的版本号：h6-&lt;N&gt;.apk 且 N &lt;= 当前 versionCode 说明
     * 这包装过了（或比现在装的还旧），直接删；N 大于当前版本的是刚下好还没装
     * 的新包，留着以免重下。下载中途的 .part / .tmp 一律清。
     * 只认程序自己命名的 h6-N.apk，用户放进 U 盘的安装包不碰。
     */
    fun purgeInstalledApks(): Long = sweep(includeNew = false)

    /** 手动清理：连还没安装的新包也一起删（设置页按钮）。 */
    fun cleanAllDownloads(): Long = sweep(includeNew = true)

    private fun sweep(includeNew: Boolean): Long {
        val cur = curCode()
        if (cur <= 0) return 0L  // 拿不到版本号时宁可不删，避免误删待装新包
        var freed = 0L
        var n = 0
        for (d in apkDirs()) {
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in kids) {
                if (!f.isFile) continue
                val name = f.name.lowercase(Locale.ROOT)
                val ours = name.startsWith("h6-") && (
                    name.endsWith(".apk") || name.endsWith(".apk.part") ||
                        name.endsWith(".apk.tmp") || name.endsWith(".apk.download")
                    )
                if (!ours) continue
                val code = apkCode(name)
                if (!includeNew && cur > 0 && code != null && code > cur) continue
                val len = f.length()
                if (f.delete()) { freed += len; n++ }
                Log.i(TAG, "删除安装包 ${f.name}（${fmtSize(len)}）")
            }
        }
        // 装上以后这些状态就没用了，避免「回来续装」把一个已经删掉的包再拉一次
        val e = sp().edit()
        if (pendingCode() in 1..cur) e.putInt(K_P_CODE, 0).remove(K_P_NAME).remove(K_P_NOTE)
        if (sp().getInt(K_WAIT_CODE, 0) in 1..cur) e.remove(K_WAIT_CODE)
        if (sp().getInt(K_INSTALLING_CODE, 0) in 1..cur) e.remove(K_INSTALLING_CODE)
        e.putInt(K_CLEAN_CODE, cur)
        e.putString(
            K_CLEAN_NOTE,
            "已删 $n 个安装包 · 释放 ${fmtSize(freed)} · " +
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(java.util.Date())
        )
        e.apply()
        return freed
    }

    private fun apkCode(name: String): Int? {
        if (!name.startsWith("h6-")) return null
        return name.removePrefix("h6-").substringBefore(".apk").toIntOrNull()
    }

    private fun apkDirs(): List<File> = buildList {
        runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            add(File(dl, "哈弗桌面"))
            add(File(dl, "H6N桌面"))
        }
        runCatching { ctx().getExternalFilesDir("update")?.let { add(it) } }
        add(File(ctx().filesDir, "update"))
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", b / 1048576f)
        b >= 1024L -> String.format(Locale.CHINA, "%.1f KB", b / 1024f)
        else -> "$b B"
    }

    // ---------------- 安装结果 ----------------

    private fun registerResultReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val text = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        // 会话已把字节写进系统，原 apk 不再有用，立刻删掉
                        Thread { runCatching { purgeInstalledApks() } }.start()
                        "安装成功，下载的安装包已删除"
                    }
                    PackageInstaller.STATUS_FAILURE -> "安装失败：$msg"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被拦截：$msg"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已装应用冲突：$msg"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包不兼容：$msg"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效：$msg"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "空间不足，装不上：$msg"
                    else -> "安装状态 $status $msg"
                }
                sp().edit().putString(K_INSTALL_MSG, text).apply()
            }
        }
        val f = IntentFilter(ctx().packageName + ".INSTALL_DONE")
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx().registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx().registerReceiver(r, f)
            }
        }
    }

    // ---------------- 工具 ----------------

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "HavalGuochao-Launcher")
        c.instanceFollowRedirects = true
        return c
    }

    private fun get(u: String, limit: Int): String {
        val c = open(u)
        try {
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > limit) return String(bytes, 0, limit, Charsets.UTF_8)
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun brief(t: Throwable): String =
        (t.message ?: t.javaClass.simpleName).take(80)

    private fun fire(cb: Cb?, state: Int, text: String, ver: Ver?) {
        if (cb == null) return
        main.post { runCatching { cb.on(state, text, ver) } }
    }
}
