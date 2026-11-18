package com.h6n.launcher.core

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes

/**
 * 场景播报配置 —— 移植自未重写版 `com.h6n.launcher.scene.SceneStore`。
 *
 * **沿用旧版 SharedPreferences 名字与键**：`h6_scene` / `h6_scene_audio`。
 * 从 5.97.x 覆盖安装上来时，用户原来选的音色包、车门电平、自定义音效、音频通道全部直接继承。
 *
 * 音效命名规则（旧版 51 个 wav 全量搬进 assets/voice）：
 * - 车门：`door_{fl,fr,rl,rr}_{open,close}.wav`，尾门 `trunk_{open,close}.wav`
 * - 档位：`gear_{P,R,N,D}.wav`
 * - 熄火车窗提醒：`window.wav`
 * - 音色包：**只有「经典」一套**（6.0.70 删掉了女生/男生），文件名不带前缀
 * - 逐条自定义：`snd_door_*` / `snd_gear_*` / `snd_window_warn`，填了文件名就覆盖默认
 */
object SceneStore {

    private const val SP = "h6_scene"
    private const val SP_AUDIO = "h6_scene_audio"

    const val K_MASTER = "voice_on"          // 重写版新增的总开关
    const val K_DOORS_ON = "doors_on"
    const val K_GEAR_ON = "gear_on"
    const val K_WINDOW_ON = "window_on"
    const val K_PACK = "sound_pack"
    const val K_DOOR_ACTIVE_HIGH = "door_active_high"
    const val K_WINDOW_THRESHOLD = "window_threshold"
    const val K_WARNED = "warned_off"

    /**
     * 胎压提醒的两个开关（6.0.65 新增）。
     *
     * 放在「场景播报」体系里而不是车辆页：用户要求「和场景播报逻辑一样」，
     * 所以同样受总开关 [K_MASTER] 管，同样走 wav 优先 / TTS 兜底那条播报路径。
     * 键名带 `tire_` 前缀，和车门/档位那几组并列，别改成通用的 `alert_*`。
     */
    const val K_TIRE_LOW_ON = "tire_low_on"
    const val K_TIRE_DIFF_ON = "tire_diff_on"

    /**
     * 两个告警阈值（6.0.68 新增，用户可改）。
     *
     * 默认值仍是 240 / 15，与 6.0.65 起的行为完全一致 —— 老用户升级上来不传键，
     * [tireLowKpa] / [tireDiffKpa] 取默认值，一个字都不用迁移。
     * 值域夹在 [TireRules.LOW_MIN_KPA]..[TireRules.LOW_MAX_KPA] 之间：
     * 180 以下是真扎钉，300 以上和 [TireRules.HIGH_KPA] 会打架。
     */
    const val K_TIRE_LOW_KPA = "tire_low_kpa"
    const val K_TIRE_DIFF_KPA = "tire_diff_kpa"

    /**
     * 开门自动 360（6.0.69 新增，默认开）。
     *
     * 不属于播报体系，所以**不受** [K_MASTER] 管：用户关掉「播报提醒」只是不要声音，
     * 不该连带把「开门看四周」这个画面动作也关掉。要关它请用本键自己的开关。
     */
    const val K_DOOR_AVM = "door_avm_on"

    val DOOR_SLOTS = arrayOf("fl", "fr", "rl", "rr", "trunk")
    val DOOR_NAMES = arrayOf("左前门", "右前门", "左后门", "右后门", "后备箱")
    val GEAR_CHARS = arrayOf("R", "D", "P", "N")

    /**
     * 音色包（6.0.70 起只剩「经典」一种）。
     *
     * 女生 / 男生两套（`airy_*` / `male_*` 共 38 个 wav，约 2.4MB）已按用户要求整体删除，
     * 连资源带入口一起去掉 —— 留着入口却没有文件，点下去只会静默无声，比没有更糟。
     * [K_PACK] 键与 [setPack] 保留：老用户 `h6_scene` 里存的 `airy`/`male` 读出来会被
     * [pack] 归一到 `classic`，配置文件备份恢复也不会再写回别的值。
     */
    val PACK_IDS = arrayOf("classic")
    val PACK_NAMES = arrayOf("经典")

    /** 与旧版 AudioRoute.java 完全一致：默认通道「系统提示」、默认提示音量 25。 */
    const val DEFAULT_ROUTE = 2
    const val DEFAULT_VOL = 25

    /** 旧版音频通道顺序：导航播报 / 媒体音乐 / 系统提示 / 通知 / 蓝牙电话 / 闹钟 */
    val AUDIO_NAMES = arrayOf("导航播报", "媒体音乐", "系统提示", "通知", "蓝牙电话", "闹钟")
    private val AUDIO_USAGE = intArrayOf(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        AudioAttributes.USAGE_NOTIFICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_ALARM
    )
    private val AUDIO_TYPE = intArrayOf(
        AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioAttributes.CONTENT_TYPE_MUSIC,
        AudioAttributes.CONTENT_TYPE_SONIFICATION,
        AudioAttributes.CONTENT_TYPE_SONIFICATION,
        AudioAttributes.CONTENT_TYPE_SPEECH,
        AudioAttributes.CONTENT_TYPE_SONIFICATION
    )

    @Volatile private var app: Context? = null

    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun sp(): SharedPreferences =
        (app ?: throw IllegalStateException("SceneStore 未 attach")).getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun audio(): SharedPreferences =
        (app ?: throw IllegalStateException("SceneStore 未 attach")).getSharedPreferences(SP_AUDIO, Context.MODE_PRIVATE)

    // ---------------- 开关 ----------------

    fun master(): Boolean = sp().getBoolean(K_MASTER, true)
    fun setMaster(v: Boolean) { sp().edit().putBoolean(K_MASTER, v).apply() }

    fun doorsOn(): Boolean = sp().getBoolean(K_DOORS_ON, true)
    fun setDoorsOn(v: Boolean) { sp().edit().putBoolean(K_DOORS_ON, v).apply() }

    fun gearOn(): Boolean = sp().getBoolean(K_GEAR_ON, true)
    fun setGearOn(v: Boolean) { sp().edit().putBoolean(K_GEAR_ON, v).apply() }

    fun windowOn(): Boolean = sp().getBoolean(K_WINDOW_ON, true)
    fun setWindowOn(v: Boolean) { sp().edit().putBoolean(K_WINDOW_ON, v).apply() }

    /** 胎压过低提醒（默认开）：低于 240kPa 弹窗 + 播报，一次异常只提醒一次。 */
    fun tireLowOn(): Boolean = sp().getBoolean(K_TIRE_LOW_ON, true)
    fun setTireLowOn(v: Boolean) { sp().edit().putBoolean(K_TIRE_LOW_ON, v).apply() }

    /** 四轮差值提醒（默认开）：极差达到 15kPa 弹窗 + 播报。 */
    fun tireDiffOn(): Boolean = sp().getBoolean(K_TIRE_DIFF_ON, true)
    fun setTireDiffOn(v: Boolean) { sp().edit().putBoolean(K_TIRE_DIFF_ON, v).apply() }

    /** 开门自动 360（默认开）：非主驾车门打开时弹后视画面，5 秒后自动关闭。 */
    fun doorAvmOn(): Boolean = sp().getBoolean(K_DOOR_AVM, true)
    fun setDoorAvmOn(v: Boolean) { sp().edit().putBoolean(K_DOOR_AVM, v).apply() }

    /** 胎压过低阈值，默认 240 kPa。判据的唯一出口，别在别处再读这个键。 */
    fun tireLowKpa(): Int = sp()
        .getInt(K_TIRE_LOW_KPA, TireRules.DEFAULT_LOW_KPA)
        .coerceIn(TireRules.LOW_MIN_KPA, TireRules.LOW_MAX_KPA)

    fun setTireLowKpa(v: Int) {
        sp().edit().putInt(K_TIRE_LOW_KPA, v.coerceIn(TireRules.LOW_MIN_KPA, TireRules.LOW_MAX_KPA)).apply()
    }

    /** 四轮极差阈值，默认 15 kPa。 */
    fun tireDiffKpa(): Int = sp()
        .getInt(K_TIRE_DIFF_KPA, TireRules.DEFAULT_DIFF_KPA)
        .coerceIn(TireRules.DIFF_MIN_KPA, TireRules.DIFF_MAX_KPA)

    fun setTireDiffKpa(v: Int) {
        sp().edit().putInt(K_TIRE_DIFF_KPA, v.coerceIn(TireRules.DIFF_MIN_KPA, TireRules.DIFF_MAX_KPA)).apply()
    }

    fun doorActiveHigh(): Boolean = sp().getBoolean(K_DOOR_ACTIVE_HIGH, true)
    fun setDoorActiveHigh(v: Boolean) { sp().edit().putBoolean(K_DOOR_ACTIVE_HIGH, v).apply() }

    /** 车窗开度大于这个百分比算「未关」，默认 1 → 与桌面车模 2..98 的判定一致。 */
    fun windowThreshold(): Int = sp().getInt(K_WINDOW_THRESHOLD, 1).coerceIn(0, 95)
    fun setWindowThreshold(v: Int) { sp().edit().putInt(K_WINDOW_THRESHOLD, v.coerceIn(0, 95)).apply() }

    /** 同一次熄火只提醒一次，重新上电（power_mode 非 0）时清掉。 */
    fun warnedThisPowerOff(): Boolean = sp().getBoolean(K_WARNED, false)
    fun setWarnedThisPowerOff(v: Boolean) { sp().edit().putBoolean(K_WARNED, v).apply() }

    /** 恒为 `classic`：老配置里存的 `airy`/`male` 一律归一，不再有第二种音色。 */
    fun pack(): String = PACK_IDS[0]

    /** 保留给设置页/备份恢复调用，写法统一归一到 [pack]。 */
    fun setPack(@Suppress("UNUSED_PARAMETER") p: String) {
        sp().edit().putString(K_PACK, PACK_IDS[0]).apply()
    }

    fun packName(): String = PACK_NAMES[0]

    /** 0..100，只作用于本 App 的提示音；按用户要求沿用旧版默认 25。 */
    fun volume(): Int = audio().getInt("vol", DEFAULT_VOL).coerceIn(0, 100)
    fun setVolume(v: Int) { audio().edit().putInt("vol", v.coerceIn(0, 100)).apply() }
    fun volumeF(): Float = volume() / 100f

    fun audioRoute(): Int = audio().getInt("route", DEFAULT_ROUTE).coerceIn(0, AUDIO_NAMES.size - 1)
    fun setAudioRoute(v: Int) { audio().edit().putInt("route", v.coerceIn(0, AUDIO_NAMES.size - 1)).apply() }
    fun audioName(): String = AUDIO_NAMES[audioRoute()]
    fun audioDesc(): String = AudioRoute.DESC[audioRoute()]
    fun audioUsage(): Pair<Int, Int> = AUDIO_USAGE[audioRoute()] to AUDIO_TYPE[audioRoute()]

    // ---------------- 音效文件名 ----------------

    /** 旧版 SceneStore.sound()：用户填了自定义文件名就用它，否则用默认。 */
    private fun sound(key: String, default: String): String {
        val custom = sp().getString(key, "")?.trim().orEmpty()
        return custom.ifEmpty { default }
    }

    fun doorDefault(slot: String, open: Boolean): String {
        val state = if (open) "open" else "close"
        return if (slot == "trunk") "trunk_$state.wav" else "door_${slot}_$state.wav"
    }

    fun doorFile(index: Int, open: Boolean): String {
        val slot = DOOR_SLOTS[index]
        val key = "snd_door_${slot}_${if (open) "open" else "close"}"
        return sound(key, doorDefault(slot, open))
    }

    fun gearDefault(g: String): String = "gear_$g.wav"

    fun gearFile(g: String): String = sound("snd_gear_$g", gearDefault(g))

    fun windowDefault(): String = "window.wav"

    fun windowFile(): String = sound("snd_window_warn", windowDefault())

    /** 胎压过低 / 差值过大的音效；文件不存在时 TireAlert 会自动改用 TTS。 */
    fun tireLowDefault(): String = "tire_low.wav"
    fun tireLowFile(): String = sound("snd_tire_low", tireLowDefault())

    fun tireDiffDefault(): String = "tire_diff.wav"
    fun tireDiffFile(): String = sound("snd_tire_diff", tireDiffDefault())

    /** 当前音色包要用到的全部音效，给 VoicePlayer 预加载。 */
    fun packFiles(): List<String> = buildList {
        for (slot in DOOR_SLOTS) {
            add(doorDefault(slot, true))
            add(doorDefault(slot, false))
        }
        for (g in GEAR_CHARS) add(gearDefault(g))
        add(windowDefault())
    }

    // ---------------- 信号判定 ----------------

    /**
     * 旧版 SceneEngine.isOpen()：默认高电平有效（1 开 0 关），
     * 可以切成低电平有效；-1 未知一律不算开。
     */
    fun isDoorOpen(raw: Int): Boolean = when {
        raw < 0 -> false
        doorActiveHigh() -> raw != 0
        else -> raw == 0
    }

    /**
     * 旧版 countOpenWindows()：四路车窗开度 > 阈值算未关。
     * 重写版多了天窗读数（旧版没有可靠通道），一并计入；
     * 上界 <99 / <95 与桌面车模的琥珀判定一致，避免平台属性恒为 1 或 100 时误报。
     */
    fun openWindowCount(s: CarSignal): Int {
        val th = windowThreshold()
        var n = 0
        for (v in s.windowPos.take(4)) if (v > th && v < 99) n++
        if (s.sunroof in 6..94) n++
        return n
    }
}
