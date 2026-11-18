package com.h6n.launcher.core

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** 一首 U 盘里的歌。只存显示和播放需要的东西。 */
data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L
)

/**
 * U 盘本地音乐播放器 —— 从旧版（smali 重打包那版）的 `LocalPlayer` 整体移植。
 *
 * ## 为什么必须有它
 * 车机自带的 U 盘播放器（`com.beantechs.mediaplayerservice` / `BeanMediaPlayer`）**不走
 * MediaSession**：把 `BeanMediaPlayer-app.apk`、`BeanMediaPlayerService-app.apk`、
 * `BeanMediaAgentService-app.apk` 三个包的 `classes.dex` 全扫一遍，
 * `MediaSession` / `MediaController` / `setPlaybackState` / `METADATA_KEY` **全为 0**；
 * 它对外只有私有 AIDL（`IMediaControl` / `IMediaService` / `ISession`），且整包被
 * 梆梆壳（`com.secneo.apkwrapper.AW`）加固、跑在 `android.uid.system` 下。
 * 也就是说：卡播放器插 U 盘放歌时，机油卡永远读不到它。要让它显示，只能自己放。
 *
 * ## 移植时保留的行为
 * - 扫描：固定挂载点 + `/storage/` 下的真实目录，深度 ≤ 5，最多 3000 首，
 *   扩展名白名单 mp3/flac/wav/m4a/aac/ogg/opus/wma/ape；
 * - 文件名自动拆两段，拆不出来就当歌名（6.0.78 起按实车「歌名 - 歌手」拆）；
 * - `MediaPlayer` + `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`，`prepareAsync` 不卡主线程；
 * - 申请音频焦点，导航播报时自动让路（旧版是同样的 `requestAudioFocus`）；
 * - 自己建一个 `MediaSession`（tag `H6LocalMusic`），把歌名/歌手/播放态发出去，
 *   方向盘媒体键也因此能控制它。
 *
 * ## 与旧版的差异
 * - 旧版 `next()` 是**随机**下一首（`new Random().nextInt(n-1)+1`），这里改成顺序，
 *   另外单独提供 [shuffle]；随机跳没有"下一首"的语义，方向盘按一下换到哪首完全不可预期。
 * - 新增 [android.os.storage.StorageManager] 的可移动卷探测：老 ROM 上 U 盘常挂在
 *   `/storage/1A2B-3C4D` 这种卷名上，光靠固定路径列表找不到。
 * - 新增 `Settings.Global.h6n_music_root` 覆盖项，纯粹为了在没有 U 盘的环境（比如平板）
 *   上验证扫描/播放链路，留空即忽略。
 * - 6.0.74：桌面冷启动自动随机播放（设置页可关）；别的音频通道出声时把音乐压到 20%，
 *   播完自动恢复音量。焦点回调 + `getActivePlaybackConfigurations` 轮询两条路一起兜，
 *   因为车机上导航播报/提示音/电话很多根本不申请音频焦点。
 * - 6.0.80：播完不再立刻恢复，延时 2 秒去抖（一句句播报时音量不再反复上下跳）；
 *   2 秒内又出声就取消恢复、继续压低。压低本身仍然即时。
 * - 6.0.81：实车「压低有、恢复没延时」取证 —— 高德 `com.autonavi.amapauto` 每次播报
 *   都是 `USAGE_MEDIA` + `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，系统级 duck 一撤销就把
 *   音轨音量直接抬回满音量，覆盖掉 app 层写的 20%。于是 2 秒延时改由 400ms 轮询
 *   驱动：[lastExternalAt] 记录最后一次外部音源，安静不满 2 秒就持续写回 20%；
 *   切歌不再清压低状态、起播先写一次音量、长期丢焦点也不再有「立刻恢复」旁路。
 */
object LocalPlayer {

    private const val TAG = "H6Local"

    /** 扫描深度上限。旧版 5 层，U 盘里 `音乐/歌手/专辑/年份/曲目` 正好够。 */
    private const val MAX_DEPTH = 5

    /** 曲目数上限。旧版 3000，太大列表渲染和内存都吃不消。 */
    private const val MAX_FILES = 3000

    /** 别的通道出声时把音乐压到这个比例；播完恢复到 [baseVolume]（6.0.74）。 */
    private const val DUCK_VOLUME = 0.2f

    /**
     * 别的通道播完后，保持压低多久再恢复音量（6.0.80）。
     * 车机播报常常是「一句一句」的，句与句之间静默几百毫秒；立刻恢复会让音量
     * 一遍遍上下跳，反而比一直压着更难受。2 秒内又出声就重新计时（去抖）。
     */
    private const val DUCK_RELEASE_DELAY_MS = 2000L

    /** 封面缓存上限；按 LRU 淘汰，避免听几千首后把内存吃满。 */
    private const val ART_CACHE_MAX = 24

    /**
     * 同一首封面最多试几次（6.0.104）。
     * U 盘刚插上、挂载还没就绪时读文件会失败，这种失败**不能**当成「没有封面」，
     * 隔 1.5 秒再试，三次仍不行就不折腾了（多半是真读不了）。
     */
    private const val ART_TRY_MAX = 3

    /**
     * 用户主动切歌的最小间隔。车机方向盘媒体键一次按下可能被系统分发两遍
     * （down / up 各一次），150ms 内的重复直接丢掉，超过则允许连续点。
     */
    private const val USER_SKIP_GAP_MS = 150L

    private val EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape")

    /** 旧版原样保留的一批挂载点。 */
    private val FIXED_ROOTS = listOf(
        "/storage/udisk", "/storage/usbdisk", "/storage/usb_storage",
        "/storage/usb0", "/storage/usb1", "/storage/usb2",
        "/mnt/udisk", "/mnt/usb_storage", "/mnt/usb", "/udisk"
    )

    /** 诊断覆盖：`adb shell settings put global h6n_music_root /sdcard/Music`。 */
    private const val K_ROOT = "h6n_music_root"

    private const val SP = "h6_local_music"
    private const val K_LAST_PATH = "last_path"
    private const val K_LAST_TITLE = "last_title"
    /** 桌面冷启动自动随机播放的开关（6.0.74，默认开）。 */
    private const val K_AUTO_PLAY = "auto_play"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val tracks = ArrayList<LocalTrack>()

    private var appCtx: Context? = null
    private var index = -1
    private var root = ""
    private var scanning = false
    private var scanningPrefix = ""

    private var mp: MediaPlayer? = null
    private var playing = false
    private var manualStop = false
    private var posBase = 0L
    private var posAt = 0L

    private var session: MediaSession? = null
    private var focusing = false
    private var focusReq: AudioFocusRequest? = null

    /** 桌面冷启动自动随机播放：进程内只判一次，翻页/重建/回到前台都不再重复起播。 */
    @Volatile
    private var autoPlayTried = false

    // ---- 自动压低（6.0.74）----
    // 两条来源只要有一条说"该压低"，就压低；两条都消失才恢复。
    //   focusDuck：系统音频焦点回调（对方申请了焦点，最权威）
    //   streamDuck：每 400ms 扫 getActivePlaybackConfigurations，兜住不申请焦点的车机音源
    //               （导航播报、倒车雷达、蓝牙电话、系统提示音常常都不申请焦点）
    @Volatile
    private var focusDuck = false

    @Volatile
    private var streamDuck = false
    private var ducked = false
    private var baseVolume = 1f

    /**
     * 最近一次检测到「别的通道在出声」的时间（elapsedRealtime）。
     * 6.0.81：恢复不再只靠一次 postDelayed —— 实车高德播报走系统级 MAY_DUCK，
     * 播报一停 AudioFlinger 直接把音轨音量抬回满音量，把 app 层写的 20% 冲掉；
     * 改成「安静满 2 秒」由每 400ms 的轮询判定，期间持续把音量写回 20%。
     */
    private var lastExternalAt = 0L

    @Volatile
    private var lastErr: String? = null

    // ---- 封面（6.0.75 / 6.0.104）----
    // LinkedHashMap 按插入顺序淘汰最旧的；containsKey 用来区分「没查过」和「查过但没封面」，
    // 后者必须记住，不然每刷一次卡片就重新去读一遍几十 MB 的文件头。
    private val artCache = LinkedHashMap<String, Bitmap?>()
    private val artLoading = HashSet<String>()
    /** 读取失败的重试计数；成功或确认没封面时清掉。 */
    private val artTries = HashMap<String, Int>()
    private var lastUserSkipAt = 0L

    /** 扫描/播放出错时的最后一句话，给设置页看。 */
    fun lastError(): String? = lastErr

    private fun ctx(): Context? = appCtx

    fun attach(c: Context): Context {
        val app = c.applicationContext ?: c
        appCtx = app
        return app
    }

    // ================================================================== 探测目录

    /**
     * 候选根目录，按「可能性」排序。
     *
     * 顺序：诊断覆盖 → StorageManager 报的可移动卷 → `/storage/` 里除
     * `emulated` / `self` / `sdcard0` 之外的目录 → 旧版那批固定路径。
     */
    private fun candidateRoots(ctx: Context): List<File> {
        val out = ArrayList<File>()

        fun add(f: File?) {
            if (f == null) return
            if (!f.isDirectory || !f.canRead()) return
            if (out.any { it.absolutePath == f.absolutePath }) return
            out.add(f)
        }

        runCatching {
            Settings.Global.getString(ctx.contentResolver, K_ROOT)?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { add(File(it)) }

        runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: return@runCatching
            for (v in sm.storageVolumes) {
                // isPrimary 就是内置存储（内部可写目录），U 盘一定是 else 这一支
                if (runCatching { v.isPrimary }.getOrDefault(true)) continue
                if (!runCatching { v.isRemovable }.getOrDefault(false)) continue
                if (runCatching { v.state }.getOrNull() != "mounted") continue
                add(volumeDir(v))
            }
        }

        runCatching {
            File("/storage").listFiles()?.forEach { f ->
                val n = f.name
                if (n == "emulated" || n == "self" || n == "sdcard0") return@forEach
                // 上面已经加过的会在 add() 里按路径去重
                add(f)
            }
        }

        for (p in FIXED_ROOTS) add(File(p))

        return out
    }

    /** `StorageVolume.getDirectory()` 是 API 30 才有的，老系统只能反射取 mPath。 */
    private fun volumeDir(v: android.os.storage.StorageVolume): File? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory
        else {
            val f = v.javaClass.getDeclaredField("mPath")
            f.isAccessible = true
            f.get(v) as? File
        }
    }.getOrNull()

    // ================================================================== 扫描

    /**
     * 扫描 U 盘。
     *
     * 同 [maxWait] 次内如果已经在扫，就等它扫完再回调（旧版是排队 60 次 × 250ms = 15 秒）。
     * 回调一定在主线程。
     */
    fun scan(cb: ((list: List<LocalTrack>, root: String, err: String?) -> Unit)? = null) =
        scanQueued(cb, 0)

    private fun scanQueued(
        cb: ((list: List<LocalTrack>, root: String, err: String?) -> Unit)?,
        waitTick: Int
    ) {
        val ctx = ctx() ?: return
        if (scanning) {
            if (waitTick >= 60) {
                cb?.let { main.post { it(tracks(), root, "上一次扫描还没结束") } }
                return
            }
            main.postDelayed({ scanQueued(cb, waitTick + 1) }, 250L)
            return
        }
        scanning = true
        lastErr = null
        Thread({
            val found = ArrayList<LocalTrack>()
            var hitRoot = ""
            var err: String? = null
            try {
                for (dir in candidateRoots(ctx)) {
                    scanningPrefix = dir.absolutePath
                    found.clear()
                    val n = walk(dir, 0, found)
                    if (n > 0) {
                        hitRoot = dir.absolutePath
                        break
                    }
                }
                if (found.isEmpty()) {
                    val roots = candidateRoots(ctx)
                    err = if (roots.isEmpty()) {
                        // 一个可读目录都没有，几乎一定是没给存储权限（File.canRead 全被挡了），
                        // 光说"没找到音乐文件"会让人以为是 U 盘里没歌。
                        "读不到任何 U 盘目录：存储权限未授予，或没插 U 盘"
                    } else "没找到音乐文件（已探测 ${roots.size} 个目录）"
                }
            } catch (t: Throwable) {
                err = t.javaClass.simpleName + "：" + (t.message ?: "")
                Log.w(TAG, "扫描失败：$t")
            } finally {
                scanning = false
                scanningPrefix = ""
            }
            if (err != null) lastErr = err
            synchronized(lock) {
                tracks.clear()
                tracks.addAll(found)
                root = hitRoot
                // 上次播的那首如果还在列表里，把 index 定位过去
                val last = prefs(ctx).getString(K_LAST_PATH, null)
                index = if (last == null) -1 else tracks.indexOfFirst { it.path == last }
            }
            Log.i(TAG, "扫描完成：根=$hitRoot 曲目=${found.size} 错误=$err")
            main.post { cb?.invoke(tracks(), hitRoot, err) }
        }, "h6-local-scan").start()
    }

    /** 递归收歌。返回本次新增数量（旧版同一套规则：隐藏文件跳过、目录继续下钻）。 */
    private fun walk(dir: File?, depth: Int, out: MutableList<LocalTrack>): Int {
        if (dir == null || depth > MAX_DEPTH) return 0
        if (out.size >= MAX_FILES) return 0
        val list = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        var added = 0
        for (f in list) {
            if (out.size >= MAX_FILES) break
            val name = f.name
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                added += walk(f, depth + 1, out)
                continue
            }
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) continue
            val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext !in EXTS) continue
            out.add(make(f, name.substring(0, dot)))
            added++
        }
        return added
    }

    /**
     * 实车 U 盘是「**歌名 - 歌手**」（2026-11-15 ADB 取证：`1987我不知会遇见你 - 李宇春.flac`、
     * `A Little Story - Valentin.flac`、`50 Ways to Say Goodbye - Train.mp3`；无分隔符的
     * `01.吻别.wav` 就是纯歌名）。
     *
     * 6.0.77 及以前按「歌手 - 歌名」拆反了：`title` 取了分隔符**后**那段，于是音乐卡主行
     * 显示的其实是歌手（例如「胡夏」「Lost Frequencies、Janieck」而不是「那些年」）——
     * 这正是「不要显示歌手，显示歌曲名」一直没真正满足的原因。
     *
     * 6.0.78 起反过来：分隔符**前**是歌名（卡片只显示这一段），**后**是歌手（只进
     * MediaSession 元数据 / 断点，不上卡片）。取 `lastIndexOf` 是为了兼容歌名里本身带
     * `" - "` 的 remix 文件名，让歌名尽量完整；拆不出来整串当歌名，歌手留空。
     */
    private fun make(f: File, rawName: String): LocalTrack {
        val s = rawName.trim()
        val sep = s.lastIndexOf(" - ")
        return if (sep > 0) {
            LocalTrack(
                path = f.absolutePath,
                title = s.substring(0, sep).trim(),
                artist = s.substring(sep + 3).trim()
            )
        } else {
            LocalTrack(f.absolutePath, s, "")
        }
    }

    // ================================================================== 播放

    fun tracks(): List<LocalTrack> = synchronized(lock) { ArrayList(tracks) }

    fun size(): Int = synchronized(lock) { tracks.size }

    fun index(): Int = synchronized(lock) { index }

    fun root(): String = synchronized(lock) { root }

    fun current(): LocalTrack? = synchronized(lock) {
        if (index < 0 || index >= tracks.size) null else tracks[index]
    }

    fun isPlaying(): Boolean = playing

    fun position(): Long {
        val p = mp ?: return 0L
        return posBase + if (playing) {
            (SystemClock.elapsedRealtime() - posAt).coerceAtLeast(0L)
        } else 0L
    }

    fun duration(): Long = runCatching { mp?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    fun playAt(i: Int) {
        val t = synchronized(lock) {
            if (i < 0 || i >= tracks.size) return
            index = i
            tracks[i]
        }
        start(t)
    }

    /** 播放列表里第一首（扫描完成后用）。 */
    fun playFirst() {
        val i = synchronized(lock) { if (tracks.isEmpty()) -1 else 0 }
        if (i >= 0) playAt(i) else scan { l, _, _ -> if (l.isNotEmpty()) playAt(0) }
    }

    /**
     * 接着上次听的那首放。
     *
     * 列表还没扫过就现扫一次（U 盘扫描是几百毫秒到几秒，只在用户点了之后才发生）。
     */
    fun resumeLast() {
        val ctx = ctx() ?: return
        val last = prefs(ctx).getString(K_LAST_PATH, null)
        if (tracks().isEmpty()) {
            scan { list, _, _ ->
                if (list.isEmpty()) return@scan
                val i = if (last == null) 0 else list.indexOfFirst { it.path == last }.coerceAtLeast(0)
                playAt(i)
            }
            return
        }
        val i = synchronized(lock) {
            when {
                index >= 0 && index < tracks.size -> index
                last == null -> 0
                else -> tracks.indexOfFirst { it.path == last }.coerceAtLeast(0)
            }
        }
        playAt(i)
    }

    // ==================================================== 冷启动自动随机播放（6.0.74）

    /** 设置页开关：桌面冷启动时自动随机来一首。默认开。 */
    fun autoPlayEnabled(c: Context): Boolean =
        runCatching { prefs(c).getBoolean(K_AUTO_PLAY, true) }.getOrDefault(true)

    fun setAutoPlay(c: Context, on: Boolean) {
        runCatching { prefs(c).edit().putBoolean(K_AUTO_PLAY, on).apply() }
    }

    /**
     * 桌面冷启动入口：随机播一首 U 盘里的歌。
     *
     * 只判一次（[autoPlayTried]）—— 用户手动暂停后切页、回前台、重建卡片都不会被强行续播。
     * 冷启动瞬间 U 盘往往还没挂载完，先等 2.5 秒再扫；扫不到再补一次（+8 秒），
     * 两次都没有就彻底放弃，不常驻轮询耗电。没插 U 盘 / 没歌 / 开关关 / 已有别的播放器
     * 在出声，都不动作。
     */
    fun maybeAutoPlay() {
        val app = ctx() ?: return
        synchronized(lock) {
            if (autoPlayTried) return
            autoPlayTried = true
        }
        if (!autoPlayEnabled(app)) {
            Log.i(TAG, "冷启动自动播放：开关已关闭")
            return
        }
        main.postDelayed({ autoPlayRound(0) }, 2500L)
    }

    private fun autoPlayRound(round: Int) {
        val app = ctx() ?: return
        if (!autoPlayEnabled(app)) return
        if (mp != null || playing) return                 // 用户已经先点了播放
        if (MediaWatcher.current()?.playing == true) {   // 别的播放器正在放，别抢
            Log.i(TAG, "冷启动自动播放：已有音乐在播，跳过")
            return
        }
        scan { list, _, _ ->
            if (list.isEmpty()) {
                if (round < 1) main.postDelayed({ autoPlayRound(round + 1) }, 8000L)
                else Log.i(TAG, "冷启动自动播放：没检测到 U 盘音乐，放弃")
                return@scan
            }
            // 随机一首；尽量避开上次那首，免得每次开机都是同一首
            val n = list.size
            var i = (Math.random() * n).toInt().coerceIn(0, n - 1)
            if (n > 1) {
                val last = runCatching { prefs(app).getString(K_LAST_PATH, null) }.getOrNull()
                if (list[i].path == last) i = (i + 1 + (Math.random() * (n - 1)).toInt()) % n
            }
            Log.i(TAG, "冷启动自动播放：随机第 ${i + 1}/$n 首 ${list[i].title}")
            playAt(i)
        }
    }

    private fun start(t: LocalTrack) {
        val app = attach(ctx() ?: return)
        manualStop = false
        releasePlayer()
        runCatching {
            val s = ensureSession(app)
            s.isActive = true
            focus(true)

            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 6.0.75：改交文件描述符，不再把路径字符串丢给 mediaserver。
            // 实机 6.0.74 上点一次「下一首」，`/sdcard/Music/music/...` 里一批 mp3/flac
            // 全部报 `what=-38 extra=0`：这些文件 owner 是别的 app（rw-rw----），
            // mediaserver 直接 open 路径时被 FUSE 权限挡住，而 app 自己 FileInputStream 能读。
            // 先把 fd 打开再交给 MediaPlayer，权限检查就落在 app 这一侧了。
            val fis = FileInputStream(t.path)
            val len = runCatching { fis.channel.size() }.getOrDefault(0L)
            try {
                if (len > 0) p.setDataSource(fis.fd, 0, len) else p.setDataSource(fis.fd)
            } finally {
                runCatching { fis.close() }
            }
            p.setOnPreparedListener {
                // 6.0.81：切歌/续播的新 MediaPlayer 默认满音量，先按当前压低状态写一次，
                // 否则系统 duck 一撤销就会冒出一段原音量，把 2 秒延时吃掉。
                writeVolume()
                playing = true
                posBase = 0L
                posAt = SystemClock.elapsedRealtime()
                runCatching { it.start() }
                pushMeta(t)
                pushState()
                pushCard()
            }
            p.setOnCompletionListener {
                posBase = 0L
                if (!manualStop) next() else { playing = false; pushState(); pushCard() }
            }
            p.setOnErrorListener { _, what, extra ->
                playing = false
                posBase = 0L
                Log.w(TAG, "播放失败 what=$what extra=$extra path=${t.path}")
                lastErr = "这首放不了（$what/$extra）"
                // 6.0.75：**绝不再自动跳下一首**。6.0.74 这里 `errStreak<=3` 会延迟 400ms
                // 再 next()，一旦连续几首放不了，用户点一次「下一首」就会看到卡片连换 4 首歌
                // （实机日志 16:08:36~37 四连跳已证实）。现在停在本曲，一次点击只换一首。
                pushState()
                pushCard()
                true
            }
            mp = p
            playing = false
            p.prepareAsync()

            // 列表里还没这首歌（比如直接 resume 单曲）就补一条，保证卡片/切歌有上下文
            synchronized(lock) {
                if (index < 0 || index >= tracks.size || tracks[index].path != t.path) {
                    val i = tracks.indexOfFirst { it.path == t.path }
                    index = if (i >= 0) i else index
                }
            }
            pushMeta(t)
            pushState()
            pushCard()
            startDuckWatch()
            runCatching {
                prefs(app).edit().putString(K_LAST_PATH, t.path)
                    .putString(K_LAST_TITLE, t.title).apply()
            }
        }.onFailure {
            playing = false
            lastErr = "起播异常：" + (it.message ?: it.javaClass.simpleName)
            Log.w(TAG, "起播异常：$it")
            pushState()
            pushCard()
        }
    }

    fun toggle() {
        when {
            playing -> pause()
            mp != null -> resume()
            current() != null -> playAt(index())
            else -> resumeLast()
        }
    }

    fun resume() {
        val p = mp
        if (p == null) {
            if (current() != null) playAt(index()) else resumeLast()
            return
        }
        runCatching {
            ensureSession(attach(ctx() ?: return)).isActive = true
            focus(true)
            p.start()
            playing = true
            posAt = SystemClock.elapsedRealtime()
            manualStop = false
            pushState()
            pushCard()
        }.onFailure { Log.w(TAG, "resume 失败：$it") }
    }

    fun pause() {
        val p = mp ?: return
        runCatching {
            if (p.isPlaying) p.pause()
            posBase = position()
            playing = false
            pushState()
            pushCard()
        }.onFailure { Log.w(TAG, "pause 失败：$it") }
    }

    fun stop() {
        manualStop = true
        releasePlayer()
        stopDuckWatch()   // 真正停止播放：清干净轮询、延时任务与压低状态
        playing = false
        posBase = 0L
        focus(false)
        runCatching { session?.isActive = false }
        pushState()
        pushCard()
    }

    fun next() {
        val n = size()
        if (n == 0) {
            scan { l, _, _ -> if (l.isNotEmpty()) playAt(0) }
            return
        }
        val i = index()
        playAt(if (i < 0 || i >= n - 1) 0 else i + 1)
    }

    fun prev() {
        val n = size()
        if (n == 0) return
        val i = index()
        playAt(if (i <= 0) n - 1 else i - 1)
    }

    /**
     * 给用户操作（卡片按钮 / 方向盘媒体键）用的切歌入口。
     *
     * 车机方向盘一次按键常被分发成 down+up 两条媒体键事件，不做去重就会「按一下跳两首」。
     * 只有这两个入口限流；[next] 本身仍供播完自动续播调用，不受影响。
     */
    fun userNext() {
        if (!skipAllowed()) return
        next()
    }

    fun userPrev() {
        if (!skipAllowed()) return
        prev()
    }

    private fun skipAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUserSkipAt < USER_SKIP_GAP_MS) return false
        lastUserSkipAt = now
        return true
    }

    /** 随机来一首（旧版 `next()` 的行为，独立出来）。 */
    fun shuffle() {
        val n = size()
        if (n == 0) {
            // 还没扫过就现扫，扫完同样随机挑 —— 别固定回第一首（6.0.74 卡片空态点击）
            scan { l, _, _ ->
                if (l.isNotEmpty()) {
                    playAt((Math.random() * l.size).toInt().coerceIn(0, l.size - 1))
                }
            }
            return
        }
        if (n == 1) { playAt(0); return }
        val cur = index()
        var i = cur
        var guard = 0
        while (i == cur && guard++ < 16) i = (Math.random() * n).toInt().coerceIn(0, n - 1)
        playAt(i)
    }

    fun seekTo(pos: Long) {
        val p = mp ?: return
        runCatching {
            p.seekTo(pos.toInt())
            posBase = pos
            posAt = SystemClock.elapsedRealtime()
            pushState()
        }.onFailure { Log.w(TAG, "seek 失败：$it") }
    }

    fun setVolume(v: Float) {
        baseVolume = v.coerceIn(0f, 1f)
        val real = if (ducked) baseVolume * DUCK_VOLUME else baseVolume
        runCatching { mp?.setVolume(real, real) }
    }

    // ==================================================== 自动压低 / 恢复（6.0.74）

    /**
     * 这些 usage 只要处于 STARTED，就是「别的通道正在出声」。
     * 我们自己的播放器用的是 USAGE_MEDIA，不会误判到自己头上。
     */
    private val DUCK_USAGES = setOf(
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_NOTIFICATION_EVENT,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_ALARM,
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
    )

    /**
     * 每 400ms 看一次系统当前活跃的播放通道。
     *
     * 不能只靠音频焦点：车机上的导航播报、倒车雷达、蓝牙电话、系统提示音很多都
     * **不申请焦点**，焦点回调一次都不会来，音乐却已经把人声盖住了。
     * `AudioManager.getActivePlaybackConfigurations` 是 API 26 的公开接口，
     * 车机 8.1（API 27）可用；低于 26 就只留音频焦点那条路。
     *
     * 6.0.81：**USAGE_MEDIA 不能一刀切排除**。实车 `dumpsys audio` 焦点栈里
     * `com.autonavi.amapauto` 每次播报都是 `AA=USAGE_MEDIA` +
     * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`；真把 MEDIA 全排除，导航播报就没人压低了。
     * 保留「非自身 uid 的 MEDIA 音轨也算」这条，靠 uid 排除自己的播放器即可。
     */
    private fun externalPlaying(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val am = ctx()?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val list = runCatching { am.activePlaybackConfigurations }.getOrNull() ?: return false
        val myUid = android.os.Process.myUid()
        for (c in list) {
            // getPlayerState / getClientUid 在 SDK stub 里是 @hide（编译期不可见），
            // 只能反射；车机 8.1 的 framework 里有实现。拿不到状态时按「出现即活跃」算。
            val state = runCatching {
                c.javaClass.getMethod("getPlayerState").invoke(c) as? Int ?: -1
            }.getOrDefault(-1)
            if (state >= 0 && state != 2) continue  // 2 = PLAYER_STATE_STARTED
            val usage = runCatching { c.audioAttributes.usage }
                .getOrDefault(AudioAttributes.USAGE_UNKNOWN)
            if (usage == AudioAttributes.USAGE_MEDIA) {
                // 同样是媒体流：确认是别的应用在放才算。拿不到 uid 就宁可漏判，
                // 也不能把自己压低（那会变成音乐永远只有 20% 音量）。
                val uid = runCatching {
                    c.javaClass.getMethod("getClientUid").invoke(c) as? Int ?: -1
                }.getOrDefault(-1)
                if (uid < 0 || uid == myUid) continue
            } else if (usage !in DUCK_USAGES) continue
            return true
        }
        return false
    }

    /**
     * 把「当前应该生效的音量」真正写进播放器。**每次都写，不能只在状态翻转时写**。
     *
     * 6.0.81 实车取证：`com.autonavi.amapauto` 播报申请
     * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，AudioFlinger 会自己做系统级 duck，
     * 播报一停 `abandonAudioFocus()` 又把音轨音量直接抬回满音量 —— 这一步会**覆盖**
     * app 层 `MediaPlayer.setVolume(0.2f)`。所以「延时」不能只靠一次 postDelayed，
     * 必须在整段延时窗口里持续把音量写回去。
     */
    private fun writeVolume() {
        val v = if (ducked) baseVolume * DUCK_VOLUME else baseVolume
        runCatching { mp?.setVolume(v, v) }
    }

    /**
     * 两个来源取「或」；恢复改成「安静满 2 秒」的轮询判定（6.0.81）。
     *
     * 6.0.80 用一次 `postDelayed` 做延时，实车不生效：系统级 duck 撤销时音量被抬回，
     * 而 `ducked` 又可能因焦点回调时序 / 切歌没被可靠置位，延时任务根本没排上。
     * 现在只要检测到外部音源就刷新 [lastExternalAt] 并立刻压低；音源消失后，
     * 每 400ms 的 [duckTask] 持续把音量写回 20%，直到「安静」累计满 2 秒才恢复。
     * 这样无论压低是系统做的还是我们做的、中间切没切歌，2 秒延时都成立。
     */
    private fun recomputeDuck() {
        val want = focusDuck || streamDuck
        val now = SystemClock.elapsedRealtime()
        if (want) {
            lastExternalAt = now
            val changed = !ducked
            ducked = true
            writeVolume()
            if (changed) Log.i(TAG, "别的通道出声 → 音乐压低到 20% focus=$focusDuck stream=$streamDuck")
        } else if (ducked) {
            val quiet = now - lastExternalAt
            if (quiet < DUCK_RELEASE_DELAY_MS) {
                // 延时窗口内照样写压低音量：对抗系统 duck 自动撤销、切歌新 mp 的满音量
                writeVolume()
                return
            }
            ducked = false
            writeVolume()
            Log.i(TAG, "安静满 ${DUCK_RELEASE_DELAY_MS}ms → 恢复音量")
        } else {
            // 本来就没压低：每次都写一遍正常音量（切歌换新 mp 时需要）
            writeVolume()
        }
    }

    private val duckTask = object : Runnable {
        override fun run() {
            if (mp == null) {
                stopDuckWatch()
                return
            }
            streamDuck = externalPlaying()
            recomputeDuck()
            main.postDelayed(this, 400L)
        }
    }

    private fun startDuckWatch() {
        main.removeCallbacks(duckTask)
        main.post(duckTask)
    }

    /** 切歌：只挂起轮询，保留压低状态与安静计时，新 mp 由下一轮写回正确音量。 */
    private fun pauseDuckWatch() {
        main.removeCallbacks(duckTask)
    }

    private fun stopDuckWatch() {
        main.removeCallbacks(duckTask)
        focusDuck = false
        streamDuck = false
        ducked = false
        lastExternalAt = 0L
    }

    /** 给设置页看的当前状态（6.0.80：等待恢复也单独显示，方便确认 2 秒延时生效）。 */
    fun duckState(): String = when {
        mp == null -> "未在播放"
        focusDuck || streamDuck -> "已压低（别的通道在出声）"
        ducked -> "已压低，2 秒后恢复音量"
        else -> "正常音量"
    }

    // ================================================================== 系统集成

    private fun ensureSession(ctx: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx, "H6LocalMusic")
        @Suppress("DEPRECATION")
        s.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.setCallback(object : MediaSession.Callback() {
            // 这些回调只对本应用自己的 session 生效，不需要 MEDIA_CONTENT_CONTROL，
            // 所以方向盘媒体键能直接控制 U 盘音乐。
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = stop()
            override fun onSkipToNext() = userNext()
            override fun onSkipToPrevious() = userPrev()
            override fun onSeekTo(pos: Long) = seekTo(pos)
        })
        session = s
        return s
    }

    private fun pushMeta(t: LocalTrack) {
        runCatching {
            val dur = duration()
            val b = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "U 盘")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, t.artist)
            if (dur > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
            session?.setMetadata(b.build())
        }
    }

    private fun pushState() {
        runCatching {
            val st = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position(),
                    if (playing) 1.0f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
            session?.setPlaybackState(st)
        }
    }

    /** 把自己推给机油卡那条横条（[MediaWatcher] 里专门给本地播放器留了一个插槽）。 */
    private fun pushCard() {
        val t = current()
        val np = if (t == null) {
            null
        } else {
            // 6.0.75：U 盘曲目也有封面了。缓存没命中就异步读，读到位再回灌一次卡片 ——
            // NowPlaying.key() 带 `art != null` 这一位，null→有图会正常触发 UI 刷新。
            val art = artOf(t.path)
            if (art == null) ensureArt(t.path)
            NowPlaying(
                pkg = appCtx?.packageName.orEmpty(),
                appLabel = "U 盘音乐",
                title = t.title,
                artist = t.artist,
                art = art,
                playing = playing
            )
        }
        MediaWatcher.setLocal(np)
    }

    // ================================================================== 封面（6.0.75 / 6.0.104）

    /** 封面缓存；null = 查过、文件里确实没有封面（读取失败不进缓存）。 */
    private fun artOf(path: String): Bitmap? = synchronized(artCache) { artCache[path] }

    /**
     * 异步读封面，读完回灌卡片。
     *
     * 真正的多级解析在 [CoverArt]（平台解码器 → ID3 APIC → FLAC PICTURE → 同目录图）。
     * 这里只负责三件事：
     * - 同一首别重复读（[artLoading]）；
     * - **读取失败不写缓存**，1.5 秒后再试，最多 [ART_TRY_MAX] 次 —— U 盘刚插上时
     *   文件常常还读不到；6.0.75 把这种「读不出来」也当成「没有封面」缓存了，
     *   于是整趟开机都不会再有图；
     * - 真读到（或确认确实没有）之后回灌一次卡片。
     */
    private fun ensureArt(path: String) {
        synchronized(artCache) {
            if (artCache.containsKey(path) || artLoading.contains(path)) return
            if ((artTries[path] ?: 0) >= ART_TRY_MAX) return
            artLoading.add(path)
        }
        Thread({
            val r = runCatching { CoverArt.load(path) }
                .getOrElse { CoverArt.Result.Fail(it.javaClass.simpleName) }
            var retry = false
            synchronized(artCache) {
                artLoading.remove(path)
                when (r) {
                    is CoverArt.Result.Ok -> {
                        trimArt()
                        artCache[path] = r.art
                        artTries.remove(path)
                    }
                    is CoverArt.Result.None -> {
                        trimArt()
                        artCache[path] = null
                        artTries.remove(path)
                    }
                    is CoverArt.Result.Fail -> {
                        val n = (artTries[path] ?: 0) + 1
                        artTries[path] = n
                        retry = n < ART_TRY_MAX
                    }
                }
            }
            val name = File(path).name
            when (r) {
                is CoverArt.Result.Ok -> Log.i(TAG, "封面：$name ${r.art.width}x${r.art.height}（${r.from}）")
                is CoverArt.Result.None -> Log.i(TAG, "封面：$name 没有（${r.why}），画音符块")
                is CoverArt.Result.Fail -> Log.w(TAG, "封面：$name 读不出来（${r.why}），稍后重试")
            }
            if (retry) {
                main.postDelayed({ if (current()?.path == path) ensureArt(path) }, 1500L)
            } else if (r !is CoverArt.Result.Fail) {
                // 已经切到别的歌就别再刷，免得把新歌的封面顶掉
                main.post { if (current()?.path == path) pushCard() }
            }
        }, "h6-local-art").start()
    }

    /** 缓存满了先淘汰最旧的一条；调用方必须已经持有 [artCache] 的锁。 */
    private fun trimArt() {
        while (artCache.size >= ART_CACHE_MAX) {
            artCache.remove(artCache.keys.iterator().next())
        }
    }

    /**
     * 设置页「重读封面」：清掉当前曲目的缓存立刻重读。
     * U 盘换了内容、或插盘那一刻封面没读到时，不用重启桌面就能补上。
     */
    fun reloadArt(): String {
        val t = current() ?: return "当前没有在播放，先放一首再试"
        synchronized(artCache) {
            artCache.remove(t.path)
            artTries.remove(t.path)
        }
        ensureArt(t.path)
        return "正在重读「${t.title}」的封面…"
    }

    /** 当前曲目的封面状态，给设置页与导出日志看。 */
    fun artText(): String {
        val t = current() ?: return "没有在播放"
        var known = false
        var has = false
        synchronized(artCache) {
            known = artCache.containsKey(t.path)
            has = artCache[t.path] != null
        }
        return when {
            has -> "已显示封面"
            !known -> "读取中…"
            else -> "没有封面（文件里没内嵌图，目录里也没有同名图）"
        }
    }

    // ================================================================== 杂项

    private fun releasePlayer() {
        // 6.0.81：切歌只挂起轮询，别把压低状态和「2 秒恢复」任务一起清掉，
        // 否则切歌瞬间新 mp 会以满音量冒出来，延时就被绕过去了。
        pauseDuckWatch()
        val p = mp ?: return
        mp = null
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.reset() }
        runCatching { p.release() }
    }

    private fun focus(on: Boolean) {
        val am = ctx()?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (on) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusLsn)
                        .build()
                        .also { focusReq = it }
                    am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(
                        focusLsn, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                focusing = ok
                Log.d(TAG, "请求焦点=$ok")
            } else if (focusing) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusReq?.let { am.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(focusLsn)
                }
                focusing = false
            }
            Unit
        }.onFailure { Log.d(TAG, "焦点操作失败 $it") }
    }

    /**
     * 音频焦点侧的压低/恢复（6.0.74）。
     *
     * 旧版短暂丢失会直接 pause，播完只剩一个 `setVolume(1f)`，歌其实是停着的。
     * 现在统一成「压低 → 播完恢复」：短暂丢失只压低不暂停，GAIN 时恢复音量；
     * 如果之前因长期丢失暂停过，且用户没手动停，也顺手续播。
     * 不申请焦点的那些车机音源由 [externalPlaying] 那条轮询兜住。
     * 6.0.80 起「恢复」统一延时 2 秒（见 [recomputeDuck]）；6.0.81 连长期丢失要暂停
     * 的分支也走同一条延时，不再有「立刻抬回音量」的旁路。
     */
    private val focusLsn = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 别的音乐 app 要长期占用：这种情况暂停比压着一起放更合适。
                // 6.0.81：不再立刻把音量抬回基准值 —— 若此前压低过，统一走
                // 「安静满 2 秒再恢复」，避免 GAIN 时 resume() 直接以满音量回来。
                focusDuck = false
                recomputeDuck()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusDuck = true
                recomputeDuck()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusDuck = false
                recomputeDuck()
                if (!playing && !manualStop && mp != null) resume()
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 上次播过的歌名；没有返回 null。卡片据此显示「点此继续」。 */
    fun lastTitle(): String? = ctx()?.let {
        prefs(it).getString(K_LAST_TITLE, null)?.takeIf { s -> s.isNotEmpty() }
    }

    /** 扫描进度里当前正在看的目录（设置页显示用）。 */
    fun scanningHint(): String = if (scanning) "扫描中：$scanningPrefix" else ""
}
