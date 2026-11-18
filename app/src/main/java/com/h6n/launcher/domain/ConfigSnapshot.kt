package com.h6n.launcher.domain

/**
 * 某一时刻的完整生效配置，不可变。
 *
 * ## 为什么要快照 + diff，而不是每次 `effective()`
 * 旧路径（ConfigStore.effective）每次调用都要：
 * 读 assets/ui_config.json（磁盘 IO + JSON 解析）→ 读 SharedPreferences.getAll()
 * → 逐条 putValue 合并 → 返回整棵 JSONObject。
 * 设置页 onResume 调一次、回桌面 onResume 又调一次，都在主线程，
 * 于是"点设置卡一下、回桌面又卡一下"。
 *
 * 新路径：
 *  - 进程内只保留一份 [ConfigSnapshot]（由 ConfigRepository 的 StateFlow 持有）；
 *  - 树在构造时拷贝成不可变结构，之后所有读取都是纯内存；
 *  - 任意一处修改只产出**新的**快照，订阅方用 [diff] 得到"哪几个扁平 key 变了"，
 *    再按分组前缀决定刷新哪个卡片，不再全量重建控件。
 *
 * 快照比较用 [flat]（扁平字符串表）而不是深比较整棵树，数组字段整体作为一个值，
 * 足够覆盖配置场景，也避免递归 equals 在大树上的开销。
 */
class ConfigSnapshot internal constructor(
    /** 合并后的配置树：Map / List / String / Number / Boolean / null */
    val root: Map<String, Any?>,
    /** 用户覆盖层（SP/DataStore 里存的那份扁平表，已去掉 __ 元数据） */
    val overrides: Map<String, String>,
    /** 覆盖层写入时间戳，用于诊断 */
    val stamp: Long
) {

    /** 配置是否已从磁盘/资产加载完成。false 时 UI 应画骨架而不是按默认值排版。 */
    val isReady: Boolean get() = root.isNotEmpty()

    /**
     * 扁平化视图：`分组.字段 -> 字符串值`。
     * 首次访问计算一次，之后复用；快照不可变，所以无需加锁。
     */
    val flat: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        val out = LinkedHashMap<String, String>(512)
        flattenInto(root, "", out)
        out
    }

    // ---------------- 读取 ----------------

    /** 按点号路径取原始值，例如 `page2.tireUnit`。 */
    fun value(path: String): Any? {
        var cur: Any? = root
        var start = 0
        while (start <= path.length) {
            val dot = path.indexOf('.', start)
            val seg = if (dot < 0) path.substring(start) else path.substring(start, dot)
            val map = cur as? Map<*, *> ?: return null
            cur = map[seg] ?: return null
            if (dot < 0) break
            start = dot + 1
        }
        return cur
    }

    fun str(path: String, def: String = ""): String = value(path)?.toString() ?: def

    fun int(path: String, def: Int = 0): Int = when (val v = value(path)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: def
        else -> def
    }

    fun bool(path: String, def: Boolean = false): Boolean = when (val v = value(path)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "1", "true", "yes", "on", "是", "开" -> true
            "0", "false", "no", "off", "否", "关" -> false
            else -> def
        }
        else -> def
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(path: String): Map<String, Any?>? = value(path) as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(path: String): List<Any?>? = value(path) as? List<Any?>

    /** 某个分组的扁平子表，例如 [groupFlat]("page2") 只返回 page2.* 的内容。 */
    fun groupFlat(group: String): Map<String, String> {
        val prefix = "$group."
        val out = LinkedHashMap<String, String>(32)
        for ((k, v) in flat) if (k.startsWith(prefix)) out[k] = v
        return out
    }

    // ---------------- 变更比较 ----------------

    /**
     * 与上一份快照比较，返回发生变化的扁平 key 集合（含被删除的 key）。
     *
     * 返回集合直接喂给订阅方：
     * ```
     * val changed = latest.diff(prev)
     * if (changed.any { it.startsWith("page2.") }) refreshPage2(changed)
     * ```
     */
    fun diff(prev: ConfigSnapshot): Set<String> {
        val cur = flat
        val old = prev.flat
        val out = LinkedHashSet<String>(8)
        for ((k, v) in cur) if (old[k] != v) out += k
        for (k in old.keys) if (!cur.containsKey(k)) out += k
        return out
    }

    /** 指定分组是否有变化（比 `diff` 后再过滤更省一次分配）。 */
    fun changedIn(prev: ConfigSnapshot, group: String): Boolean {
        val prefix = "$group."
        val cur = flat
        val old = prev.flat
        for ((k, v) in cur) if (k.startsWith(prefix) && old[k] != v) return true
        for (k in old.keys) if (k.startsWith(prefix) && !cur.containsKey(k)) return true
        return false
    }

    override fun toString(): String =
        "ConfigSnapshot(flat=${flat.size}, overrides=${overrides.size}, stamp=$stamp)"

    internal companion object {

        /** 把 JSON 树压成 `a.b.c -> 字符串值`。数组整体作为一个值参与比较。 */
        fun flattenInto(node: Map<String, Any?>, prefix: String, out: MutableMap<String, String>) {
            for ((k, v) in node) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                when (v) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        flattenInto(v as Map<String, Any?>, path, out)
                    }
                    is List<*> -> out[path] = v.joinToString(",", "[", "]")
                    null -> out[path] = "null"
                    else -> out[path] = v.toString()
                }
            }
        }
    }
}
