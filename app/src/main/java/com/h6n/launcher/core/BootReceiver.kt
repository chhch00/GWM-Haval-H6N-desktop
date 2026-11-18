package com.h6n.launcher.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.h6n.launcher.core.H6Log as Log

/**
 * 开机广播入口：把「开机任务」交给 [BootTask]。
 *
 * 不需要 `goAsync()` / 前台服务：桌面是 HOME 进程，系统开机后会把它拉起来常驻，
 * 任务本身是「延迟 N 秒 → 执行一个动作」，用主线程 Handler 排队即可。
 * 进程万一在这次执行完之前被杀，下次开机还会再跑（[BootTask] 的间隔判定
 * 只挡同一开机周期内的重复触发）。
 *
 * 除了标准 `BOOT_COMPLETED`，也接 `QUICKBOOT_POWERON` / `HTC.quickboot`：
 * 部分车机用的是"快速开机"，只发厂商自定义那条。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val GRANT_MEDIA = "com.h6n.launcher.GRANT_MEDIA"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context?.applicationContext ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("H6BootReceiver", "收到开机广播：$action")
                runCatching { BootTask.runBoot(app, "开机广播") }
            }
            // 6.0.87：外部「一键激活权限」adb 命令写完通知使用权 setting 后发这个广播，
            // 让桌面立刻重绑通知监听器。8.1 上单纯写 setting 不会触发绑定，
            // 而 requestRebind 只有本进程能调，所以外部命令必须借这条广播兜底。
            GRANT_MEDIA -> {
                Log.i("H6BootReceiver", "收到音乐权限重绑广播")
                runCatching { MediaWatcher.ensureGrant(app) }
            }
        }
    }
}
