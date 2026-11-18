package com.h6n.launcher.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出诊断日志（6.0.102）。
 *
 * 车机上既没有 logcat 也没有控制台，出问题时只能让桌面自己把现场打成一个 txt，
 * 用户插 U 盘拷到电脑上发过来。这个类就负责拼那份文件：
 *
 * ```
 * 基本信息  版本 / 机型 / Android 版本 / 屏幕 / 运行时长 / pid / ADB / root
 * 权限      9 项逐条「已激活 / 未激活」（与权限面板同一口径 PermGuide.granted）
 * 车控      ICarInfo Binder 是否连上、空调内外循环、当前整车信号一帧
 * 配置      全部 SharedPreferences（VIN 这类打码）
 * 壳日志    getExternalFilesDir/shell/shell.log 尾部（加壳 / 身份替换失败时看它）
 * 进程日志  H6Log 落盘的 h6n.log 尾部
 * logcat    尽力抓一份（多数车机只给本进程，拿不到就写原因）
 * ```
 *
 * 落盘优先级：**U 盘根目录 → /sdcard/Download → 应用外部目录**。
 * 插着 U 盘时直接写在 U 盘根，拔下来插电脑就能打开，最省事。
 *
 * 所有方法都是同步阻塞的（要读文件、要等 logcat），**调用方必须放子线程**。
 */
object LogExport {

    private const val TAG = "H6LogExport"

    /** 常见 U 盘挂载点；命中第一个可写的就写那儿。 */
    private val USB_ROOTS = listOf(
        "/storage/udisk", "/storage/udisk1", "/storage/udisk2",
        "/storage/usb1", "/storage/usbotg", "/storage/usbdisk", "/storage/usb_disk",
        "/mnt/udisk", "/mnt/usbhost1", "/mnt/usb_storage", "/mnt/media_rw/udisk"
    )

    /** 日志尾部保留的行数：够看清一次故障，又不至于文件太大发不动。 */
    private const val TAIL_LINES = 4000

    // ------------------------------------------------------------------ 对外

    /**
     * 生成一份诊断日志文件并返回它。失败抛异常，由调用方提示。
     *
     * @param note 用户填的一句备注（可选），写在文件头，方便对上「哪个问题」
     */
    fun export(c: Context, note: String = ""): File {
        H6Log.mark("开始导出诊断日志")
        H6Log.flushNow()

        val text = build(c, note)

        val name = "H6N日志_" + versionName(c) + "_" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

        var last: Throwable? = null
        for (d in targets(c)) {
            val r = runCatching {
                if (!d.exists()) d.mkdirs()
                val f = File(d, name)
                f.writeText(text, Charsets.UTF_8)
                f
            }
            r.onSuccess {
                if (it.length() > 0) {
                    H6Log.mark("诊断日志已导出：${it.absolutePath}（${it.length()} B）")
                    return it
                }
            }
            r.onFailure { last = it }
        }
        throw last ?: IllegalStateException("没有可写的目录（U 盘、Download、应用目录都写不进去）")
    }

    /** 界面展示用：把 /storage/emulated/0 缩成 /sdcard。 */
    fun shown(f: File): String {
        val p = f.absolutePath
        val pre = runCatching { "/storage/emulated/0" }.getOrDefault("")
        return if (p.startsWith(pre)) "/sdcard" + p.substring(pre.length) else p
    }

    // ------------------------------------------------------------------ 落盘位置

    private fun targets(c: Context): List<File> = buildList {
        // 1) U 盘根：用户拔下来插电脑就能看
        for (p in USB_ROOTS) {
            val d = File(p)
            if (d.isDirectory && d.canWrite()) add(d)
        }
        // 2) 公共下载目录：车机上「文件管理」与 adb pull 都能拿到
        runCatching {
            val dl = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (dl != null) add(dl)
        }
        // 3) 应用自己的外部目录（前两个都不可写时的兜底，永远可写）
        runCatching { c.getExternalFilesDir("logs")?.let { add(it) } }
        runCatching { File(c.filesDir, "logs").let { add(it) } }
    }

    // ------------------------------------------------------------------ 内容

    private fun build(c: Context, note: String): String {
        val sb = StringBuilder(32 * 1024)
        fun line(s: String = "") = sb.append(s).append('\n')

        line("================ 哈弗国潮桌面 诊断日志 ================")
        line("导出时间：${stamp()}")
        if (note.isNotBlank()) line("用户备注：$note")
        line()

        // ---- 基本信息
        line("---- 基本信息 ----")
        val pkg = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        line("版本：${pkg?.versionName ?: "?"}  (versionCode ${pkg?.versionCode ?: "?"})")
        line("包名：${c.packageName}")
        line("设备：${Build.MANUFACTURER} ${Build.MODEL}  (${Build.DEVICE})")
        line("系统：Android ${Build.VERSION.RELEASE}  API ${Build.VERSION.SDK_INT}  ROM ${Build.DISPLAY}")
        line("ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching {
            val dm = c.resources.displayMetrics
            line("屏幕：${dm.widthPixels}×${dm.heightPixels}  density=${dm.density}  densityDpi=${dm.densityDpi}")
        }
        val up = SystemClock.elapsedRealtime()
        line("开机时长：${up / 3600000}h${up % 3600000 / 60000}m   pid=${Process.myPid()}")
        line("应用运行时长：${runCatching { PerfStats.uptime() }.getOrDefault("?")}")
        line("ADB：${AdbGrant.summary(c)}")
        line("root：${if (runCatching { File("/system/xbin/su").exists() || File("/system/bin/su").exists() }.getOrDefault(false)) "有 su" else "无 su"}")
        line("日志文件：${H6Log.file()?.absolutePath ?: "未初始化"}  ${H6Log.humanBytes(H6Log.totalBytes())}")
        line()

        // ---- 权限（与权限面板同一口径，逐项现算）
        line("---- 权限 ----")
        for (p in PermGuide.ALL) {
            val on = PermGuide.granted(c, p.id)
            val tag = when {
                on -> "已激活"
                p.optional -> "未激活（可跳过）"
                else -> "未激活"
            }
            line("  [${p.id}] ${p.title} = $tag")
        }
        line("  → 汇总：${PermGuide.summary(c)}")
        line()

        // ---- 车控 / 车况
        line("---- 车控 / 车况 ----")
        line("ICarInfo Binder：${if (runCatching { CarBinderSource.canControl() }.getOrDefault(false)) "已连接" else "未连接"}")
        line("空调内外循环：${runCatching { Hvac.stateText(c) }.getOrElse { "读取失败 ${it.message}" }}")
        line("空调通道：${runCatching { Hvac.channelText(c) }.getOrElse { "读取失败" }}")
        line("空调读数：${runCatching { CarBinderSource.hvacDebug() }.getOrElse { "读取失败 ${it.message}" }}")
        line("U 盘音乐封面：${runCatching { LocalPlayer.artText() }.getOrElse { "读取失败 ${it.message}" }}")
        line("锁车读数：${runCatching { CarBinderSource.lockDebug() }.getOrElse { "读取失败 ${it.message}" }}")
        line("熄火自动关窗：${runCatching { AutoClose.lastLog() }.getOrElse { "读取失败 ${it.message}" }}")
        line("当前信号：${runCatching { SignalHub.current().toString() }.getOrElse { "读取失败 ${it.message}" }}")
        line("媒体监听：${runCatching { if (MediaWatcher.isGranted(c)) "通知使用权已给" else "通知使用权未给" }.getOrElse { "?" }}")
        line()

        // ---- 配置（SharedPreferences 全量，敏感值打码）
        line("---- 配置（SharedPreferences）----")
        line(prefsDump(c))
        line()

        // ---- 壳日志
        line("---- 加壳日志（shell.log）----")
        line(shellLogTail(c))
        line()

        // ---- 进程日志
        line("---- 运行日志（h6n.log 最近 $TAIL_LINES 行）----")
        line(runCatching { H6Log.tail(TAIL_LINES) }.getOrDefault("").ifBlank { "（暂无）" })
        line()

        // ---- logcat
        line("---- logcat（尽力抓取，多数车机只给本进程）----")
        line(logcat(Process.myPid()))
        line()
        line("================ 结束 ================")
        return sb.toString()
    }

    private fun prefsDump(c: Context): String {
        val d = runCatching { File(c.applicationInfo.dataDir, "shared_prefs") }.getOrNull()
            ?: return "（读不到 shared_prefs 目录）"
        val files = runCatching { d.listFiles() }.getOrNull()?.filter {
            it.isFile && it.name.endsWith(".xml")
        }?.sortedBy { it.name } ?: return "（shared_prefs 为空）"
        if (files.isEmpty()) return "（shared_prefs 为空）"

        val sb = StringBuilder()
        for (f in files) {
            val name = f.name.removeSuffix(".xml")
            sb.append("[$name]").append('\n')
            val sp = runCatching { c.getSharedPreferences(name, Context.MODE_PRIVATE) }.getOrNull()
            if (sp == null) {
                sb.append("  （读取失败）").append('\n')
                continue
            }
            val all = runCatching { sp.all }.getOrNull().orEmpty()
            if (all.isEmpty()) {
                sb.append("  （空）").append('\n')
                continue
            }
            for (k in all.keys.sorted()) {
                sb.append("  ").append(k).append(" = ").append(mask(k, all[k])).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** 车架号 / 授权码这类不往外带的键，只留长度 —— 排查用不到具体值。 */
    private fun mask(key: String, v: Any?): String {
        val k = key.lowercase(Locale.US)
        val sensitive = k.contains("vin") || k.contains("auth") || k.contains("code") ||
            k.contains("license") || k.contains("token")
        val s = v?.toString() ?: "null"
        return if (sensitive && s.length > 4) s.take(4) + "****" else s
    }

    private fun shellLogTail(c: Context): String {
        val f = runCatching { File(c.getExternalFilesDir("shell"), "shell.log") }.getOrNull()
        if (f == null || !f.exists()) return "（没有 shell.log：可能未加壳，或壳没写过日志）"
        return runCatching {
            val lines = f.readLines()
            val from = maxOf(0, lines.size - 600)
            lines.subList(from, lines.size).joinToString("\n")
        }.getOrElse { "读取失败：${it.message}" }
    }

    /**
     * 抓一份 logcat。
     *
     * Android 4.1 起普通应用能读自己的日志，但仍受 ROM 限制，所以这里：
     * ① 限定 `--pid`，不给系统日志添负担；② 独立线程 + 2.5 秒硬超时，
     * 拿不到就如实写原因，绝不让导出卡住。
     */
    private fun logcat(pid: Int): String {
        val sb = StringBuilder()
        val worker = Thread {
            runCatching {
                val p = ProcessBuilder(
                    "logcat", "-d", "-v", "time", "-t", "3000", "--pid=$pid"
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { r ->
                    // 只留最后 3000 行、最多 512KB，避免文件过大
                    var n = 0
                    while (true) {
                        val s = r.readLine() ?: break
                        if (++n > 3000 || sb.length > 512 * 1024) break
                        sb.append(s).append('\n')
                    }
                }
                runCatching { p.destroy() }
            }.onFailure { sb.append("（抓取失败：${it.javaClass.simpleName} ${it.message}）") }
        }
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(2500L) }
        return if (sb.isBlank()) "（这台车机不允许应用读 logcat，已跳过；上面 h6n.log 里是同样的内容）"
        else sb.toString().trimEnd()
    }

    private fun versionName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown").replace("哈弗国潮桌面", "")

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
