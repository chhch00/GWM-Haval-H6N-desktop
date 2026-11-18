package com.h6n.launcher.core

import android.content.Context
import android.os.Environment
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.data.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置备份 / 恢复 —— 移植并扩展未重写版 `ConfigBackup.java`。
 *
 * 重写版把界面配置搬进了 Preferences DataStore，但播报开关、音频通道与音量、
 * 发动机累计小时、模拟信号、dock 槽位、更新源、启动提示等仍分散在各自的
 * SharedPreferences 里。只备份 DataStore 会丢一大半用户设置，所以这里把
 * 「DataStore 覆盖层 + 全部配置类 SP」一起打包成一个 JSON。
 *
 * 备份文件放在 Download 目录（车机上插 U 盘、用应用管家都能直接看到）：
 * `哈弗桌面配置_<版本名>_<时间戳>.json`（版本名随每次出包递增）
 *
 * 兼容：旧版 5.97.x 的 `H6N桌面配置*.json`（整棵 ui_config 有效树 + backup 附加节）
 * 也能直接导入，界面配置会被扁平化写进 DataStore，附加节恢复发动机小时、
 * 播报通道 / 音量与 dock 槽位。
 *
 * 所有文件读写都是同步方法，调用方必须放在子线程；只有 DataStore 事务内部
 * 用 runBlocking 切到 IO，不影响主线程。
 */
object ConfigBackup {

    private const val TAG = "H6CfgBak"
    const val PREFIX = "哈弗桌面配置"
    private const val LEGACY_PREFIX = "H6N桌面配置"
    private const val SUFFIX = ".json"
    private const val FORMAT = "h6n-backup"
    /** 5：白名单补入自动化四组（悬浮球位置 / 按键映射 / 开机任务 / 导航悬浮窗位置）。 */
    private const val VER = 5
    private const val KEY_BACKUP = "backup"
    private const val KEY_CONFIG = "config"
    private const val KEY_PREFS = "prefs"

    /** 本地备份最多保留的版本数，自动备份与手动导出统一轮转。 */
    const val MAX_VERSIONS = 10

    /**
     * 允许导出 / 导入的 SP 白名单，避免备份文件被塞进任意偏好名。
     *
     * ★ 2026-11-11 覆盖审查补齐三项：`h6_steer_key`（方向盘学习键码 / 总开关 /
     * 长按时长）、`h6_donate`（6.0.57 起为捐赠弹窗的授权码解锁标记）、`h6_backup`（冷启动自动备份开关）。
     * 之前只备份 11 项，方向盘按键绑定和赞赏开关恢复后会丢。
     *
     * ★ 2026-11-13（v6.0.49）补齐自动化四组：`h6_float_btn`（悬浮球停靠位置）、
     * `h6_key_map`（按键映射绑定）、`h6_boot_task`（开机任务列表）、
     * `h6_navi_overlay`（导航悬浮窗位置，6.0.35 起一直漏着）。这四组都只放
     * 「用户摆出来的位置 / 教的键 / 排的任务」，换机恢复后就是原样，不会带设备态。
     *
     * 有意不纳入的三项：
     * · `h6_loc` —— 定位权限「开机只问一次」的一次性运行时标记，备份它会让换机恢复后跳过授权询问；
     * · `h6_perm` —— 权限提示「今天已经提示过」的日期戳，同上，恢复后当天就再也不提示了；
     * · `h6_adb_grant` —— 自动开无线 ADB 的「已尝试过」标记，换机必须允许重试一次。
     * `h6_weather` 属天气缓存但仍按老行为带走。
     */
    private val SP_NAMES = arrayOf(
        "h6_cfg",              // 旧版覆盖层，导入时合并进 DataStore
        "h6_scene",            // 场景播报开关 / 音色包 / 自定义音效
        "h6_scene_audio",      // 音频通道 + 提示音量
        "h6_engine_hours",     // 发动机累计
        "h6_sim_signals",      // 全车数据模拟
        "h6_storage",          // 存储清理守护
        "h6_update",           // 更新源 / 自动检查 / 待更新
        "h6_weather",          // 天气缓存
        "dock_slots",          // dock 图标与顺序
        "h6_wallpaper",        // 壁纸选择（内置编号 / 本机图片路径 / 蒙层开关）
        "h6_steer_key",        // 方向盘按键学习 / 总开关 / 长按时长
        "h6_donate",           // 捐赠弹窗授权码解锁（6.0.57 起）
        "h6_float_btn",        // 悬浮按钮停靠位置（x / y，dp）
        "h6_key_map",          // 按键映射绑定（JSON 数组，含单击 / 长按）
        "h6_boot_task",        // 开机任务列表（延时 + 动作）
        "h6_navi_overlay",     // 高德导航悬浮窗位置（x / y，dp）
        AutoBackup.SP_NAME     // 冷启动自动备份开关 / 上次结果
    )

    private val SKIP_TOP = setOf(
        KEY_BACKUP, KEY_CONFIG, KEY_PREFS, "format", "ver", "at", "app", "code"
    )

    /**
     * 组内的设备态键：导出不写、导入不读。
     * `h6_boot_task` 的 `last_run` 是上次开机执行的时间戳，恢复成备份里的旧值会让
     * 恢复后那一次开机被判成「已经跑过」，任务静默不执行；`last_result` 同理只是展示态。
     */
    private val SKIP_KEYS = mapOf(
        "h6_boot_task" to setOf("last_run", "last_result")
    )

    // ---------------- 目录 / 文件 ----------------

    private fun dirs(c: Context): List<File> = buildList {
        val dl = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (dl != null) {
            add(dl)
            add(File(dl, "哈弗桌面"))
            add(File(dl, "H6N桌面"))
        }
        runCatching { c.getExternalFilesDir(null)?.let { add(it) } }
    }

    private fun writeDir(c: Context): File? {
        for (d in dirs(c)) {
            runCatching {
                if (!d.exists()) d.mkdirs()
                if (d.isDirectory && d.canWrite()) return d
            }
        }
        return null
    }

    private fun isBackup(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.endsWith(SUFFIX) &&
            (name.startsWith(PREFIX) || name.startsWith(LEGACY_PREFIX))
    }

    /** 所有能读到的备份，按修改时间倒序（同时扫 Download 根 / 哈弗桌面 / 旧目录 / 应用兜底目录）。 */
    fun list(c: Context): List<File> {
        val seen = HashSet<String>()
        val out = ArrayList<File>()
        for (d in dirs(c)) {
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (f in kids) {
                if (!f.isFile || !isBackup(f.name)) continue
                val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                if (seen.add(key)) out.add(f)
            }
        }
        out.sortWith { a, b -> b.lastModified().compareTo(a.lastModified()) }
        return out
    }

    fun shown(f: File): String {
        val p = f.absolutePath
        val pre = runCatching {
            Environment.getExternalStorageDirectory().absolutePath
        }.getOrDefault("")
        return if (pre.isNotEmpty() && p.startsWith(pre)) "/sdcard" + p.substring(pre.length) else p
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** 精确到秒：冷启动 / 快速导出 / 导入前备份同一分钟也不会互相覆盖。 */
    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun fileName(c: Context, tag: String): String {
        val ver = appName(c).replace(Regex("[/\\\\:*?\"<>|\\s]"), "")
        return PREFIX + "_" + ver + tag + "_" + fileStamp() + SUFFIX
    }

    private fun appName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "-"
    }.getOrDefault("-")

    private fun appCode(c: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }.getOrDefault(0)

    // ---------------- 导出 ----------------

    /** 当前完整配置 JSON。 */
    fun dump(c: Context): String {
        val o = JSONObject()
            .put("format", FORMAT)
            .put("ver", VER)
            .put("at", stamp())
            .put("app", appName(c))
            .put("code", appCode(c))

        val cfg = JSONObject()
        for ((k, v) in ConfigRepository.get(c).current().overrides) cfg.put(k, v)
        o.put(KEY_CONFIG, cfg)
        o.put(KEY_PREFS, prefsNode(c))
        o.put(KEY_BACKUP, legacyNode(c))
        return o.toString(2)
    }

    /** @param tag 文件名附加标记，导入前自动备份传「_导入前」，冷启动传「_自动」。 */
    fun export(c: Context, tag: String = ""): File {
        val dir = writeDir(c) ?: throw IllegalStateException("没有可写的存储目录")
        val base = fileName(c, tag)
        var f = File(dir, base)
        // 同一秒内连续导出时追加序号，保证每次都是独立版本而不是覆盖前一份。
        var n = 2
        while (f.exists()) {
            f = File(dir, base.removeSuffix(SUFFIX) + "_$n" + SUFFIX)
            n++
        }
        f.writeText(dump(c), Charsets.UTF_8)
        Log.i(TAG, "导出配置 ${f.absolutePath} · ${f.length()} 字节")
        // 统一轮转：最多保留 MAX_VERSIONS 份，超出删最旧的。
        val pruned = runCatching { prune(c, f) }.getOrDefault(0)
        if (pruned > 0) Log.i(TAG, "轮转清理 $pruned 份旧备份（上限 $MAX_VERSIONS）")
        return f
    }

    /**
     * 只删本 app 生成的新格式备份（[PREFIX] 开头）；旧版 `H6N桌面配置*.json`
     * 是用户从老版本带过来做兼容导入的，保留不动。
     * [protect] 是刚写出来的那份，防止同一秒 lastModified 打平时误删。
     */
    private fun prune(c: Context, protect: File? = null): Int {
        val files = list(c).filter { it.name.startsWith(PREFIX) }
        if (files.size <= MAX_VERSIONS) return 0
        val protectPath = runCatching { protect?.canonicalPath }.getOrNull()
        var n = 0
        for (f in files.drop(MAX_VERSIONS)) {
            if (protectPath != null &&
                runCatching { f.canonicalPath }.getOrNull() == protectPath
            ) continue
            if (runCatching { f.delete() }.getOrDefault(false)) n++
        }
        return n
    }

    /** 摘要，给面板与设置页显示。 */
    fun summary(c: Context): String {
        val overrides = runCatching { ConfigRepository.get(c).current().overrides.size }.getOrDefault(0)
        val audio = runCatching { "${SceneStore.audioName()} · ${SceneStore.volume()}%" }.getOrDefault("-")
        val dock = runCatching {
            val sp = c.getSharedPreferences("dock_slots", Context.MODE_PRIVATE)
            (0 until 16).count { sp.contains("slot_$it") }
        }.getOrDefault(0)
        val auto = runCatching {
            " · 键位 ${KeyMap.binds(c).size} 条 / 开机任务 ${BootTask.tasks(c).size} 条"
        }.getOrDefault("")
        return "界面覆盖 $overrides 项 · 发动机 ${EngineHours.text()} · 播报 $audio · dock $dock 格$auto · " +
            "备份 ${list(c).size} 份（上限 $MAX_VERSIONS） · 冷启动自动备份 " +
            (if (AutoBackup.enabled(c)) "开" else "关")
    }

    private fun prefsNode(c: Context): JSONObject {
        val out = JSONObject()
        for (name in SP_NAMES) {
            val all = runCatching {
                c.getSharedPreferences(name, Context.MODE_PRIVATE).all
            }.getOrNull() ?: continue
            val node = JSONObject()
            val skip = SKIP_KEYS[name]
            for ((k, v) in all) {
                if (v == null) continue
                if (skip?.contains(k) == true) continue
                val cell = JSONObject()
                when (v) {
                    is Boolean -> { cell.put("t", "b"); cell.put("v", v) }
                    is Int -> { cell.put("t", "i"); cell.put("v", v) }
                    is Long -> { cell.put("t", "l"); cell.put("v", v) }
                    is Float -> { cell.put("t", "f"); cell.put("v", v.toDouble()) }
                    is String -> { cell.put("t", "s0"); cell.put("v", v) }
                    is Set<*> -> {
                        cell.put("t", "set")
                        cell.put("v", JSONArray(v.mapNotNull { it?.toString() }))
                    }
                    else -> continue
                }
                node.put(k, cell)
            }
            out.put(name, node)
        }
        return out
    }

    /** 与旧版 ConfigBackup.java 的 backup 节同形，方便旧版也能读附加数据。 */
    private fun legacyNode(c: Context): JSONObject {
        val b = JSONObject().put("ver", 3).put("at", stamp()).put("app", appName(c))
        runCatching {
            val sec = EngineHours.totalSeconds()
            b.put("engine", JSONObject().put("sec", sec).put("text", EngineHours.text()))
        }
        runCatching {
            b.put(
                "audio", JSONObject()
                    .put("route", SceneStore.audioRoute())
                    .put("name", SceneStore.audioName())
                    .put("vol", SceneStore.volume())
            )
        }
        runCatching {
            val sp = c.getSharedPreferences("dock_slots", Context.MODE_PRIVATE)
            val slots = JSONObject()
            var n = 0
            for (i in 0 until 16) {
                if (!sp.contains("slot_$i")) continue
                slots.put(i.toString(), sp.getString("slot_$i", "").orEmpty())
                n++
            }
            b.put(
                "dock", JSONObject()
                    .put("slots", slots)
                    .put("icons", JSONObject())
                    .put("count", n)
            )
        }
        return b
    }

    // ---------------- 导入 ----------------

    /**
     * 用备份覆盖当前配置。会先自动导出一份「_导入前」备份。
     * @return 给 Toast 看的恢复摘要
     */
    fun restore(c: Context, f: File): String {
        var txt = f.readText(Charsets.UTF_8)
        if (txt.startsWith("\ufeff")) txt = txt.substring(1)
        val o = JSONObject(txt)
        val isNew = o.optString("format") == FORMAT

        // 后悔药：失败也不打断恢复
        val before = runCatching { export(c, "_导入前") }.getOrNull()

        val cfg = LinkedHashMap<String, String>()
        var prefsCount = 0
        if (isNew) {
            val node = o.optJSONObject(KEY_CONFIG)
            if (node != null) {
                val it = node.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (k.startsWith("__")) continue
                    cfg[k] = node.optString(k, "")
                }
            }
            // 旧版遗留的 h6_cfg 覆盖层，合并进来，不覆盖新版 config 里的同名项
            val legacy = o.optJSONObject(KEY_PREFS)?.optJSONObject("h6_cfg")
            if (legacy != null) {
                val it = legacy.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    if (k.startsWith("__") || cfg.containsKey(k)) continue
                    val cell = legacy.optJSONObject(k) ?: continue
                    cfg[k] = cell.optString("v", "")
                }
            }
        } else {
            flattenLegacy(o, cfg)
        }

        // DataStore：先清掉旧覆盖再写入，避免留下幽灵配置
        runBlocking(Dispatchers.IO) {
            val repo = ConfigRepository.get(c)
            repo.resetToDefault()
            if (cfg.isNotEmpty()) repo.set(cfg)
        }

        if (isNew) {
            prefsCount = applyPrefs(c, o.optJSONObject(KEY_PREFS) ?: JSONObject())
            // 模拟信号模块有内存状态，SP 写回后要重新装载才能立刻生效
            runCatching { SimSignals.reloadPrefs() }
            // 自动化四组也有内存状态 / 已经挂上的窗口，恢复完立刻同步，
            // 否则设置页显示的还是导入前的旧键位、旧任务、旧悬浮球位置
            applyAutoLive(c)
        }

        val hasDockPrefs = isNew &&
            (o.optJSONObject(KEY_PREFS)?.has("dock_slots") == true)
        val extra = applyBackup(c, o.optJSONObject(KEY_BACKUP), hasDockPrefs)

        val msg = "已恢复 ${cfg.size} 项界面配置 · $prefsCount 项偏好$extra" +
            (before?.let { "\n导入前配置已存：" + shown(it) } ?: "")
        Log.i(TAG, "导入配置 ${f.name} → $msg")
        return msg
    }

    /**
     * 恢复后让自动化模块立刻读到新值（[FloatButton.sync] / [EdgeGesture.sync]
     * 内部都 `main.post`，在子线程调用也安全）。
     */
    private fun applyAutoLive(c: Context) {
        val app = c.applicationContext ?: c
        runCatching { KeyMap.reload() }
        runCatching { BootTask.reload() }
        runCatching { FloatButton.sync(app) }
        runCatching { EdgeGesture.sync(app) }
    }

    /** 旧版备份：整棵配置树扁平化成「分组.字段 -> 字符串」覆盖表。 */
    private fun flattenLegacy(o: JSONObject, out: MutableMap<String, String>) {
        val it = o.keys()
        while (it.hasNext()) {
            val g = it.next()
            if (g.startsWith("_") || SKIP_TOP.contains(g)) continue
            val node = o.optJSONObject(g) ?: continue
            val jt = node.keys()
            while (jt.hasNext()) {
                val k = jt.next()
                if (k.startsWith("_")) continue
                val v = node.opt(k) ?: continue
                if (v === JSONObject.NULL) continue
                out["$g.$k"] = v.toString()
            }
        }
    }

    private fun applyPrefs(c: Context, root: JSONObject): Int {
        var n = 0
        for (name in SP_NAMES) {
            val node = root.optJSONObject(name) ?: continue
            val sp = c.getSharedPreferences(name, Context.MODE_PRIVATE)
            val ed = sp.edit().clear()
            val skip = SKIP_KEYS[name]
            val it = node.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (skip?.contains(k) == true) continue
                val cell = node.optJSONObject(k) ?: continue
                when (cell.optString("t")) {
                    "b" -> ed.putBoolean(k, cell.optBoolean("v"))
                    "i" -> ed.putInt(k, cell.optInt("v"))
                    "l" -> ed.putLong(k, cell.optLong("v"))
                    "f" -> ed.putFloat(k, cell.optDouble("v").toFloat())
                    "set" -> {
                        val arr = cell.optJSONArray("v") ?: JSONArray()
                        val set = LinkedHashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                        ed.putStringSet(k, set)
                    }
                    else -> ed.putString(k, cell.optString("v"))
                }
                n++
            }
            ed.commit()
        }
        return n
    }

    /** 附加节：发动机累计 / 播报通道与音量 / dock 槽位。 */
    private fun applyBackup(c: Context, bak: JSONObject?, hasDockPrefs: Boolean): String {
        if (bak == null) return ""
        val sb = StringBuilder()
        runCatching {
            val eng = bak.optJSONObject("engine")
            if (eng != null && eng.has("sec")) {
                val sec = eng.optLong("sec", -1L)
                if (sec >= 0L) {
                    EngineHours.restoreSeconds(sec)
                    sb.append("\n发动机累计 ").append(EngineHours.text())
                }
            }
        }
        runCatching {
            val aud = bak.optJSONObject("audio")
            if (aud != null) {
                if (aud.has("route")) {
                    val r = aud.optInt("route", -1)
                    if (r >= 0) SceneStore.setAudioRoute(r)
                }
                if (aud.has("vol")) {
                    val v = aud.optInt("vol", -1)
                    if (v >= 0) SceneStore.setVolume(v)
                }
                SceneAnnouncer.reloadSounds()
                sb.append("\n播报 ").append(SceneStore.audioName()).append(" · ")
                    .append(SceneStore.volume()).append("%")
            }
        }
        if (!hasDockPrefs) runCatching {
            val slots = bak.optJSONObject("dock")?.optJSONObject("slots")
            if (slots != null) {
                val sp = c.getSharedPreferences("dock_slots", Context.MODE_PRIVATE)
                val ed = sp.edit()
                for (i in 0 until 16) ed.remove("slot_$i")
                val it = slots.keys()
                var n = 0
                while (it.hasNext()) {
                    val k = it.next()
                    ed.putString("slot_$k", slots.optString(k, ""))
                    n++
                }
                ed.commit()
                sb.append("\ndock 槽位 ").append(n).append(" 格")
            }
        }
        return sb.toString()
    }

    // ---------------- 其它操作 ----------------

    fun delete(f: File): Boolean = runCatching { f.delete() }.getOrDefault(false)

    /** 恢复出厂：只清 DataStore 覆盖层，跟旧版一样不动发动机小时与 dock。 */
    fun reset(c: Context) {
        runBlocking(Dispatchers.IO) { ConfigRepository.get(c).resetToDefault() }
    }

    fun time(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
