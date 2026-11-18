package com.h6n.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 配置树的构建工具：JSON 解析、不可变化、覆盖层回填、值类型推断。
 *
 * 这里刻意不依赖任何第三方 JSON 库：车机上只需要解析一个 30KB 左右的
 * `assets/ui_config.json`，org.json 足够，且省掉 300KB 的依赖体积。
 */
internal object ConfigTree {

    /** `JSONObject` -> 有序 Map（递归）。JSONObject.NULL 归一为 Kotlin null。 */
    fun fromJson(obj: JSONObject): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length() * 2)
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = if (obj.isNull(k)) null else normalize(obj.get(k))
        }
        return out
    }

    fun normalize(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> fromJson(v)
        is JSONArray -> {
            val list = ArrayList<Any?>(v.length())
            for (i in 0 until v.length()) {
                list.add(if (v.isNull(i)) null else normalize(v.get(i)))
            }
            list
        }
        is Int, is Long, is Double, is Float, is Boolean, is String -> v
        is Number -> v.toDouble()
        else -> v.toString()
    }

    /**
     * 把覆盖层里的字符串还原成 JSON 值。
     *
     * 与旧版 ConfigStore.putValue 的行为保持一致：先试布尔/数字，
     * 再试 `{`/`[` 开头当 JSON 解析，都不行则按原始字符串写入（不 trim，
     * 保留用户输入里的空格）。
     */
    fun parseValue(raw: String): Any? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        when (t) {
            "null" -> return null
            "true" -> return true
            "false" -> return false
        }
        t.toIntOrNull()?.let { return it }
        t.toLongOrNull()?.let { return it }
        t.toDoubleOrNull()?.let { return it }
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching { return normalize(JSONTokener(t).nextValue()) }
        }
        return raw
    }

    /**
     * 把一条扁平覆盖 `page.field.sub -> raw` 写回树上。
     *
     * 路径中间缺失时会自动补空对象，这样即使 assets 里删掉了某个字段，
     * 用户的历史覆盖也不会把整棵树搞崩。
     */
    fun applyOverride(root: MutableMap<String, Any?>, key: String, raw: String) {
        val segs = key.split('.')
        if (segs.isEmpty() || segs[0].isEmpty()) return
        val value = parseValue(raw)
        var node = root
        for (i in 0 until segs.size - 1) {
            val seg = segs[i]
            @Suppress("UNCHECKED_CAST")
            val child = node[seg] as? MutableMap<String, Any?>
            if (child != null) {
                node = child
            } else {
                val created = LinkedHashMap<String, Any?>(4)
                node[seg] = created
                node = created
            }
        }
        node[segs[segs.size - 1]] = value
    }

    /**
     * 深拷贝整棵树。
     *
     * 必须深拷贝：默认树缓存在内存里长期复用，而 [applyOverride] 会往嵌套
     * Map 里写值；如果只做顶层浅拷贝，用户覆盖会污染默认树，导致
     * "恢复出厂设置后仍然是用户改过的值"这种极难排查的问题。
     *
     * 成本：约 500 个节点的递归拷贝，仅在配置变更时执行一次，且不在主线程。
     */
    fun deepCopy(node: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(node.size * 2)
        for ((k, v) in node) {
            out[k] = when (v) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    deepCopy(v as Map<String, Any?>)
                }
                is List<*> -> ArrayList(v)
                else -> v
            }
        }
        return out
    }

    /** 从 assets 读取默认配置树；失败时返回空树，绝不抛出到调用方。 */
    fun loadDefaults(context: Context, assetName: String = "ui_config.json"): Map<String, Any?> {
        return try {
            context.assets.open(assetName).use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                fromJson(JSONObject(text))
            }
        } catch (t: Throwable) {
            com.h6n.launcher.core.H6Log.w("H6Config", "读取 assets/$assetName 失败，使用空配置", t)
            LinkedHashMap(0)
        }
    }
}
