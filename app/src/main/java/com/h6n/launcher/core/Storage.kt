package com.h6n.launcher.core

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * 内部存储检测 + **本机全路径**文件清理。
 *
 * ## 与旧版的区别（VC377）
 * 旧实现只认几类白名单目录（桌面自己的 cache、Android/data 下各包的 cache、几个固定
 * .thumbnails、sd 根目录的 log、Download 顶层碎片），等于「只清桌面缓存」。
 * 现在改成：
 *  1. [mounts] 枚举本机**所有可访问路径**：内置存储、外置 SD 卡、U 盘 / media_rw、
 *     系统可写区（/data/local/tmp、/cache、/data/log…）以及系统根；
 *  2. [browse] 按路径逐级浏览，每个直接子项递归统计真实占用与文件数；
 *  3. [deletePaths] 允许勾选并删除**任意路径**的文件 / 目录（系统关键分区只读保护）；
 *  4. [scan] 的垃圾规则不再挑目录，而是走遍所有挂载点：任意路径下的 cache 目录、
 *     .log / .tmp / .part / .apk 都进清理清单。
 *
 * ## 安全边界
 * 扫描只读、默认不勾选；删除前由面板二次确认。[locked] 保护 /proc /sys /system
 * /vendor /data/app /data/data 等系统树、挂载点根、以及桌面自身的 files/databases/
 * shared_prefs，避免把系统或配置删掉。自动清理只清 safe 组，且跳过导航类地图缓存。
 *
 * 扫盘带时间 / 文件数硬预算，超了带已扫到的量返回并标记 truncated。
 */
object Storage {

    const val LIMIT_BYTES = 1536L * 1024L * 1024L

    /** 参考车机「应用管家」垃圾清理的三级标签：放心清理 / 推荐清理 / 慎用 */
    val LEVEL_NAMES = arrayOf("放心清理", "推荐清理", "慎用")
    val LEVEL_MARKS = arrayOf("✓", "●", "⚠")

    private const val SP = "h6_storage"
    private const val K_AUTO = "auto"
    private const val K_LAST_CLEAN = "lastCleanMs"
    private const val K_LAST_TIP = "lastTipMs"

    /** 导航类：离线地图切片放在 cache 里，自动清理跳过，手动清理仍可勾选 */
    private val NAV_PKGS = setOf(
        "com.autonavi.amapauto", "com.autonavi.amap", "com.baidu.naviauto",
        "com.baidu.BaiduMap", "com.tencent.map", "com.google.android.apps.maps", "com.here.app.maps"
    )

    /** 扫描时直接跳过的系统只读 / 危险子树 */
    private val SYS_SKIP = listOf(
        "/proc", "/sys", "/dev", "/system", "/vendor", "/product", "/apex",
        "/odm", "/oem", "/firmware", "/boot", "/data/app", "/data/dalvik-cache",
        "/data/system", "/data/misc", "/data/user", "/data/user_de", "/data/data",
        "/data/credential-protected", "/data/media", "/data/property",
        "/mnt/asec", "/mnt/obb", "/mnt/secure", "/storage/self"
    )

    /** 删除时禁止触碰的路径（挂载点根另判） */
    private val SYS_LOCK = listOf(
        "/proc", "/sys", "/dev", "/system", "/vendor", "/product", "/apex",
        "/odm", "/oem", "/firmware", "/boot", "/data/app", "/data/dalvik-cache",
        "/data/system", "/data/misc", "/data/user", "/data/user_de", "/data/data",
        "/data/credential-protected", "/data/media", "/mnt/asec", "/mnt/secure"
    )

    @Volatile private var app: Context? = null
    @Volatile private var mountKeys: Set<String>? = null
    @Volatile private var lastScanInfo: String = "尚未扫描"

    fun attach(ctx: Context) {
        app = ctx.applicationContext
        mountKeys = null
    }

    private fun ctx(): Context = app ?: throw IllegalStateException("Storage 未 attach")

    private fun sp() = ctx().getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun canon(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
    private fun canon(p: String): String = canon(File(p))

    // ---------------- 容量 ----------------

    fun availBytes(): Long = runCatching {
        StatFs(Environment.getDataDirectory().path).availableBytes
    }.getOrDefault(-1L)

    fun totalBytes(): Long = runCatching {
        StatFs(Environment.getDataDirectory().path).totalBytes
    }.getOrDefault(-1L)

    fun isLow(): Boolean = availBytes().let { it in 0 until LIMIT_BYTES }

    /** 低空间的严重程度：0 正常 / 1 偏低(<3G) / 2 危险(<1.5G)。 */
    fun level(): Int = when {
        availBytes() < 0 -> 0
        availBytes() < LIMIT_BYTES -> 2
        availBytes() < LIMIT_BYTES * 2 -> 1
        else -> 0
    }

    fun gb(bytes: Long): String {
        val g = bytes / 1024.0 / 1024.0 / 1024.0
        return when {
            g >= 100 -> String.format(Locale.CHINA, "%.0fG", g)
            g >= 10 -> String.format(Locale.CHINA, "%.1fG", g)
            else -> String.format(Locale.CHINA, "%.2fG", g)
        }
    }

    fun fmt(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.CHINA, "%.1fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1fM", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.CHINA, "%.2fG", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    /** 桌面机油卡右侧那一行的文字。 */
    fun statusText(): String {
        val a = availBytes()
        if (a < 0) return "剩余储存 --"
        return "剩余储存 ${gb(a)} / 共 ${gb(totalBytes())}"
    }

    // ---------------- 开关与节流 ----------------

    fun autoEnabled(): Boolean = runCatching { sp().getBoolean(K_AUTO, true) }.getOrDefault(true)
    fun setAuto(v: Boolean) = sp().edit().putBoolean(K_AUTO, v).apply()

    fun lastCleanMs(): Long = sp().getLong(K_LAST_CLEAN, 0L)
    fun markCleaned() = sp().edit().putLong(K_LAST_CLEAN, System.currentTimeMillis()).apply()
    fun lastTipMs(): Long = sp().getLong(K_LAST_TIP, 0L)
    fun markTipped() = sp().edit().putLong(K_LAST_TIP, System.currentTimeMillis()).apply()

    // ---------------- 全盘路径 ----------------

    /** 一个可浏览 / 可勾选删除的路径。 */
    data class PathItem(
        val path: String,
        val name: String,
        val dir: Boolean,
        /** -1 = 分区根，占用未统计 */
        val bytes: Long,
        val files: Int,
        val writable: Boolean,
        /** true = 系统保护，不允许删除 */
        val locked: Boolean,
        val kind: String,
        var checked: Boolean = false
    ) {
        val sizeText: String get() = if (bytes < 0) "分区" else fmt(bytes)
    }

    /** 一次目录浏览的结果。path=null 表示「本机全部分区」虚拟根。 */
    data class Browse(
        val path: String?,
        val parent: String?,
        val children: List<PathItem>,
        val truncated: Boolean,
        val label: String
    )

    /** 扫盘预算：超时 / 文件数超限就截断，宁可少算也不卡桌面。 */
    private class Budget(ms: Long, val maxFiles: Int = 220000) {
        val deadline = System.currentTimeMillis() + ms
        var files = 0
        var truncated = false
        fun out(): Boolean = System.currentTimeMillis() > deadline || files >= maxFiles
    }

    /** 枚举本机所有路径（内置 / 外置卡 / U 盘 / 系统可写区 / 根）。 */
    fun mounts(): List<File> {
        val map = LinkedHashMap<String, File>()
        fun add(f: File?) {
            if (f == null) return
            runCatching {
                // 不要求 canRead：部分卷要等用户授权 / 插上 U 盘才可读，
                // 先列出来，真不可读时面板会显示「不可读」，别把路径藏掉。
                if (f.exists() && f.isDirectory) {
                    val k = canon(f)
                    if (!map.containsKey(k)) map[k] = f
                }
            }
        }
        // /storage/XXXX/Android/data/<pkg>/files|cache 往上退 4 级才是卷根
        fun volRoot(f: File?): File? {
            var cur = f
            repeat(4) { cur = cur?.parentFile }
            return cur
        }
        add(Environment.getExternalStorageDirectory())
        add(File("/sdcard"))
        add(File("/mnt/sdcard"))
        runCatching { File("/storage").listFiles() }.getOrNull()?.forEach { top ->
            if (top.name == "emulated") {
                runCatching { top.listFiles() }.getOrNull()?.forEach { add(it) }
            } else if (top.name != "self") add(top)
        }
        runCatching { File("/mnt").listFiles() }.getOrNull()?.forEach { f ->
            val n = f.name.lowercase(Locale.ROOT)
            val hit = n == "sdcard" || n.startsWith("usb") || n.startsWith("udisk") ||
                n.startsWith("ext") || n.startsWith("external") ||
                n.startsWith("media_rw") || n.startsWith("sd")
            if (hit) {
                if (n == "media_rw") runCatching { f.listFiles() }.getOrNull()?.forEach { add(it) }
                else add(f)
            }
        }
        // getExternalFilesDirs / getExternalCacheDirs 能带出系统登记的外置卷
        runCatching { ctx().getExternalFilesDirs(null) }.getOrNull()?.forEach { add(volRoot(it)) }
        runCatching { ctx().getExternalCacheDirs() }.getOrNull()?.forEach { add(volRoot(it)) }
        add(File("/data/local/tmp"))
        add(File("/data/cache"))
        add(File("/data/log"))
        add(File("/cache"))
        add(Environment.getDataDirectory())
        add(File("/"))
        return map.values.toList()
    }

    private fun mountRoots(): Set<String> = mountKeys ?: synchronized(this) {
        val cur = mountKeys
        if (cur != null) cur
        else mounts().map { canon(it) }.toSet().also { mountKeys = it }
    }

    private fun labelOf(p: String): String = when {
        p == "/" -> "系统根 /"
        p.startsWith("/data") && p != "/data" -> "系统可写区 $p"
        p == "/data" -> "数据分区 /data"
        p == "/cache" -> "系统缓存 /cache"
        p.contains("emulated") || p.startsWith("/sdcard") || p.startsWith("/mnt/sdcard") ->
            "内置存储 $p"
        p.startsWith("/storage/") || p.startsWith("/mnt/") -> "外置卡 / U 盘 $p"
        else -> p
    }

    private fun kindOf(p: String, isDir: Boolean): String = when {
        p.contains("emulated") || p.startsWith("/sdcard") || p.startsWith("/mnt/sdcard") -> "内置存储"
        p.startsWith("/storage/") || p.startsWith("/mnt/") -> "外置卡 / U 盘"
        p.startsWith("/data") || p.startsWith("/cache") -> "系统可写区"
        else -> if (isDir) "目录" else "文件"
    }

    private fun skipTree(p: String): Boolean {
        if (p == "/" || p == "/storage" || p == "/mnt" || p == "/data") return false
        for (s in SYS_SKIP) if (p == s || p.startsWith("$s/")) return true
        return false
    }

    /** 删除保护：挂载点根、系统树、桌面自身配置数据一律不删。 */
    fun locked(p: String): Boolean {
        val c = canon(p)
        if (c == "/" || c in mountRoots()) return true
        for (s in SYS_LOCK) if (c == s || c.startsWith("$s/")) return true
        val base = runCatching { ctx().applicationInfo.dataDir }.getOrNull()?.let { canon(File(it)) }
        if (base != null && (c == base || c.startsWith("$base/"))) {
            // 桌面的 cache / code_cache 可以清，其余（files/databases/shared_prefs）保护
            if (!c.contains("/cache") && !c.contains("/code_cache")) return true
        }
        return false
    }

    /** 分区列表：虚拟根视图。 */
    fun roots(): List<PathItem> = mounts().map { f ->
        val p = canon(f)
        PathItem(p, labelOf(p), true, -1L, 0, f.canWrite(), true, "分区 · 点开查看占用")
    }

    /** 逐级浏览：列出直接子项并递归统计各自占用。后台线程调用。 */
    fun browse(path: String?): Browse {
        if (path == null) {
            val ms = mounts()
            return Browse(null, null, roots(), false, "本机全部分区 · ${ms.size} 个可访问路径")
        }
        val dir = File(path)
        if (!dir.isDirectory) {
            return Browse(path, dir.parent?.let { canon(it) }, emptyList(), false, path)
        }
        val b = Budget(6500L)
        val kids = runCatching { dir.listFiles() }.getOrNull()
            ?: return Browse(path, dir.parent?.let { canon(it) }, emptyList(), false, "$path · 不可读")
        val out = ArrayList<PathItem>()
        for (k in kids) {
            if (b.out()) { b.truncated = true; break }
            val p = canon(k)
            if (skipTree(p)) continue
            val m = measure(k, b)
            out += PathItem(
                p, k.name, k.isDirectory, m[0], m[1].toInt(),
                k.canWrite(), locked(p), kindOf(p, k.isDirectory)
            )
        }
        out.sortWith(compareByDescending<PathItem> { it.dir }.thenByDescending { it.bytes })
        return Browse(
            path, dir.parent?.let { canon(it) }, out, b.truncated,
            "$path · ${out.size} 项" + if (b.truncated) " · 扫描已截断" else ""
        )
    }

    /** 递归量一棵树：[bytes, files]，共享预算。 */
    private fun measure(f: File, b: Budget): LongArray {
        var size = 0L
        var count = 0L
        val stack = ArrayDeque<File>()
        stack.add(f)
        while (stack.isNotEmpty()) {
            if (b.out()) { b.truncated = true; break }
            val x = stack.removeFirst()
            val kids = runCatching { x.listFiles() }.getOrNull()
            if (kids == null) {
                size += runCatching { x.length() }.getOrDefault(0L)
                count++
                b.files++
            } else {
                // 统计子目录时也不进系统只读树，避免 /proc 之类的伪文件系统拖死扫盘
                for (k in kids) {
                    val kp = k.absolutePath
                    var skip = false
                    for (s in SYS_SKIP) if (kp == s || kp.startsWith("$s/")) { skip = true; break }
                    if (!skip) stack.addLast(k)
                }
            }
        }
        return longArrayOf(size, count)
    }

    private fun sizeOf(f: File): Long = measure(f, Budget(2000L, 1000000))[0]

    /** 删除勾选的任意路径，返回实际释放字节数。后台线程调用。 */
    fun deletePaths(paths: List<String>): Long {
        var freed = 0L
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) continue
            if (locked(p)) continue
            val sz = sizeOf(f)
            deleteTree(f)
            if (!f.exists()) freed += sz
        }
        if (freed > 0) markCleaned()
        return freed
    }

    // ---------------- 全盘垃圾扫描 ----------------

    /** 一类可清理内容（沿用旧 API，调用点不用改）。 */
    data class Item(
        val key: String,
        val title: String,
        val desc: String,
        /** 自动清理时是否清它（无风险、删了自动重建） */
        val safe: Boolean,
        var checked: Boolean = true,
        var bytes: Long = 0,
        var files: Int = 0,
        var truncated: Boolean = false,
        val targets: MutableList<File> = ArrayList(),
        /** 0 放心清理 / 1 推荐清理 / 2 慎用（参考应用管家 g7/o0 的分类分级） */
        val level: Int = 0
    ) {
        val sizeText: String get() = fmt(bytes)
        val levelText: String get() = LEVEL_NAMES[level.coerceIn(0, LEVEL_NAMES.size - 1)]
    }

    /** 最近一次全盘扫描的统计文字。 */
    fun scanInfo(): String = lastScanInfo

    /** 只扫桌面自己的缓存，保留给启动清理用。 */
    fun selfCache(): Item {
        val item = Item("self", "桌面缓存", "桌面自己的图片 / 临时文件", true)
        val c = ctx()
        val b = Budget(1500L)
        runCatching { c.cacheDir?.let { addTree(item, it, b) } }
        runCatching { c.externalCacheDir?.let { addTree(item, it, b) } }
        runCatching { c.codeCacheDir?.let { addTree(item, it, b) } }
        return item
    }

    /**
     * 全盘垃圾扫描（后台线程）。
     *
     * 分类与分级参考车机「应用管家 1.8」`g7/o0` 的清理规则：
     *  - 放心清理：全盘应用缓存（含 Android/data 下各包 cache）、缩略图、运行日志；
     *  - 推荐清理：临时与下载碎片、/data/local/tmp 残留、内部存储里的空文件夹；
     *  - 慎用：文件名带 log/cache/tmp/temp/xlog 关键字的冗余文件、安装残留 .apk、
     *    已卸载应用在 Android/data、Android/obb、/data/data 留下的目录。
     *
     * @param full true = 九类全扫（手动面板），false = 只扫「放心清理」三类（自动清理用，快）。
     */
    fun scan(full: Boolean = true): List<Item> {
        val b = Budget(if (full) 11000L else 5000L)
        val groups = LinkedHashMap<String, Item>()
        fun g(key: String, title: String, desc: String, level: Int) {
            groups[key] = Item(key, title, desc, level == 0, level = level)
        }
        g("appcache", "应用缓存", "全盘 cache / code_cache（含 Android/data 下各应用缓存），删了自动重建", 0)
        g("thumb", "缩略图缓存", ".thumbnails / thumbnails 预览图缓存，删了自动重建", 0)
        g("logs", "运行日志", ".log / .xlog / .trace / tombstone 与 log / crash / ANR 目录", 0)
        if (full) {
            g("tmp", "临时文件与下载碎片", ".tmp / .part / .crdownload / .download / .!ut / .td / .bak 等中断残留", 1)
            g("adb", "adb 临时目录残留", "/data/local/tmp 下的临时文件（对应应用管家清空该临时目录）", 1)
            g("empty", "空文件夹", "内部存储 / U 盘里没有内容的空目录", 1)
            g("redundant", "冗余关键字文件", "文件名带 log / cache / tmp / temp / xlog 的文件，应用管家规则「通通删除」，默认不勾", 2)
            g("apks", "安装残留（安装包）", "任意路径下的 .apk，装过一次就没用了，默认不勾", 2)
            g("orphan", "卸载残留目录", "Android/data、Android/obb、/data/data 里已卸载应用留下的目录，默认不勾", 2)
        }

        val ms = mounts()
        for (m in ms) {
            if (b.out()) { b.truncated = true; break }
            if (canon(m) == "/") continue // 根由各挂载点分别覆盖，避免重复扫
            walk(m, 0, b, full, groups)
        }
        if (full) scanOrphan(b, groups["orphan"]!!)

        val bytes = groups.values.sumOf { it.bytes }
        val files = groups.values.sumOf { it.files }
        lastScanInfo = "扫描完成 · ${ms.size} 个分区 · 发现 $files 个文件，共 ${fmt(bytes)}" +
            if (b.truncated) " · 达到扫描上限已截断" else ""
        val out = groups.values.filter { it.bytes > 0 || it.targets.isNotEmpty() }
        for (it in out) {
            it.truncated = b.truncated
            // 前两级默认勾选，「慎用」类必须由用户手动勾，避免误删安装包 / 用户文件
            it.checked = it.level <= 1
        }
        return out.sortedBy { it.level }
    }

    private fun navPath(p: String): Boolean = NAV_PKGS.any { p.contains("/$it/") || p.endsWith("/$it") }

    private fun isJunkDir(n: String): Boolean = when (n) {
        "cache", "code_cache", ".cache", "tmp", "temp",
        ".temp", ".trash", "trash", "crashpad", "download_cache", ".fragment" -> true
        else -> false
    }

    private fun isLogDir(n: String): Boolean = when (n) {
        "log", "logs", "xlog", "logcat", "crash", "crashes", "tombstones", "anr", "traces" -> true
        else -> false
    }

    private fun isLogFile(n: String): Boolean =
        n.endsWith(".log") || n.endsWith(".xlog") || n.endsWith(".trace") || n.contains("tombstone")

    private fun isTmpFile(n: String): Boolean =
        n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") ||
            n.endsWith(".partial") || n.endsWith(".download") || n.endsWith(".crdownload") ||
            n.endsWith(".!ut") || n.endsWith(".td") ||
            n.endsWith(".bak") || n.endsWith(".old") || n.endsWith(".swp") || n.endsWith(".~")

    /** 应用管家「冗余文件」规则：文件名带 log / cache / tmp / temp / xlog 关键字 */
    private fun isRedundant(n: String): Boolean =
        arrayOf("log", "cache", "tmp", "temp", "xlog").any { n.contains(it) }

    private fun addTarget(g: MutableMap<String, Item>, key: String, f: File, b: Budget) {
        val it = g[key] ?: return
        addTree(it, f, b)
    }

    private fun walk(f: File, depth: Int, b: Budget, full: Boolean, g: MutableMap<String, Item>) {
        if (b.out() || depth > 16) { b.truncated = true; return }
        val p = canon(f)
        if (skipTree(p)) return
        if (!full && f.isDirectory && navPath(p)) return // 自动清理不动导航离线地图
        val n = f.name.lowercase(Locale.ROOT)
        if (f.isDirectory) {
            // 应用管家：/data/local/tmp 下的内容整条当 adb 残留清掉（分区根本身由 locked 保护）
            if (full && p.startsWith("/data/local/tmp/") && !locked(p)) {
                addTarget(g, "adb", f, b)
                return
            }
            val kids = runCatching { f.listFiles() }.getOrNull() ?: return
            // 应用管家：「是否要清理内部存储中的所有空文件夹」
            if (full && kids.isEmpty() && !locked(p) && p !in mountRoots()) {
                val it = g["empty"] ?: return
                it.files++
                it.targets += f
                return
            }
            val holder: Item? = when {
                n == ".thumbnails" || n == "thumbnails" -> g["thumb"]
                isJunkDir(n) -> g["appcache"]
                isLogDir(n) -> g["logs"]
                else -> null
            }
            if (holder != null && !locked(p)) {
                addTree(holder, f, b)
                return
            }
            for (k in kids) {
                if (b.out()) { b.truncated = true; break }
                walk(k, depth + 1, b, full, g)
            }
        } else {
            b.files++
            if (locked(p) || !f.canWrite()) return
            val holder: Item? = when {
                isLogFile(n) -> g["logs"]
                full && isTmpFile(n) -> g["tmp"]
                full && n.endsWith(".apk") -> g["apks"]
                full && isRedundant(n) -> g["redundant"]
                else -> null
            }
            if (holder != null) {
                val sz = runCatching { f.length() }.getOrDefault(0L)
                holder.bytes += sz
                holder.files++
                holder.targets += f
            }
        }
    }

    /** 卸载残留：Android/data、Android/obb，以及能读到时的 /data/data、/data/user/0。 */
    private fun scanOrphan(b: Budget, item: Item) {
        val alive = HashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            ctx().packageManager.getInstalledPackages(0).forEach { alive.add(it.packageName) }
        }
        // 旧版 StorageCleaner 的安全阀：拿不到已安装列表时绝不能把整排目录当残留删掉
        if (alive.size < 5) return
        val sd = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val roots = buildList {
            if (sd != null) {
                add(File(sd, "Android/data"))
                add(File(sd, "Android/obb"))
            }
            add(File("/data/data"))
            add(File("/data/user/0"))
        }
        val seen = HashSet<String>()
        for (root in roots) {
            val kids = runCatching { root.listFiles() }.getOrNull() ?: continue
            for (d in kids) {
                if (b.out()) { b.truncated = true; break }
                val pkg = d.name
                if (!d.isDirectory || !pkg.contains('.') || pkg == ctx().packageName ||
                    alive.contains(pkg)
                ) continue
                val k = canon(d)
                if (!seen.add(k) || locked(k)) continue
                addTree(item, d, b)
            }
        }
    }

    private fun addTree(item: Item, root: File, b: Budget) {
        if (!root.exists()) return
        val m = measure(root, b)
        item.bytes += m[0]
        item.files += m[1].toInt()
        item.targets += root
    }

    /** 只删勾选的项，返回实际释放字节数；后台线程调用。 */
    fun clean(items: List<Item>): Long {
        var freed = 0L
        for (it in items) {
            if (!it.checked) continue
            for (f in it.targets) {
                if (!f.exists() || locked(canon(f))) continue
                val sz = sizeOf(f)
                deleteTree(f)
                if (!f.exists()) freed += sz
            }
        }
        if (freed > 0) markCleaned()
        return freed
    }

    private fun deleteTree(f: File?) {
        if (f == null || !f.exists()) return
        runCatching {
            val kids = f.listFiles()
            if (kids != null) for (k in kids) deleteTree(k)
            f.delete()
        }
    }

    /** 自动清理：只清「放心清理」三类，10 分钟节流，返回释放字节数。 */
    fun autoClean(): Long {
        if (!autoEnabled()) return 0L
        if (System.currentTimeMillis() - lastCleanMs() < 10 * 60 * 1000L) return 0L
        return clean(scan(false).filter { it.safe })
    }

    /** 启动 / 回桌面时清掉装完的安装包（与自动清理开关无关）。 */
    fun cleanOldApks(): Long {
        var freed = 0L
        for (d in apkDirs()) {
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in kids) {
                val n = f.name.lowercase(Locale.ROOT)
                val code = oldApkCode(n)
                val isOurs = n.startsWith("h6-") &&
                    (n.endsWith(".apk") || n.endsWith(".apk.part") || n.endsWith(".apk.tmp"))
                if (isOurs && (code == null || code <= curCode())) {
                    freed += runCatching { f.length() }.getOrDefault(0L)
                    deleteTree(f)
                }
            }
        }
        return freed
    }

    private fun oldApkCode(name: String): Int? {
        val n = name.lowercase(Locale.ROOT)
        if (!n.startsWith("h6-") || !n.contains(".apk")) return null
        val s = n.removePrefix("h6-").substringBefore(".apk")
        return s.toIntOrNull()
    }

    private fun apkDirs(): List<File> = buildList {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        runCatching { add(File(dl, "哈弗桌面")) }
        // 兼容改名前的旧目录，里面残留的旧安装包一样能扫到
        runCatching { add(File(dl, "H6N桌面")) }
        runCatching { ctx().getExternalFilesDir("update")?.let { add(it) } }
    }

    private fun curCode(): Int = runCatching {
        @Suppress("DEPRECATION")
        ctx().packageManager.getPackageInfo(ctx().packageName, 0).versionCode
    }.getOrDefault(0)

    /** 供设置页显示的可清理总量。 */
    fun summary(items: List<Item>): String {
        val bytes = items.sumOf { it.bytes }
        val files = items.sumOf { it.files }
        return "${fmt(bytes)} · $files 个文件"
    }
}
