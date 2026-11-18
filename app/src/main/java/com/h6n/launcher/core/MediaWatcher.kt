package com.h6n.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 当前在播的音乐（桌面机油卡中间那条横条）。
 *
 * 只保留显示需要的字段，封面已经缩到 [ART_PX]，避免把原图（动辄 1000×1000）一路传到 UI。
 */
data class NowPlaying(
    val pkg: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean
) {
    /**
     * 文本指纹（不含封面）。
     *
     * 轮询兜底时先用它比对：一致就直接返回，连专辑图都不解码 ——
     * `getBitmap` + 裁方 + 缩放是整个取数流程里最贵的一步。
     */
    fun id(): String = "$pkg|$title|$artist|$playing"

    /**
     * 完整指纹：只有它会变才通知 UI，避免 session 反复回调时白刷一遍 TextView。
     *
     * 6.0.79：封面必须按**对象身份**参与，不能再用 `(art != null)` 这个布尔位。
     * 外部播放器切歌时经常先发「新歌名 + 上一首的封面」，真封面要等图片异步加载完才到；
     * 这两次文本相同、`art != null` 又都是 true，旧算法会把后一次当成重复直接丢掉，
     * 卡片就永久停在上一首的封面。[MediaWatcher.metaBitmap] 缓存了缩放结果，
     * 保证「没换图」时拿到的是同一个 Bitmap 对象，不会因此反复白刷。
     */
    fun key(): String = id() + "|" + (art?.let { System.identityHashCode(it) } ?: 0)
}

/**
 * 读「谁在放什么歌」。
 *
 * ## 为什么必须走 MediaSession
 * 车机 ICarInfo 的 571 条常量里**没有任何媒体项**（`grep -i media|music|song` 零命中），
 * `CarAPI可读与可控信号清单` 里也只有 `CarAudioManager`（音量/静音，要 signature 权限）。
 * 所以歌名只能从 android.media.session 拿 —— 嘟嘟桌面（`com.dudu.autoui`）也是这么做的：
 * 它的 `service/musicInfo/GetMusicInfoService` 就是个 NotificationListenerService，
 * 在 `onCreate` 里 `MediaSessionManager.addOnActiveSessionsChangedListener(this, componentName)`，
 * 然后 `getActiveSessions(componentName)` 取列表、按 `PlaybackState.STATE_PLAYING == 3` 挑一个。
 *
 * ## 为什么需要「通知使用权」
 * `MediaSessionManager.getActiveSessions(ComponentName)` 会先检查调用方是否有
 * `MEDIA_CONTENT_CONTROL`（signature|privileged，`pm grant` 授不了，嘟嘟虽然申请了也拿不到），
 * 没有权限时**唯一放行条件**是「传进来的那个 ComponentName 是本机已启用的通知监听器」。
 * 所以本应用必须先有一个启用的 NotificationListenerService，见 [H6MediaListener]。
 *
 * ## 6.0.79 起外部播放器也能控制
 * `MediaController.getTransportControls()` 在无 MEDIA_CONTENT_CONTROL 时不可用（会抛
 * SecurityException），通知监听器只放行读取；各家私有 SDK 又得逐个适配（嘟嘟为此内置了
 * 酷狗/QQ音乐/网易云的插件，还额外申请 `com.tencent.wecarflow.PLAY_CONTROL` 等权限）。
 * 改走**媒体按键**这条通用通道：[sendMediaKey] 把 KEYCODE_MEDIA_* 送进卡片显示的那一个
 * 会话，失败再退到 `AudioManager.dispatchMediaKeyEvent`（车机方向盘媒体键同款路径）。
 * U 盘音乐是本应用自己放的，仍由 `LocalPlayer` 直接控制，不绕这一圈。
 */
object MediaWatcher {

    private const val TAG = "H6Media"

    /** 封面解码目标边长（px）。机油卡那条横条最高也就 60dp×1.5≈90px，144 已经够。 */
    const val ART_PX = 144

    /**
     * 不当作「音乐」的包名。
     *
     * 导航 TTS 也挂 MediaSession（高德播报时 state 同样是 PLAYING），
     * 不排除的话导航一说话，音乐卡就被"前方路口左转"占掉。
     */
    private val SKIP = setOf(
        "com.autonavi.amapauto",
        "com.autonavi.amap",
        "com.autonavi.auto",
        "com.h6n.launcher",
        "android"
    )

    private val listeners = CopyOnWriteArrayList<(NowPlaying?) -> Unit>()

    /** 回调线程不是主线程（来自 NotificationListenerService 的 Binder 回调），统一在这里转一下。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 主动拉取用的单线程：`getActiveSessions` + 读 metadata 都是 Binder，不能放主线程。 */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "h6-media").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    @Volatile
    private var now: NowPlaying? = null

    @Volatile
    private var lastKey = ""

    @Volatile
    private var pulling = false

    // -------------------------------------------------- 逐会话回调（切歌即时更新）
    //
    // v6.0.72 的教训：`OnActiveSessionsChangedListener` 只在**活跃会话集合变化**时才回调，
    // 也就是「某个 App 开始/停止播放、会话被创建/销毁」。同一个播放器里换了下一首歌，
    // session 对象不变、集合不变 —— 一次回调都不会来，卡片就永远停在第一首歌。
    // 想知道换歌只有一条路：给每个 controller 挂 `MediaController.Callback`，
    // 听 `onMetadataChanged` / `onPlaybackStateChanged`。

    /** 最近一次拿到的活跃会话。切歌回调只重读这些 controller，不必再 `getActiveSessions`。 */
    @Volatile
    private var active: List<MediaController> = emptyList()

    /** 应用上下文：回调来自 Binder 线程，没有 Context 可传，这里存一份。 */
    @Volatile
    private var appCtx: Context? = null

    /** 已挂回调的会话（key 是 sessionToken，它按底层 Binder 比较，同一会话的两次获取相等）。 */
    private val ctrls = java.util.concurrent.ConcurrentHashMap<MediaSession.Token, MediaController>()

    /**
     * 切歌/暂停时播放器通常连发 `setMetadata` 和 `setPlaybackState`，
     * 两次回调合并成一次取数，避免一次切歌解码两遍专辑图。
     */
    private val reemitTask = Runnable { fullRefresh() }

    /**
     * 6.0.79：封面晚到时补读。用两个不同 Runnable，免得 `removeCallbacks` 把彼此抵消。
     *
     * 不少播放器（汽水音乐等）切歌只发一次 metadata —— 文本立刻更新，专辑图却是后台
     * 异步加载完才塞进会话，**不会再触发 onMetadataChanged**。只靠回调就永远读不到真封面，
     * 卡片会一直挂着上一首的图。补两枪全量重读即可，[metaBitmap] 有缓存，代价很小。
     */
    private val artLate1 = Runnable { fullRefresh() }
    private val artLate2 = Runnable { fullRefresh() }

    private fun fullRefresh() {
        val app = appCtx ?: return
        io.execute {
            runCatching { emit(app, active, cheap = false) }
                .onFailure { Log.w(TAG, "切歌刷新失败：$it") }
        }
    }

    private val ctrlCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = reemit()
        override fun onPlaybackStateChanged(state: PlaybackState?) = reemit()

        /** 会话没了就不能再拿缓存列表去 pick，直接重拉一次。 */
        override fun onSessionDestroyed() {
            main.post { appCtx?.let { refreshNow(it) } }
        }
    }

    private fun reemit() {
        main.removeCallbacks(reemitTask)
        main.postDelayed(reemitTask, 120L)
        // 封面异步晚到：700ms / 1800ms 各补读一次当前 metadata，把真封面拉回来。
        main.removeCallbacks(artLate1)
        main.removeCallbacks(artLate2)
        main.postDelayed(artLate1, 700L)
        main.postDelayed(artLate2, 1800L)
    }

    /**
     * 轻量自检。由桌面每秒一次的 [com.h6n.launcher.ui.main.SixGrid.OilCard.bind] 驱动，
     * 内部自己限流，调用方不必管节奏。
     *
     * 兜的是「播放器换了歌却不更新 metadata 回调」这种情况 —— 只能重读。
     * 每 5 拍（≈20 秒）做一次完整 `getActiveSessions`，用来把「通知监听服务被杀、
     * [active] 已过期」这种状态救回来；其余拍子只重读已有 controller 的文本，成本很低。
     */
    private var pollTick = 0

    fun poll(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        pollTick++
        if (pollTick % 5 == 0) {
            refreshNow(app)
            return
        }
        if (active.isEmpty()) return
        io.execute {
            runCatching { emit(app, active, cheap = true) }
                .onFailure { Log.w(TAG, "轮询失败：$it") }
        }
    }

    /**
     * 给新出现的会话挂回调，把已经消失的摘掉。
     *
     * 必须在有 Looper 的线程（这里统一回主线程）：`registerCallback` 内部要建 Handler。
     */
    private fun syncCallbacks(list: List<MediaController>) {
        // 防御：正常情况下活跃会话只有一两个。真堆到几十个说明 sessionToken 的去重失效了
        // （个别魔改 ROM 的 Token.equals 不走底层 Binder），宁可全部重挂也不要无限堆积。
        if (ctrls.size > 64) dropCallbacks()
        val seen = HashSet<MediaSession.Token>(list.size)
        for (c in list) {
            val tok = runCatching { c.sessionToken }.getOrNull() ?: continue
            seen.add(tok)
            if (ctrls.containsKey(tok)) continue
            ctrls[tok] = c
            runCatching { c.registerCallback(ctrlCb, main) }
                .onFailure { Log.w(TAG, "注册会话回调失败：$it") }
        }
        val it = ctrls.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in seen) continue
            runCatching { e.value.unregisterCallback(ctrlCb) }
            it.remove()
        }
    }

    private fun dropCallbacks() {
        for (c in ctrls.values) runCatching { c.unregisterCallback(ctrlCb) }
        ctrls.clear()
    }

    // ------------------------------------------------------------ 自检状态
    //
    // v6.0.72：这一版之前，「没显示」和「没数据」在界面上长得一模一样 —— 用户只看到机油卡
    // 中间空着，分不清是没授权、没放歌、还是服务没起来。下面这几个字段就是给设置页
    // 的诊断行用的，把每一步都摊开。

    /** [H6MediaListener] 是否被系统真正绑定（`onListenerConnected`）。 */
    @Volatile
    var connected: Boolean = false
        internal set

    /** 最近一次 `getActiveSessions` 返回的会话个数，-1 表示还没成功拉过。 */
    @Volatile
    var sessionCount: Int = -1
        private set

    /** 最近一次成功拉取的时间戳；0 表示从未成功。 */
    @Volatile
    var lastOkMs: Long = 0L
        private set

    /** 最近一次失败原因；null 表示没有失败。 */
    @Volatile
    var lastErr: String? = null
        private set

    /** 已挂上切歌回调的会话数。0 表示切歌不会即时刷新（只剩 4 秒轮询兜底）。 */
    val callbackCount: Int get() = ctrls.size

    /** UI 侧订阅：主线程回调，参数为 null 表示「没有在放的音乐」。 */
    fun addListener(l: (NowPlaying?) -> Unit) {
        listeners.addIfAbsent(l)
        l(now)
    }

    fun removeListener(l: (NowPlaying?) -> Unit) {
        listeners.remove(l)
    }

    fun current(): NowPlaying? = now

    // ---------------------------------------------------------------- 主动拉取

    /**
     * 桌面自己去拉一次，不等 [H6MediaListener] 的回调。
     *
     * ## 为什么桌面能拉
     * `MediaSessionManager.getActiveSessions(ComponentName)` 的权限检查**只看传进去的那个
     * ComponentName 在不在 `enabled_notification_listeners` 里**，并**不检查调用方是不是那个
     * Service**。也就是说：只要本应用的通知监听器已被启用，应用进程里任何代码传入自己的监听器
     * 组件名都能拿到 session 列表。
     *
     * ## 为什么必须自己拉
     * v6.0.71 只靠 Service 的 `onActiveSessionsChanged` 回调。那条路有两个静默失效的场景：
     * 1. 授权那一刻 Service 可能还没被系统绑定，`onCreate` 里的首次 `refresh()` 就不会发生；
     * 2. Service 被车机省电策略杀掉后不会自己回来，桌面从此永远空着，且没有任何提示。
     *
     * 所以桌面在挂上/回到前台时补一枪，Service 回调正常时这一枪是重复的、结果一致。
     */
    fun refreshNow(ctx: Context) {
        if (!isGranted(ctx)) {
            lastErr = "通知使用权未开启"
            return
        }
        if (pulling) return
        pulling = true
        val app = ctx.applicationContext
        appCtx = app
        io.execute {
            try {
                val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                if (msm == null) {
                    lastErr = "系统没有 MediaSessionManager"
                    return@execute
                }
                val list = msm.getActiveSessions(componentName(app))
                sessionCount = list.size
                lastOkMs = System.currentTimeMillis()
                lastErr = null
                onSessions(app, list)
            } catch (t: Throwable) {
                lastErr = t.javaClass.simpleName + "：" + (t.message ?: "")
                Log.w(TAG, "主动拉取失败：$t")
            } finally {
                pulling = false
            }
        }
    }

    /** 把一个会话摊平成一行文字（设置页「列出所有播放会话」用）。state 3 = 正在播放。 */
    private fun dump(c: MediaController): String {
        val pkg = runCatching { c.packageName }.getOrNull().orEmpty()
        val st = runCatching { c.playbackState?.state }.getOrNull() ?: -1
        val t = runCatching {
            c.metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.trim()
        }.getOrNull()
        val mark = if (pkg in SKIP) "（已忽略）" else ""
        return "$pkg · state=$st · " + (t?.take(24) ?: "无标题") + mark
    }

    /**
     * 列出当前所有活跃会话（阻塞，仅设置页点击时调用）。
     *
     * 这一行是排查「为什么机油卡没有歌」的最终依据：如果这里连播放器都列不出来，
     * 问题在授权/系统；如果能列出来但 state 不是 3，问题在播放器没有上报播放态。
     */
    fun sessionsDump(ctx: Context): List<String> {
        if (!isGranted(ctx)) return listOf("未开启通知使用权 —— 先点上面的「开启」")
        return runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return@runCatching listOf("系统没有 MediaSessionManager")
            val list = msm.getActiveSessions(componentName(ctx))
            if (list.isEmpty()) listOf("0 个活跃会话 —— 播放器还没开始放")
            else list.map { dump(it) }
        }.getOrElse {
            listOf("读取失败：" + it.javaClass.simpleName + "（" + (it.message ?: "") + "）")
        }
    }

    /** 一句话结论，给设置页的诊断行。 */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        sb.append(if (isGranted(ctx)) "授权 ✔" else "授权 ✘ 未开启通知使用权")
        sb.append(" · ")
        sb.append(if (connected) "服务已连" else "服务未连")
        sb.append(" · ")
        sb.append(if (sessionCount < 0) "会话未取到" else "会话 $sessionCount 个")
        sb.append(" · ")
        sb.append("回调 $callbackCount")
        sb.append(" · ")
        sb.append(
            if (lastOkMs <= 0L) "从未拉取成功"
            else "上次成功 " + (System.currentTimeMillis() - lastOkMs) / 1000 + " 秒前"
        )
        lastErr?.let { sb.append("\n").append(it) }
        val np = now
        sb.append("\n").append(
            if (np == null || np.title.isEmpty()) "当前：没有在播放"
            else "当前：" + np.title + (if (np.playing) "（播放中）" else "（已暂停）")
        )
        return sb.toString()
    }

    // ---------------------------------------------------------------- 权限

    private fun componentName(ctx: Context): ComponentName =
        ComponentName(ctx.packageName, H6MediaListener::class.java.name)

    /**
     * 通知使用权是否已经给到本应用的监听器。
     *
     * 直接读 `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`（形如 `pkg/cls:pkg2/cls2`），
     * 比去查 NotificationManager 的内部状态可靠 —— 车机上没有现成的公开 API 能查。
     */
    fun isGranted(ctx: Context): Boolean {
        val flat = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        if (flat.isEmpty()) return false
        return flat.split(':').any { s ->
            ComponentName.unflattenFromString(s)?.packageName == ctx.packageName
        }
    }

    /**
     * 尝试自动打开通知使用权。
     *
     * 本应用持有 `WRITE_SECURE_SETTINGS`（走的是无线 ADB 授权那条线），
     * 所以可以直接把组件名追加进 `enabled_notification_listeners`，再 `requestRebind` 让系统重新绑定。
     * 没这个权限时不硬来，返回 false 让调用方引导用户去系统设置点一下。
     */
    fun tryAutoGrant(ctx: Context): Boolean {
        if (isGranted(ctx)) {
            // 已授权 ≠ 已绑定：`settings put` 写完，系统的 NotificationManagerService
            // 不一定立刻重绑监听器（实测车机上要重启进程才连上）。这里补一枪 rebind。
            if (!connected) rebind(ctx)
            return true
        }
        val cr = ctx.contentResolver
        val flat = runCatching {
            Settings.Secure.getString(cr, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        val me = componentName(ctx).flattenToString()
        val next = if (flat.isEmpty() || flat == "null") me else "$flat:$me"
        val ok = runCatching {
            Settings.Secure.putString(cr, "enabled_notification_listeners", next)
        }.getOrDefault(false)
        Log.i(TAG, "tryAutoGrant put=$ok")
        if (!ok) return false
        rebind(ctx)
        return isGranted(ctx)
    }

    /** 让系统重新绑定本应用的监听器（Android 24+）。 */
    private fun rebind(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        // 静态方法不随子类继承，必须写基类名
        runCatching {
            android.service.notification.NotificationListenerService
                .requestRebind(componentName(ctx))
        }
    }

    /**
     * 确保「能读到歌」这件事尽量不需要用户动手。
     *
     * 本应用持有 `WRITE_SECURE_SETTINGS`（走无线 ADB 那条线授权的），所以绝大多数情况下
     * 可以静默把通知使用权写进去；写不进去（用户换机、没走过 ADB 授权）才回落到界面提示。
     *
     * @return true 表示已经具备读取条件
     */
    fun ensureGrant(ctx: Context): Boolean {
        val app = ctx.applicationContext ?: ctx
        val ok = tryAutoGrant(app)
        if (ok) {
            // rebind 是异步的，稍等一下再拉；同时立刻拉一次（setting 已生效时这一步就够了）
            refreshNow(app)
            main.postDelayed({ refreshNow(app) }, 1500L)
        }
        return ok
    }

    /** 系统设置里「通知使用权」那一页；车机若裁掉了这个 Activity 会抛，调用方回落到应用详情页。 */
    fun settingsIntent(): android.content.Intent =
        android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------------------------------------------------------------- 服务回调

    /**
     * 由 [H6MediaListener] 调用：活跃 session 变了就重新挑一个。
     *
     * 注意这里是 NotificationListenerService 的 Binder 回调线程，不是主线程 ——
     * `getMetadata()` 是同步 Binder 调用，放在主线程会让桌面掉帧，所以整段都在这个线程里做完，
     * 只把结果（已缩放的 Bitmap + 几个 String）丢给主线程。
     */
    fun onSessions(ctx: Context, controllers: List<MediaController>?) {
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        if (controllers == null) {
            // 服务断开：会话列表作废，回调也一并摘掉，卡片回到「没有在播放」
            active = emptyList()
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) dropCallbacks()
            else main.post { dropCallbacks() }
            emit(app, emptyList(), cheap = false)
            return
        }
        // 所有取数路径（Service 回调 / 桌面主动拉 / 授权后 refresh）都汇到这里，统计只做一次
        sessionCount = controllers.size
        lastOkMs = System.currentTimeMillis()
        lastErr = null
        active = controllers
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            syncCallbacks(controllers)
        } else {
            main.post { syncCallbacks(controllers) }
        }
        emit(app, controllers, cheap = false)
    }

    /**
     * 从会话列表里取数并推到 UI。
     *
     * @param cheap true 表示这是轮询路径：文本指纹没变就直接返回，不去解码专辑图。
     */
    private fun emit(app: Context, list: List<MediaController>, cheap: Boolean) {
        val picked = pick(list)
        if (picked == null) {
            if (cheap && now == null) return
            publish(app, null)
            return
        }
        val (c, md) = picked
        val pkg = runCatching { c.packageName }.getOrNull().orEmpty()
        val title = runCatching {
            md.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.trim()
        }.getOrNull().orEmpty()
        val artist = runCatching {
            md.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()?.trim()
        }.getOrNull()
            ?: runCatching {
                md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString()?.trim()
            }.getOrNull().orEmpty()
        val playing = runCatching { c.playbackState?.state == PlaybackState.STATE_PLAYING }
            .getOrDefault(false)
        if (cheap && now?.id() == "$pkg|$title|$artist|$playing") return
        val art = runCatching { metaBitmap(app, md, pkg) }.getOrNull()
        publish(
            app,
            NowPlaying(
                pkg = pkg,
                appLabel = appLabel(app, pkg),
                title = title,
                artist = artist,
                art = art,
                playing = playing
            )
        )
    }

    /**
     * 从一堆 session 里挑出"正在放歌"的那个。
     *
     * 规则（按优先级）：
     * 1. 丢开 [SKIP] 里的包和没有标题的 session（导航播报、系统提示音的 metadata 是空的）；
     * 2. 优先 `STATE_PLAYING`；
     * 3. 都不在播时保留最后停下的那个 —— 暂停后歌名还留在卡上比突然空掉更自然。
     */
    private fun pick(list: List<MediaController>): Pair<MediaController, MediaMetadata>? {
        var first: Pair<MediaController, MediaMetadata>? = null
        for (c in list) {
            val pkg = runCatching { c.packageName }.getOrNull() ?: continue
            if (pkg in SKIP) continue
            val md = runCatching { c.metadata }.getOrNull() ?: continue
            val t = runCatching {
                md.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.trim()
            }.getOrNull()
            if (t.isNullOrEmpty()) continue
            val playing = runCatching { c.playbackState?.state == PlaybackState.STATE_PLAYING }
                .getOrDefault(false)
            if (playing) return c to md
            if (first == null) first = c to md
        }
        return first
    }

    private fun publish(ctx: Context, np: NowPlaying?) {
        ext = np
        resolve()
    }

    // -------------------------------------------------- 本地播放器插槽（v6.0.73）
    //
    // U 盘那首歌是**我们自己**放的（车机自带播放器不走 MediaSession，读不到），
    // 所以它不出现在 `getActiveSessions` 里 —— 本包名还在 [SKIP] 里，避免和外部的自己撞车。
    // [LocalPlayer] 每次状态变化直接往这里推一份，和「外部会话」两个来源在这里合流。

    @Volatile
    private var ext: NowPlaying? = null

    @Volatile
    private var localNow: NowPlaying? = null

    /**
     * 本地播放器（U 盘）上报当前曲目。传 null 表示本地播放器没有内容。
     *
     * 优先级：本地在播 → 本地；本地暂停但外部也没在播 → 本地（暂停后歌名留在卡上更自然）；
     * 外部在播 → 外部。这样插 U 盘放歌时空了不会把卡片清掉，一插手机放歌也能立刻切过去。
     */
    fun setLocal(np: NowPlaying?) {
        localNow = np
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) resolve()
        else main.post { resolve() }
    }

    private fun resolve() {
        val l = localNow
        val e = ext
        val np = if (l != null && l.title.isNotEmpty() && (l.playing || e == null || !e.playing)) l else e
        deliver(np)
    }

    private fun deliver(np: NowPlaying?) {
        val prev = now
        val k = np?.key() ?: "-"
        // 除字符串指纹外再比一次封面引用：identityHashCode 理论上会碰撞，
        // 这一位保证「换了图」绝不会被去重吞掉（6.0.79）。
        if (k == lastKey && prev?.art === np?.art) return
        lastKey = k
        now = np
        if (np == null || (!np.playing && np.art == null && np.title.isEmpty())) {
            Log.i(TAG, "无在播音乐")
        } else {
            Log.i(TAG, "在播：${np.pkg} / ${np.title} / ${np.artist} art=${np.art != null}")
        }
        for (l in listeners) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runCatching { l(np) }
            } else {
                main.post { runCatching { l(np) } }
            }
        }
    }

    /** 同一张原图只缩放一次，容量 8 张小图（约 0.6MB）。 */
    private val artMemo = LinkedHashMap<Int, Bitmap>()
    private val artMemoLock = Any()

    /**
     * 封面：优先专辑图，退到单曲图，最后退到显示图标（很多播放器只给 displayIcon）。
     * 都没有时留 null，UI 会用 app 图标兜底。
     *
     * 6.0.79：返回**稳定**的 Bitmap 对象。[NowPlaying.key] 靠对象身份判断「换没换封面」，
     * 若每次刷新都重新裁一张，即使内容一模一样也会被当成换图而反复刷 UI；
     * 反过来，真换了图则一定是新对象，去重不会再把晚到的封面吞掉。
     */
    private fun metaBitmap(ctx: Context, md: MediaMetadata, pkg: String): Bitmap? {
        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (raw.width <= 0 || raw.height <= 0) return null
        if (raw.width <= ART_PX && raw.height <= ART_PX) return raw
        val rid = System.identityHashCode(raw)
        val cached = synchronized(artMemoLock) {
            artMemo[rid]?.takeIf { !it.isRecycled }
        }
        if (cached != null) return cached
        // 居中裁正方形再缩：直接 createScaledBitmap 会把方形封面拉变形
        val side = Math.min(raw.width, raw.height)
        val x = (raw.width - side) / 2
        val y = (raw.height - side) / 2
        val out = runCatching {
            Bitmap.createBitmap(raw, x, y, side, side)
                .let { if (it === raw) it else Bitmap.createScaledBitmap(it, ART_PX, ART_PX, true) }
        }.getOrNull() ?: return raw
        synchronized(artMemoLock) {
            while (artMemo.size >= 8) artMemo.remove(artMemo.keys.iterator().next())
            artMemo[rid] = out
        }
        return out
    }

    /** app 名（"QQ音乐" / "酷狗音乐"），拿不到就用包名末段。 */
    fun appLabel(ctx: Context, pkg: String): String {
        if (pkg.isEmpty()) return ""
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    /** app 图标，用作没有封面时的兜底图。 */
    fun appIcon(ctx: Context, pkg: String): Drawable? = runCatching {
        ctx.packageManager.getApplicationIcon(pkg)
    }.getOrNull()

    /**
     * 6.0.79：把「上一首 / 播放暂停 / 下一首」发给**外部播放器**。
     *
     * `getTransportControls()` 走不通（见文件头），但媒体按键是另一条通道：
     * ① 先定向给卡片显示的那一个会话发 `dispatchMediaButtonEvent`（公开 API，模拟
     *    遥控器 / 方向盘按键，不需要 MEDIA_CONTENT_CONTROL）；
     * ② 个别 ROM 会吞掉定向按键、或会话刚好过期，再退到
     *    `AudioManager.dispatchMediaKeyEvent`，由系统把媒体键派给当前活跃会话。
     *
     * DOWN + UP 都发：播放器按标准 KeyEvent 只在其中一个 action 上处理一次，不会双触发。
     * 全程 runCatching —— 按钮绝不能把桌面搞崩。
     */
    fun sendMediaKey(ctx: Context, keyCode: Int): Boolean {
        val app = ctx.applicationContext ?: ctx
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        val target = now?.pkg.orEmpty()
        var sent = false
        if (target.isNotEmpty()) {
            val ctrl = active.firstOrNull { c ->
                runCatching { c.packageName }.getOrNull() == target
            }
            if (ctrl != null) {
                sent = runCatching {
                    val a = ctrl.dispatchMediaButtonEvent(down)
                    val b = ctrl.dispatchMediaButtonEvent(up)
                    a || b
                }.getOrDefault(false)
            }
        }
        if (!sent) {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                sent = runCatching {
                    am.dispatchMediaKeyEvent(down)
                    am.dispatchMediaKeyEvent(up)
                    true
                }.getOrDefault(false)
            }
        }
        Log.i(TAG, "控制键 $keyCode → $target，送达=$sent")
        return sent
    }

    /** 打开发起播放的那个 app（点音乐条的动作）。 */
    fun openApp(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return runCatching {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        }.getOrDefault(false)
    }

    /** Drawable → Bitmap（app 图标转封面用）。 */
    fun toBitmap(d: Drawable, px: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        return b
    }

    @Suppress("unused")
    private fun pm(ctx: Context): PackageManager = ctx.packageManager
}
