package com.h6n.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 进程内唯一的配置存储。
 *
 * 用 Preferences DataStore 而不是 SharedPreferences 的原因：
 *  - 写入走 `edit {}` 事务，在 IO 线程串行提交，主线程不做 fsync，
 *    规避车机慢速 eMMC 上 `apply()` 偶发卡顿与 `commit()` 的 ANR；
 *  - 读取是一次性的 Flow，天然支持"只通知变化"；
 *  - 单进程写入顺序有保证，不会出现旧版 `__stamp` 竞争。
 *
 * 注意：DataStore 只允许一个实例访问同一个文件，本工程所有访问都必须
 * 经过 [ConfigRepository]，不要在其他地方再 `preferencesDataStore(...)` 同名实例。
 */
private const val STORE_NAME = "h6n_config"

val Context.h6nConfigStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)
