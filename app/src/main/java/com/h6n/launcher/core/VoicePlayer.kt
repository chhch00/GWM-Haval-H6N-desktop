package com.h6n.launcher.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log
import java.io.File

/**
 * 场景提示音播放器 —— 移植自未重写版 `com.h6n.launcher.VoicePlayer`（Java）。
 *
 * ## 两级音效来源
 * 先看外部目录 `/sdcard/Android/data/包名/files/voice/…`（用户可以自己换 wav），
 * 再退回 APK 内置 `assets/voice/…`；都没有就当这条不存在，不抛异常。
 *
 * ## 依次播报
 * 开门、挂挡、车窗提醒经常同一秒一起触发，以前后一条会打断前一条，听起来只剩最后一声。
 * 现在正在播时后来的排进队列，播完自动接上，最多排 6 条；同一路径连续触发只留一条。
 *
 * ## 优先级
 * [playPriority] 用于「熄火未关车窗」这类安全提醒：若当前正播普通音，立刻停下、
 * 把被抢占的音塞回队首，优先播提醒；若已有一条高优先级音在播，则按普通规则排队，
 * 避免两个提醒互相抢。
 *
 * 全部操作切到主线程：MediaPlayer 的 onCompletion / onError 回调需要 Looper，
 * 而信号帧来自 Binder / 模拟源的后台线程。
 */
object VoicePlayer {

    private const val TAG = "H6Voice"
    private const val MAX_QUEUE = 6

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, MediaPlayer>()
    private val queue = ArrayDeque<String>()

    @Volatile private var app: Context? = null
    @Volatile private var enabled = true
    private var current: MediaPlayer? = null
    private var currentPath: String? = null
    private var priorityCurrent = false

    /** 最近一次真正出声的文件名，设置页「最近播报」用它做旁证。 */
    @Volatile var lastPlayed: String = ""
        private set

    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    fun setEnabled(e: Boolean) {
        enabled = e
        if (!e) main.post {
            synchronized(this) {
                queue.clear()
                runCatching { current?.pause() }
                current = null
                currentPath = null
                priorityCurrent = false
            }
        }
    }

    fun isEnabled(): Boolean = enabled

    /** 音效文件是否存在（外部覆盖或内置 assets），找不到时车窗提醒会退回 TTS。 */
    fun hasFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val c = app ?: return false
        val ext = File(externalDir(c), path)
        if (ext.isFile) return true
        return runCatching {
            c.assets.open("voice/$path").close()
            true
        }.getOrDefault(false)
    }

    /** 普通播报：正在播就排队。 */
    fun play(path: String?) {
        if (!enabled || path.isNullOrBlank()) return
        main.post { playLocked(path, false) }
    }

    /** 高优先级播报：抢占普通音，安全提醒用。 */
    fun playPriority(path: String?) {
        if (!enabled || path.isNullOrBlank()) return
        main.post { playLocked(path, true) }
    }

    /**
     * 预加载：车一上电就可能挂 R 档，那一声必须最快就绪，
     * 所以先 prepare 倒车音，再管车门车窗，最后才是其它（旧版同样的权重顺序）。
     * 分批 post，避免启动时一次性 prepare 十几个文件卡住主线程。
     */
    fun preload(paths: List<String>) {
        if (!enabled) return
        val ordered = paths.distinct().sortedBy { weight(it) }
        ordered.forEachIndexed { i, p ->
            main.postDelayed({ runCatching { ensure(p) } }, i * 25L)
        }
    }

    /** 0 = 倒车（最急），1 = 车门车窗，2 = 其它。 */
    private fun weight(p: String): Int {
        val s = p.lowercase()
        return when {
            s.contains("gear_r") || s.contains("reverse") || s.contains("radar") -> 0
            s.contains("door") || s.contains("window") || s.contains("trunk") -> 1
            else -> 2
        }
    }

    private fun playLocked(path: String, priority: Boolean): Unit = synchronized(this) {
        val mp = ensure(path) ?: run {
            Log.w(TAG, "找不到音效：$path（外部目录和 assets 都没有）")
            return@synchronized
        }
        val busy = current?.isPlaying == true
        if (busy && (!priority || priorityCurrent)) {
            if (queue.lastOrNull() == path) return@synchronized
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(path)
            Log.i(TAG, "排队 $path（前面还有 ${queue.size} 条）")
            return@synchronized
        }
        if (busy) {
            // 普通音在播：停下、塞回队首，让位给安全提醒
            runCatching { current?.pause() }
            currentPath?.let { queue.addFirst(it) }
            current = null
            currentPath = null
        }
        priorityCurrent = priority
        start(mp, path)
    }

    /** 真正出声：先停旧位置，按当前音量起播。 */
    private fun start(mp: MediaPlayer, path: String): Unit = synchronized(this) {
        current = mp
        currentPath = path
        runCatching { if (mp.isPlaying) mp.pause() }
        runCatching { mp.seekTo(0) }
        val v = SceneStore.volumeF()
        runCatching { mp.setVolume(v, v) }
        runCatching { mp.start() }.onFailure {
            Log.w(TAG, "播放失败 $path → $it")
            current = null
            currentPath = null
            return@synchronized
        }
        lastPlayed = path
        Log.i(TAG, "播放 $path（音量 ${(v * 100).toInt()}%，通道 ${SceneStore.audioName()}）")
    }

    private fun ensure(path: String): MediaPlayer? = synchronized(this) {
        val c = app ?: return null
        val ext = File(externalDir(c), path)
        val src: String
        val key: String
        val asset: Boolean
        if (ext.isFile) {
            src = ext.absolutePath
            key = "file:$src#${SceneStore.audioRoute()}"
            asset = false
        } else {
            if (!hasFile(path)) return null
            src = "voice/$path"
            key = "asset:$src#${SceneStore.audioRoute()}"
            asset = true
        }
        cache[key]?.let { return it }
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(audioAttributes())
            p.setOnErrorListener { m, what, extra ->
                Log.w(TAG, "播放出错 $what/$extra")
                drop(m)
                runCatching { m.release() }
                true
            }
            p.setOnCompletionListener { m -> next(m) }
            if (asset) {
                val af = c.assets.openFd(src)
                p.setDataSource(af.fileDescriptor, af.startOffset, af.length)
                af.close()
            } else {
                p.setDataSource(src)
            }
            p.prepare()
            cache[key] = p
            p
        } catch (t: Throwable) {
            Log.w(TAG, "加载音效失败 $path → $t")
            runCatching { p.release() }
            null
        }
    }

    /** 一条播完，接着播队列里的下一条。 */
    private fun next(m: MediaPlayer): Unit = synchronized(this) {
        if (m !== current) return@synchronized   // 老播放器迟到的回调，不理
        val n = queue.removeFirstOrNull()
        if (n == null) {
            current = null
            currentPath = null
            priorityCurrent = false
            return@synchronized
        }
        val np = ensure(n)
        if (np == null) {
            current = null
            currentPath = null
            next(m)                 // 队列里还有就继续往下找，别卡住
            return@synchronized
        }
        priorityCurrent = false
        start(np, n)
    }

    private fun drop(m: MediaPlayer) = synchronized(this) {
        if (m === current) {
            current = null
            currentPath = null
            priorityCurrent = false
        }
        val it = cache.entries.iterator()
        while (it.hasNext()) if (it.next().value === m) it.remove()
    }

    /** 切换音色包 / 音频通道后清缓存，下一次播报用新文件重建播放器。 */
    fun clearCache() {
        main.post {
            synchronized(this) {
                queue.clear()
                current = null
                currentPath = null
                priorityCurrent = false
                cache.values.forEach { runCatching { it.release() } }
                cache.clear()
            }
        }
    }

    private fun audioAttributes(): AudioAttributes {
        val (usage, type) = SceneStore.audioUsage()
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(type)
            .build()
    }

    private fun externalDir(c: Context): File {
        val d = File(c.getExternalFilesDir(null) ?: c.filesDir, "voice")
        if (!d.exists()) d.mkdirs()
        return d
    }
}
