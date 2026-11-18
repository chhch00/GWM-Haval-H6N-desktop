package com.h6n.launcher.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.h6n.launcher.H6NApplication
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.SignalHub
import com.h6n.launcher.domain.ConfigSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/** 一次配置变更：新旧快照 + 变化的扁平 key 集合。 */
data class ConfigChange(
    val prev: ConfigSnapshot,
    val next: ConfigSnapshot,
    val keys: Set<String>
)

/**
 * 桌面主页的状态出口。
 *
 * ## 关键约束：这里不持有 Activity、不做 IO
 * ViewModel 只把两条流暴露出去：配置、车身信号。
 * 屏幕旋转 / 页面重建时数据不会丢，也不需要重新读盘。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as H6NApplication).config

    val config: StateFlow<ConfigSnapshot> = repo.state
    val signals: StateFlow<CarSignal> = SignalHub.state

    /**
     * 配置增量事件。
     *
     * `scan` 保留上一帧快照，`diff` 算出变更 key；空变更不发出。
     * 用 `shareIn(WhileSubscribed)`：没有订阅者时（桌面不在前台）自动停掉，
     * 不占 CPU。注意 replay = 0，所以订阅方在 onStart 时必须先用
     * [config] 的当前值做一次全量绑定，再接收增量。
     */
    val changes: Flow<ConfigChange> = repo.state
        .scan<ConfigSnapshot, Pair<ConfigSnapshot, ConfigSnapshot>?>(null) { acc, next ->
            acc?.let { it.second to next }
        }
        .filterNotNull()
        .map { (prev, next) -> ConfigChange(prev, next, next.diff(prev)) }
        .filter { it.keys.isNotEmpty() }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 0)

    // ---------------- 写入 ----------------

    /** 改单个配置项。DataStore 事务在 IO 线程，调用方无需自己切线程。 */
    fun setConfig(key: String, value: String) {
        viewModelScope.launch { repo.set(key, value) }
    }

    fun resetConfig() {
        viewModelScope.launch { repo.resetToDefault() }
    }
}
