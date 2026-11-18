package com.h6n.launcher.core

import android.content.Context
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.data.ConfigRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 冷启动自动备份。
 *
 * 用户要求：本地备份新增一个可选项，**每次冷启动打开 app 自动备份一次配置**，
 * 备份文件最多保留 10 个版本。开关与上次结果存在 `h6_backup` 里，
 * 该 SP 也在 [ConfigBackup] 的白名单中，所以开关本身一样能被备份 / 恢复。
 *
 * 「冷启动」按进程判定：[doneThisProcess] 保证一个进程只跑一次，
 * 恢复配置后的 `recreate()`、旋屏、Activity 重建都不会重复产生版本；
 * 进程被杀掉再打开桌面则算新的冷启动，会再存一份。
 *
 * ★ 不能一进 onCreate 就导出：DataStore 的读盘与旧配置迁移都在
 * [ConfigRepository.attach] 的 IO 收集器里异步进行，此时 `current().overrides`
 * 可能还是空的，导出的备份会丢掉全部界面自定义项。这里在后台线程等
 * `isReady`（默认树 + 覆盖层合并完成）后再写，最多等 15 秒兜底。
 */
object AutoBackup {

    private const val TAG = "H6AutoBak"
    const val SP_NAME = "h6_backup"
    private const val K_AUTO = "auto"
    private const val K_LAST_AT = "lastAt"
    private const val K_LAST_FILE = "lastFile"

    @Volatile
    private var doneThisProcess = false

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 默认开启：装上新版后第一次冷启动就会存一份。 */
    fun enabled(c: Context): Boolean = runCatching {
        sp(c).getBoolean(K_AUTO, true)
    }.getOrDefault(true)

    fun setEnabled(c: Context, on: Boolean) {
        runCatching { sp(c).edit().putBoolean(K_AUTO, on).apply() }
    }

    /** 给设置页看的最近一次结果。 */
    fun lastInfo(c: Context): String = runCatching {
        val p = sp(c)
        val at = p.getLong(K_LAST_AT, 0L)
        if (at <= 0L) "还没自动备份过"
        else "上次 " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(at)) +
            " · " + (p.getString(K_LAST_FILE, "") ?: "")
    }.getOrDefault("-")

    /**
     * MainActivity.onCreate 的唯一挂载点。同步部分只做两次布尔判断，
     * 真正的等待与文件读写都在后台线程，不占启动主线程。
     */
    fun onColdStart(c: Context) {
        if (!enabled(c)) return
        synchronized(this) {
            if (doneThisProcess) return
            doneThisProcess = true
        }
        val app = c.applicationContext
        Thread { runBackup(app) }.apply {
            name = "h6-auto-backup"
            isDaemon = true
        }.start()
    }

    /** 后台线程：等配置就绪 → 导出 → 记结果。失败只写日志，绝不打扰启动。 */
    private fun runBackup(app: Context) {
        val repo = ConfigRepository.get(app)
        var waited = 0
        while (!repo.current().isReady && waited < 15_000) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += 200
        }
        if (!repo.current().isReady) {
            Log.w(TAG, "配置 15 秒未就绪，跳过本次自动备份")
            return
        }
        val r = runCatching { ConfigBackup.export(app, "_自动") }
        r.onSuccess { f ->
            runCatching {
                sp(app).edit()
                    .putLong(K_LAST_AT, System.currentTimeMillis())
                    .putString(K_LAST_FILE, f.name)
                    .apply()
            }
            Log.i(TAG, "冷启动自动备份完成 ${f.name} · ${f.length()} 字节")
        }
        r.onFailure { t -> Log.w(TAG, "冷启动自动备份失败：${t.message}", t) }
    }
}
