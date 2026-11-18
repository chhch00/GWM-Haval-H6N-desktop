package com.h6n.launcher.core

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import com.h6n.launcher.core.H6Log as Log

/**
 * 只为拿「当前在播什么歌」而存在的通知监听器。
 *
 * 系统给 NotificationListenerService 绑定的那一刻起，本进程就有资格调
 * `MediaSessionManager.getActiveSessions(componentName)` —— 这是**不需要**
 * `MEDIA_CONTENT_CONTROL`（signature|privileged，`pm grant` 授不了）的唯一放行路径。
 * 拿到列表后交给 [MediaWatcher] 挑一个。
 *
 * 本服务**不读任何通知内容**（不覆写 `onNotificationPosted`），只借用「已启用的监听器」这个身份。
 */
class H6MediaListener : NotificationListenerService() {

    private companion object {
        const val TAG = "H6Media"
    }

    private var manager: MediaSessionManager? = null

    private val onChange = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        runCatching { MediaWatcher.onSessions(this, list) }
            .onFailure { Log.w(TAG, "onActiveSessionsChanged 失败：$it") }
    }

    private fun me() = ComponentName(this, H6MediaListener::class.java)

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        // 注册时就带上自己的 ComponentName，MediaSessionManager 才会按「已启用监听器」放行
        runCatching { manager?.addOnActiveSessionsChangedListener(onChange, me()) }
            .onFailure { Log.w(TAG, "注册会话监听失败：$it") }
        refresh()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
        MediaWatcher.connected = true
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听已断开")
        MediaWatcher.connected = false
        runCatching { MediaWatcher.onSessions(this, null) }
    }

    override fun onDestroy() {
        MediaWatcher.connected = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(onChange) }
        manager = null
        super.onDestroy()
    }

    /** 主动拉一次。授权后、连上后、开机后都要拉，否则要等下一次切歌才显示。 */
    fun refresh() {
        val list = runCatching { manager?.getActiveSessions(me()) }.getOrNull()
        runCatching { MediaWatcher.onSessions(this, list) }
            .onFailure { Log.w(TAG, "getActiveSessions 失败：$it") }
    }
}
