package com.h6n.launcher.data

import android.content.Context
import com.h6n.launcher.core.H6Log as Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.h6n.launcher.domain.ConfigPath
import com.h6n.launcher.domain.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 配置仓库：唯一对外入口。
 *
 * ## 数据流
 * ```
 * assets/ui_config.json ──(一次, IO)──┐
 *                                     ├─ 合并 ─> ConfigSnapshot ─> StateFlow ─> UI(增量刷新)
 * DataStore(h6n_config) ──(Flow)──────┘
 * ```
 * UI 永远只读 [state] 的当前值，不在 onResume 里主动去读磁盘；
 * 写配置只调 [set]，只写变化的那几个 key，不重写整棵树。
 *
 * ## 关于主线程
 * 这个类所有 suspend 方法都必须在非主线程调用（默认在 IO 调度器）。
 * 唯一"看起来同步"的 [current] 只是读内存里的 StateFlow，耗时与读变量相当。
 */
class ConfigRepository private constructor(private val appContext: Context) {

    private val store = appContext.h6nConfigStore

    /** 默认树懒加载：首次构建快照时读一次 assets，之后常驻内存。 */
    private val defaults: Map<String, Any?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConfigTree.loadDefaults(appContext)
    }

    /**
     * 初始值是空快照，绝不在构造期读 assets。
     *
     * 构造发生在 Application.onCreate（主线程），如果这里顺手把默认树
     * 读出来，就又把磁盘 IO 挪回主线程了 —— 正是这次重写要消灭的东西。
     * 空快照会在 [attach] 的 IO 收集器里被第一份真实快照替换，
     * UI 侧用 [ConfigSnapshot.isReady] 判断是否需要先画骨架。
     */
    private val _state = MutableStateFlow(ConfigSnapshot(emptyMap(), emptyMap(), 0L))

    val state: StateFlow<ConfigSnapshot> = _state.asStateFlow()

    /** 内存中的当前快照（无 IO、无锁，可在主线程调用）。 */
    fun current(): ConfigSnapshot = _state.value

    /**
     * 挂到进程级 scope 上：先做一次旧配置迁移，然后常驻收集 DataStore。
     *
     * 只调用一次（在 Application 里），重复调用会产生多个收集器。
     */
    fun attach(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            migrateLegacyIfNeeded()
            store.data
                .catch { e ->
                    // 磁盘读失败时不要让整个桌面挂掉，退化成默认配置继续跑
                    if (e is IOException) {
                        Log.w(TAG, "读取配置失败，本次使用默认配置", e)
                        emit(emptyPreferences())
                    } else {
                        throw e
                    }
                }
                .collect { prefs -> _state.value = buildSnapshot(prefs) }
        }
    }

    // ---------------- 写入 ----------------

    /**
     * 批量写入覆盖项，一次事务提交。
     *
     * @param updates 扁平 key -> 字符串值，例如 `mapOf("page2.tireUnit" to "kpa")`。
     *                值为字符串是为了和旧覆盖层格式统一，写入时由 ConfigTree 还原类型。
     */
    suspend fun set(updates: Map<String, String>) {
        if (updates.isEmpty()) return
        val clean = updates.filterKeys { !ConfigPath.isMeta(it) }
        if (clean.isEmpty()) return
        store.edit { prefs ->
            for ((k, v) in clean) prefs[stringPreferencesKey(k)] = v
            prefs[longPreferencesKey(ConfigPath.META_STAMP)] = System.currentTimeMillis()
        }
    }

    suspend fun set(key: String, value: String) = set(mapOf(key to value))

    /** 删除某条覆盖，回落到 assets 默认值。 */
    suspend fun clear(key: String) {
        if (ConfigPath.isMeta(key)) return
        store.edit { prefs -> prefs.remove(stringPreferencesKey(key)) }
    }

    /** 恢复出厂：清空全部覆盖项。 */
    suspend fun resetToDefault() {
        store.edit { prefs ->
            val names = prefs.asMap().keys.map { it.name }.filterNot { ConfigPath.isMeta(it) }
            for (name in names) prefs.remove(stringPreferencesKey(name))
            prefs[longPreferencesKey(ConfigPath.META_STAMP)] = System.currentTimeMillis()
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 由 DataStore 快照构建不可变配置快照。
     * @param prefs null 表示还没读出来，只用默认树。
     */
    private fun buildSnapshot(prefs: Preferences?): ConfigSnapshot {
        val overrides = LinkedHashMap<String, String>(64)
        var stamp = 0L
        if (prefs != null) {
            for ((k, v) in prefs.asMap()) {
                val name = k.name
                if (ConfigPath.isMeta(name)) {
                    if (name == ConfigPath.META_STAMP) {
                        stamp = (v as? Long) ?: v.toString().toLongOrNull() ?: 0L
                    }
                    continue
                }
                overrides[name] = v as? String ?: v.toString()
            }
        }

        val root = ConfigTree.deepCopy(defaults)
        for ((k, raw) in overrides) ConfigTree.applyOverride(root, k, raw)
        return ConfigSnapshot(root = root, overrides = overrides, stamp = stamp)
    }

    /**
     * 把旧版 SharedPreferences 覆盖层（`h6_cfg`）一次性搬到 DataStore。
     *
     * 只做一次，靠 `__migrated_v6` 标记。旧文件保持不动，万一需要回退到
     * 旧版 APK，原覆盖层仍然完好。
     */
    private suspend fun migrateLegacyIfNeeded() {
        val existing = runCatching { store.data.first() }.getOrNull() ?: return
        if (existing[booleanPreferencesKey(ConfigPath.META_MIGRATED)] == true) return

        val legacy = runCatching {
            appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).all
        }.getOrNull().orEmpty()

        val pairs = HashMap<String, String>(legacy.size)
        for ((k, v) in legacy) {
            if (ConfigPath.isMeta(k)) continue
            if (v == null) continue
            pairs[k] = v.toString()
        }

        store.edit { prefs ->
            for ((k, v) in pairs) prefs[stringPreferencesKey(k)] = v
            prefs[booleanPreferencesKey(ConfigPath.META_MIGRATED)] = true
            prefs[longPreferencesKey(ConfigPath.META_STAMP)] = System.currentTimeMillis()
        }
        Log.i(TAG, "旧配置迁移完成：${pairs.size} 项（h6_cfg -> $STORE_DISPLAY_NAME）")
    }

    companion object {
        private const val TAG = "H6Config"
        private const val LEGACY_PREFS = "h6_cfg"
        private const val STORE_DISPLAY_NAME = "DataStore(h6n_config)"

        @Volatile
        private var instance: ConfigRepository? = null

        /** 进程级单例；DataStore 同文件只能有一个实例，务必只从这里取。 */
        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }
}
