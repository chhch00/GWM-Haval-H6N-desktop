package com.h6n.launcher.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.h6n.launcher.core.H6Log as Log

/**
 * 场景播报提醒 —— 播报音频通道 + 提示音量。
 *
 * 完整移植自未重写版 `com.h6n.launcher.AudioRoute`（Java）：
 * 车机上「导航 / 媒体 / 系统 / 通知 / 电话 / 闹钟」走不同的音频总线，同一个 wav
 * 从不同通道出来音量和能不能听见完全不一样；所以让用户自己挑一条，并单独调这一路
 * 的提示音量（0~100，**默认 25**，只作用于本 App 的 MediaPlayer，不动系统音量）。
 *
 * 与旧版保持一致的三点：
 *  1. SP 名字 / 键沿用 `h6_scene_audio` 的 `route` / `vol`（见 [SceneStore]），覆盖安装直接继承；
 *  2. 默认通道 = 2「系统提示」，默认音量 = 25；
 *  3. 换通道后清掉 [VoicePlayer] 的播放器缓存（旧版用 `path#route` 缓存键实现），
 *     音量每次播放前重设，所以调完立刻生效。
 *
 * 试听用的是 `assets/voice/door_fl_open.wav`，与旧版 `playTest()` 相同，且不受播报总开关影响。
 */
object AudioRoute {

    private const val TAG = "H6Audio"

    /** 旧版 DEFAULT：系统提示音通道，车机上最稳、最不容易被导航 / 音乐顶掉 */
    const val DEFAULT_ROUTE = 2

    /** 旧版 DEFAULT_VOL：提示音量默认 25 */
    const val DEFAULT_VOL = 25

    /** 顺序即界面顺序，与 [SceneStore.AUDIO_NAMES] 一致 */
    val NAMES: Array<String> get() = SceneStore.AUDIO_NAMES

    val DESC = arrayOf(
        "走导航总线，播报时压低音乐",
        "当普通音乐放，跟正在放的歌抢同一条通道",
        "系统提示音通道，通常不受静音影响（默认）",
        "通知通道，部分车机会闪提示条",
        "通话音道，音量与通话共用",
        "闹钟通道，音量通常最大"
    )

    fun index(): Int = SceneStore.audioRoute()
    fun name(): String = SceneStore.audioName()
    fun desc(): String = DESC[index()]

    /** 换通道后清缓存：下一次播报用新的 AudioAttributes 重建播放器。 */
    fun set(i: Int) {
        SceneStore.setAudioRoute(i)
        VoicePlayer.clearCache()
        SceneAnnouncer.noteRouteChanged()
        Log.i(TAG, "播报通道 → ${name()} ($i) · 音量 ${SceneStore.volume()}%")
    }

    fun volume(): Int = SceneStore.volume()
    fun setVolume(v: Int) = SceneStore.setVolume(v)

    /** VoicePlayer 建播放器时用这个（旧版 AudioRoute.attributes）。 */
    fun attributes(): AudioAttributes {
        val (usage, type) = SceneStore.audioUsage()
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(type)
            .build()
    }

    @Volatile private var test: MediaPlayer? = null

    /**
     * 试听当前通道 / 音量。旧版用独立的 MediaPlayer 播开门音，
     * 不走 VoicePlayer 队列，所以关了播报总开关也能验证通道是否真的出声。
     * 必须在主线程调用（MediaPlayer 完成回调需要 Looper）。
     */
    fun playTest(ctx: Context) {
        try {
            test?.let { old ->
                runCatching { old.release() }
                if (test === old) test = null
            }
            val mp = MediaPlayer()
            test = mp
            mp.setAudioAttributes(attributes())
            val af = ctx.applicationContext.assets.openFd("voice/door_fl_open.wav")
            mp.setDataSource(af.fileDescriptor, af.startOffset, af.length)
            af.close()
            mp.setOnCompletionListener { m ->
                runCatching { m.release() }
                if (test === m) test = null
            }
            mp.prepare()
            val v = SceneStore.volumeF()
            mp.setVolume(v, v)
            mp.start()
            Log.i(TAG, "试听 ${name()} · 音量 ${SceneStore.volume()}%")
        } catch (t: Throwable) {
            Log.w(TAG, "试听失败: $t")
            runCatching { test?.release() }
            test = null
        }
    }
}
