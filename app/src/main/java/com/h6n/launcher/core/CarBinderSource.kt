package com.h6n.launcher.core

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 车身信号真实读取通道。
 *
 * 本车有四套可读通道，本文件把其中三套接全；**所有 transact code 与参数顺序
 * 都是从 `ICarInfo$Stub$Proxy` / `IPlatformAdapter` / `ICarSensorService` 的
 * 反编译结果逐条核对出来的，没有猜**：
 *
 * ## 通道 A：ICarInfo（德赛车况服务）
 * ```
 * bind  com.desay.svcarinfo/.CarInfoService
 * desc  com.desay.libsvcarinfo.ICarInfo
 *
 * 读    transact(5)  getItemValues(funcIndex, itemId)  -> int[]   （同步）
 * 写    transact(6)  sendItemValues(funcIndex, cmdId, int[])
 * 发帧  transact(7)  sendValues(propId, int[])        （oneway，直通 CarVendorExtension）
 * 订阅  transact(3)  registerCarInfoInsCallBack(funcIndex, cb, int[] items)（oneway）
 * ```
 *
 * ### 关键：第一个参数是「模块下标」而不是 moduleId
 * `CarInfoProxyManager.getItemValues()` 里先 `funcIdToIndex(moduleId)` 查
 * `ModuleID.FUNCTION_MAP` 数组得到下标再下发。这张表一共 25 项：
 * ```
 * 0..0x0F → 同值；0x11→16 0x12→17 0xFB→18 0xFC→19 0xFD→20 0xFE→21 0xFF→22
 * 0x100→23 0x101→24        （注意没有 0x10）
 * ```
 * 所以 `ID_READONLY_INFO(0xFE)` 要传 **21(0x15)**，
 * `ID_SHARE_DATA(0x101)` 要传 **24(0x18)**。
 *
 * ### 关键：返回值是 int[]，要按大端拼成一个 int
 * `getItemValues()` 回的是字节数组（服务端把每个字节塞进一个 int），
 * 原版 `intsToInt` 是 `r = (r shl 8) or (v and 0xFF)`，对 2 字节值等价于
 * 高位在前。少这一步会把 0x0BB8 读成 11。
 *
 * ## 通道 B：BeanTechs PlatformAdapter（驾驶模式 / 网络信号）
 * ```
 * 服务   ServiceManager.getService("com.beantechs.adapterservice")（反射拿，非 bind）
 * desc   com.beantechs.adapterservice.remote.IPlatformAdapter
 * 读     transact(3)  get(ns, key) -> readString()
 *        ns = "<包名>_<pid>"，同一个 ns 复用
 * ```
 * 这几个 key 在原版 APK 里是加密常量，已按 `Sec.s()`（Base64 + 24 字节轮转 XOR）解出：
 * ```
 * sys.vehicle.hardware.carspeed            车速（m/s，SystemProperties 同名键也能直接取）
 * car.basic.battery_voltage                电瓶电压
 * car.basic.battery_power_level            电瓶电量
 * car.drive_setting.driving_mode           驾驶模式（车机值，经 semOf/wireOf 换算，见 MODE_* 常量）
 * sys.network.mobile_network_signal_level  4G 信号等级
 * car.configure.idle_stop_and_go           启停能力
 * car.drive_setting.ass_state              启停状态
 * ```
 *
 * ## 通道 C：胎压（系统属性，不是 Binder）
 * ```
 * 读  SystemProperties  desay.onlnyread_config_50/52/54/56  四轮原始值 → kPa = raw × 1.375
 *                       desay.onlnyread_config_51/53/55/57  四轮胎温 → ℃ = raw - 50
 * ```
 * 这是旧版 `VehicleData.propRaw()` 的实车通道：caninfoservice 把 CAN 原始值写进系统属性，
 * 我们直接读属性即可，**不需要 bind 任何服务**。
 *
 * 另有一条 `com.desay_svautomotive.caninfoservice + ICarSensorService transact(11)`
 * 的兜底，但该服务在 H6 车机上绑不上（已装应用里没有这个包），所以只当兜底。
 *
 * ## 没接的
 * - 胎温：原版只有 `mock.tireTempC`，真实通道没有该 item。
 * - 均速 / 最高车速：原版是行程内自行累计，不是车况信号。
 * - 机油寿命 / 保养剩余：原版是本地保养计划估算（SharedPreferences），不走 Binder。
 *
 * ## 车窗 / 天窗（通道 B 平台属性，不是 Binder item）
 * ```
 * car.basic.window_pos_status     四门开度 0~100（有的平台后面再跟一位天窗）
 * car.basic.sunroof_pos_status    天窗开度，各版本键名不一 → 候选表探测
 * ```
 * 这一条是旧版 `WindowStrip` 实车验证过的读法：ICarInfo 的 `DOOR_WINDOW_STATE_*`
 * 只有 VR 写入口、读不到，但平台适配服务里能拿到位置值。判定（开/关/未知）
 * 留在 UI 侧按 `window.mode` / `window.sunroofMode` 配置算。
 */
internal object CarBinderSource {

    private const val TAG = "H6CarBinder"

    // ------------------------------------------------------------------ 通道 A

    const val PKG = "com.desay.svcarinfo"
    const val SVC = "com.desay.svcarinfo.CarInfoService"
    private const val ICAR_DESC = "com.desay.libsvcarinfo.ICarInfo"

    private const val TX_READ = 5
    private const val TX_SEND = 6
    private const val TX_SEND_FRAME = 7
    private const val TX_REGISTER = 3

    /** FUNCTION_MAP 下标（moduleId → index） */
    private const val FX_READONLY = 0x15     // 21 ← ID_READONLY_INFO(0xFE)
    private const val FX_CARSETTINGS = 0x0A  // 10 ← ID_CARSETTINGS(0x0A)

    /**
     * 空调模块。`ModuleID.ID_HVAC = 0x07`，落在 FUNCTION_MAP 的 0..0x0F 同值区，
     * 所以 funcIndex 也是 7（见《H6N-ICarInfo信号总表》）。
     *
     * ★ 读、写是**两套 item 编号**（总表里 `HvacID` 与 `HvacID$Send` 分两张表）：
     *   读内循环 `0x08`，写内循环 `0x09`。混用会读到别的字段。
     */
    private const val FX_HVAC = 0x07
    private const val IT_HVAC_RECIRC_READ = 0x08   // HvacID.ID_HVAC_RECIRCULATION
    private const val IT_HVAC_RECIRC_SEND = 0x09   // HvacID$Send.ID_HVAC_RECIRCULATION
    private const val IT_HVAC_VR_RECIRC = 0x66     // HvacID$Send.ID_VR_AIR_CIRCULATION（备选通道）
    private const val IT_HVAC_POWER_READ = 0x12    // HvacID.ID_HVAC_POWER（读数，自检用）
    private const val IT_HVAC_AC_READ = 0x01       // HvacID.ID_HVAC_AC（读数，自检用）

    /**
     * 座舱模块（车窗 / 天窗）。`ModuleID.ID_SEAT_WINDOW = 0x0B`，同样落在
     * FUNCTION_MAP 的 0..0x0F 同值区，所以 funcIndex 也是 11。
     *
     * 条目编号来自《H6N-ICarInfo信号总表》的 `cmdID/CockpitID` 段。
     * 这个模块**只有 VR（语音助手）写入口**，没有读入口 —— 车窗开度只能从
     * 平台属性 `car.basic.window_pos_status` 读（见 [readWindow]）。
     */
    private const val FX_SEAT_WINDOW = 0x0B
    private const val IT_WIN_VR = 0x00        // DOOR_WINDOW_VR_CONTROL（整车窗）
    private const val IT_WIN_FL = 0x01        // DOOR_WINDOW_STATE_FRONT_LEFT
    private const val IT_WIN_FR = 0x02        // DOOR_WINDOW_STATE_FRONT_RIGHT
    private const val IT_WIN_RL = 0x03        // DOOR_WINDOW_STATE_BACK_LEFT
    private const val IT_WIN_RR = 0x04        // DOOR_WINDOW_STATE_BACK_RIGHT
    private const val IT_SKY_VR = 0x05        // SKYLIGHT_VR_CONTROL（天窗）
    private const val IT_SHADE_VR = 0x06      // SUN_SHADE_VR_CONTROL（遮阳帘）

    /** 车门锁状态（`ReadOnlyID$Recv`），只读、在 FX_READONLY 里 */
    private const val IT_LOCK_STATUS = 0x4C   // DOOR_LOCK_STATUS

    /**
     * 背光/息屏实测通道。注意 0x18 是「下标 24 → ModuleID 0x101(ID_SHARE_DATA)」，
     * 不是 ID_OTHER(0x100，对应下标 23)。这一条是实车验证过的，别按名字改成 23。
     */
    private const val FX_BACKLIGHT = 0x18
    private const val CMD_BACKLIGHT = 0x21404410

    // 已核对的 itemId（全部属于 funcIndex 0x15 / module 0xFE）
    private const val IT_MILEAGE = 0x00   // BASIC_TOTAL_ODOMETER
    private const val IT_HEADLAMP = 0x01  // HEAD_LIGHT_STATUS
    private const val IT_RANGE = 0x06     // BASIC_REMAIN_ODOMETER（原版拿来当续航）
    private const val IT_WATER = 0x08     // BASIC_COOLANT_TEMP
    private const val IT_BATT = 0x0D      // BASIC_BATTERY_POWER_LEVEL
    private const val IT_DOOR_FL = 0x10
    private const val IT_DOOR_FR = 0x11
    private const val IT_DOOR_RL = 0x12
    private const val IT_DOOR_RR = 0x13
    private const val IT_DOOR_TRUNK = 0x14
    private const val IT_GEAR = 0x1A      // TRANSMISSION_GEAR
    private const val IT_SULFUR = 0x1B    // 电瓶硫化率（需主动发帧，见 requestSulfur）
    private const val IT_ENGINE = 0x1C    // CAR_BASIC_ENGINE_STATE
    private const val IT_ATF = 0x1D       // BASIC_TRANSMISSION_OILTEMP
    private const val IT_VOLT = 0x1E      // BASIC_BATTERY_VOLTAGE（0.01V）
    private const val IT_FUEL = 0x29      // BASIC_REMAIN_FUEL（L）

    /**
     * 驾驶模式请求（写）：属于 CARSETTINGS(0x0A) 模块，值域见下面的 [MODE_STANDARD] 等常量。
     */
    private const val IT_MODE_REQUEST = 0x25

    /**
     * 自动启停下发（写）：属于 CARSETTINGS(0x0A) 模块，item 0x2F（旧版日志误写成 32，
     * 实际 smali 里 writeInt 的是 0x2f=47）。值 int[]{1}=开启 / int[]{0}=关闭。
     */
    private const val IT_START_STOP = 0x2F

    /**
     * 「伴我回家」大灯延时（写 + 读），单位秒。
     *
     * 属于 **CARSETTINGS(0x0A)** 模块；注意 itemId 是**模块内编号**，可以和别的模块同号
     * （比如 READONLY_INFO 的 `IT_GEAR` 也是 0x1A，两者互不影响）。
     *
     * 原厂车辆设置页最小给 30 秒、没有"关"这一档，所以这里只能直接写 0，
     * 写不写得进去由 MCU 侧决定 —— 因此写入后**必须回读确认**，见 [setFollowHomeDelay]。
     */
    private const val IT_FOLLOW_HOME = 0x1A

    /** 「顶灯延时」（ROOT_LIGHT_DELAYTIME，秒），同一模块、紧邻伴我回家那一项。 */
    private const val IT_ROOF_DELAY = 0x19

    /** 「迎宾灯配置」（WELCOME_LIGHT_CONFIG），写它不影响伴我回家，仅作对照。 */
    private const val IT_WELCOME_LIGHT = 0x63

    // ------------------------------------------------------------------ 驾驶模式值域

    /**
     * 驾驶模式的**语义编号**（进程内统一用这套：界面三选、状态栏、方向盘长按都按它走）：
     *
     * ```
     * 1 标准   2 经济   3 运动   4 雪地   5 沙地   6 泥地   7 自动   8 纯电   9 节能  10 四驱
     * ```
     *
     * ★ 这是**内部语义值，不等于车机总线上的值**。下发给车机前经 [wireOf] 换成车机值，
     *   从车机读回来经 [semOf] 换回语义值。两处都必须转，漏一处就会出现「发的和显示的对不上」。
     *
     * ★★ 6.0.33 修「点标准变经济 / 点运动变标准 / 点经济变运动」：
     *    实车反馈（2026-11-12）观测到的车机值域是 `1 经济 / 2 运动 / 3 标准` ——
     *    与 systemui 反编译里 `BeanDriveMode` 的常量顺序正好把 1 和 3 对调了。
     *    之前拿语义值直接当车机值发（1 标准 / 2 经济 / 3 运动），于是每次点击都「跳一格」：
     *    点标准发 1 → 车机进经济，点运动发 3 → 车机进标准，点经济发 2 → 车机进运动。
     *    三个按钮同时错位、且错得有规律，正是这个原因（不是通道没通，也不是随机）。
     *    现默认按实车改正；别的车型若反过来，在 设置 → 车机 →「车型值域」点一下切回原厂编号。
     */
    const val MODE_STANDARD = 1
    const val MODE_ECO = 2
    const val MODE_SPORT = 3
    /** 有效语义值域（含雪地/沙地/泥地/自动/纯电/节能/四驱） */
    private const val MODE_MIN = 1
    private const val MODE_MAX = 10

    // ---- 语义值 ⇄ 车机值 ----------------------------------------------------

    private const val SP_MODE = "h6_car_mode"
    private const val K_MODE_REV = "rev13"

    /**
     * true = 车机值 `1 经济 / 2 运动 / 3 标准`（实车默认）
     * false = 原厂编号 `1 标准 / 2 经济 / 3 运动`
     */
    @Volatile private var modeRev = true

    fun modeReversed(): Boolean = modeRev

    /** 切换车型值域方案；同时落盘，下次启动沿用。 */
    fun setModeReversed(rev: Boolean) {
        modeRev = rev
        runCatching {
            appRef?.getSharedPreferences(SP_MODE, android.content.Context.MODE_PRIVATE)
                ?.edit()?.putBoolean(K_MODE_REV, rev)?.apply()
        }
    }

    /** 启动时把上次选的值域方案读回来（[attach] 调用）。 */
    private fun loadModeMap() {
        modeRev = runCatching {
            appRef?.getSharedPreferences(SP_MODE, android.content.Context.MODE_PRIVATE)
                ?.getBoolean(K_MODE_REV, true) ?: true
        }.getOrDefault(true)
    }

    /** 语义值 → 下发给车机的值。非三模式（雪地…四驱）原样透传。 */
    fun wireOf(mode: Int): Int = if (!modeRev) mode else when (mode) {
        MODE_STANDARD -> 3
        MODE_ECO -> 1
        MODE_SPORT -> 2
        else -> mode
    }

    /** 车机读回值 → 语义值。非三模式 / 无效值（-1、0xFF 之类）原样透传。 */
    fun semOf(raw: Int): Int = if (!modeRev) raw else when (raw) {
        1 -> MODE_ECO
        2 -> MODE_SPORT
        3 -> MODE_STANDARD
        else -> raw
    }

    /** 值 → 中文名，仅用于日志和诊断行；界面用各自的名字表。入参是**语义值**。 */
    fun driveModeName(mode: Int): String = when (mode) {
        MODE_STANDARD -> "标准"
        MODE_ECO -> "经济"
        MODE_SPORT -> "运动"
        4 -> "雪地"
        5 -> "沙地"
        6 -> "泥地"
        7 -> "自动"
        8 -> "纯电"
        9 -> "节能"
        10 -> "四驱"
        -1 -> "—"
        else -> "未知($mode)"
    }

    /**
     * 驾驶模式**读**（0x15/0x26）。
     * ★ 旧版 `VehicleData` 就是这个 item（`IT_DRIVE_MODE = 0x26`）。
     *   重写版早期只读平台 key `car.drive_setting.driving_mode`，车机上那个 key
     *   经常取不到 → 状态栏驾驶模式一直是「—」，看着就像"不显示驾驶模式"。
     */
    private const val IT_DRIVE_MODE = 0x26

    private val WATCH = intArrayOf(
        IT_MILEAGE, IT_HEADLAMP, IT_RANGE, IT_WATER, IT_BATT,
        IT_DOOR_FL, IT_DOOR_FR, IT_DOOR_RL, IT_DOOR_RR, IT_DOOR_TRUNK,
        IT_GEAR, IT_SULFUR, IT_ENGINE, IT_ATF, IT_VOLT, IT_FUEL, IT_DRIVE_MODE
    )

    /** 硫化率请求帧头：CarVendorExtension ID_SV_CAN_RAW_DATA */
    private const val PROP_CAN_RAW = 0x2140F000
    private const val FRAME_HEAD = 0x7523
    private const val MODULE_READONLY_ID = 0xFE

    // ------------------------------------------------------------------ 通道 B

    private const val PLAT_SERVICE = "com.beantechs.adapterservice"
    private const val PLAT_DESC = "com.beantechs.adapterservice.remote.IPlatformAdapter"
    private const val TX_PLAT_GET = 3
    /** requestCmdAsync(appKey, cmd, key, value)，oneway —— 写域走这条 */
    private const val TX_PLAT_CMD = 5
    private const val CMD_PLAT_SET = "cmd.common.request.set"

    private const val K_SPEED = "sys.vehicle.hardware.carspeed"
    private const val K_DRIVE_MODE = "car.drive_setting.driving_mode"
    /**
     * 自动启停（ASS）状态键。旧版 VehicleData 把这条 key 用 Sec.s 加密存着
     * （密文 7VC1dGDLBIQiw2uAB17XPqQh9xerdAzV70Wi），解出来就是这个明文。
     * 写 "1"/"0"，读回来同样是 "1"/"0"。
     */
    private const val K_START_STOP = "car.drive_setting.ass_state"
    private const val K_NET_SIGNAL = "sys.network.mobile_network_signal_level"
    private const val K_BATT_LEVEL = "car.basic.battery_power_level"
    private const val K_BATT_VOLT = "car.basic.battery_voltage"

    /** 四门车窗位置 0~100（原版 WindowStrip 的 KEY，实车可读） */
    private const val K_WINDOW_POS = "car.basic.window_pos_status"

    /** 天窗键候选表（旧版 WindowStrip 同表）：各平台版本叫法不一，探测第一个有值的 */
    private val ROOF_KEYS = arrayOf(
        "car.basic.sunroof_pos_status",
        "car.basic.sunroof_pos",
        "car.basic.sunroof_status",
        "car.basic.sun_roof_pos_status",
        "car.basic.sky_window_pos_status",
        "car.basic.roof_pos_status"
    )

    // ------------------------------------------------------------ 6.0.31 Car API 兜底（L2 平台属性）

    /**
     * 其它车型的 PlatformAdapter 键名不统一，按候选表探测第一个落在物理量程里的值。
     * 命中后缓存键名（同 [roofKey] 的做法），避免每 3s 轮询遍历全部候选。
     * 只补 ICarInfo 读不到的字段，读到有效值时一次都不会走这里。
     */
    private val FALLBACK_KEYS = mapOf(
        "range" to arrayOf(
            "car.basic.continue_voyage", "car.basic.remain_odometer",
            "car.basic.remaining_mileage", "car.basic.range", "car.basic.continue_mileage"
        ),
        "fuel" to arrayOf(
            "car.basic.fuel_level", "car.basic.remain_fuel", "car.basic.oil_state",
            "car.basic.fuel", "car.basic.fuel_percent"
        ),
        "water" to arrayOf(
            "car.basic.engine_cooling_fluid", "car.basic.coolant_temp",
            "car.basic.water_temp", "car.basic.engine_water_temp"
        ),
        "mileage" to arrayOf(
            "car.basic.total_odometer", "car.basic.mileage", "car.basic.odometer"
        )
    )
    private val foundKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val probeFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * ★ 6.0.60：**未命中必须回 null，不能用 -1**。
     *
     * `platInt()` 对「服务没起来 / 这个键没数据」一律回 -1，它和「真实读数是 -1」
     * 完全同形。旧版这个函数用 `-1` 表示未命中，而 water 的合法谓词写成
     * `it in -40..255`（想放行极寒环境的负温度），于是未命中值被当成 -1℃ 收下，
     * 状态栏就出现「油量 -- · 水温 -1℃ · 电压 --」这种只有水温有数的怪象
     * （2026-11-14 实机截图）。改用 null 表达「没读到」，调用方才有机会区分。
     */
    private fun probe(field: String, valid: (Int) -> Boolean): Int? {
        foundKeys[field]?.let { k ->
            val v = platInt(k)
            if (valid(v)) return v
        }
        val now = SystemClock.elapsedRealtime()
        // 全表探测失败后冷却 60s：平台服务晚启动会自愈，但别让 3s 轮询每轮都发十几次空 transact
        val failedAt = probeFailedAt[field] ?: 0L
        if (failedAt > 0L && now - failedAt < 60_000L) return null
        for (k in FALLBACK_KEYS[field].orEmpty()) {
            val v = platInt(k)
            if (valid(v)) {
                foundKeys[field] = k
                probeFailedAt.remove(field)
                return v
            }
        }
        foundKeys.remove(field)
        probeFailedAt[field] = now
        return null
    }

    // 通道 C

    /**
     * 胎压 / 胎温的 SystemProperty 前缀 —— caninfoservice 把 CAN 原始值写在这里，
     * 旧版 `VehicleData.propRaw()` 就是拼这个前缀直接读属性，实车验证可用。
     * 拼写 `onlnyread` 是原厂笔误，别"修正"。
     */
    private const val TIRE_PROP = "desay.onlnyread_config_"
    /** 四轮属性 id：左前 0x50 / 右前 0x52 / 左后 0x54 / 右后 0x56；胎温固定 +1 */
    private val TIRE_KPA_IDS = intArrayOf(0x50, 0x52, 0x54, 0x56)
    /** 四轮胎温 id：左前 0x51 / 右前 0x53 / 左后 0x55 / 右后 0x57 */
    private val TIRE_TEMP_IDS = intArrayOf(0x51, 0x53, 0x55, 0x57)
    /** 胎温原始值偏移：℃ = raw - 50 */
    private const val TIRE_TEMP_OFFSET = 50

    private const val TIRE_PKG = "com.desay_svautomotive.caninfoservice"
    private const val TIRE_CLS = "desay_svautomotive.car.service.CarSensorService"
    private const val TIRE_DESC = "com.desay_svautomotive.caninfoservice.ICarSensorService"
    private const val TX_TIRE_PROP = 11
    private const val TX_TIRE_STATE = 12
    private const val PROP_TIRE_PRESSURE = 2
    /** 原版换算：raw × 1.375 → kPa */
    private const val TIRE_KPA_SCALE = 1.375f

    // ------------------------------------------------------------------ 状态

    @Volatile private var icar: IBinder? = null
    @Volatile private var tire: IBinder? = null
    @Volatile private var plat: IBinder? = null
    @Volatile private var platNs: String? = null

    @Volatile private var binding = false
    @Volatile private var appRef: Application? = null
    @Volatile private var started = false

    /**
     * 刚下发但车机还没回报的驾驶模式。
     *
     * 车控执行有延迟（实测 1~3 秒），这段时间 platGet 读回来仍是旧值。
     * 如果不做这层保护，点完"运动"会先闪回"标准"再跳回来 —— 看上去就是"点了没反应"。
     * 6 秒内以本地值为准，超时自动放弃、回到真实值。
     */
    @Volatile private var pendingMode = -1
    @Volatile private var pendingAt = 0L

    /**
     * 天窗键覆盖（`window.sunroofKey` 配了就强制用它）。
     * 由 UI 侧按配置写入；为空则走 [ROOF_KEYS] 候选表自动探测。
     */
    @Volatile var sunroofKeyOverride: String? = null

    /** 探测到的天窗键 + 探测时间（60 秒内不重复探测，键读空了就立刻重探） */
    @Volatile private var roofKey: String? = null
    @Volatile private var roofKeyAt = 0L

    /** 缓存 SystemProperties.get(String,String)，避免每帧反射查方法 */
    private val sysGet: java.lang.reflect.Method? by lazy {
        try {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java)
        } catch (t: Throwable) {
            Log.w(TAG, "取 SystemProperties.get 失败", t)
            null
        }
    }

    // ------------------------------------------------------------------ 生命周期

    fun attach(app: Application, scope: CoroutineScope, state: MutableStateFlow<CarSignal>) {
        appRef = app
        loadModeMap()
        bindIcar(app, scope, state)
        bindTire(app)
        if (started) return
        started = true
        startFastLoop(scope, state)
        startSlowLoop(scope, state)
    }

    private fun bindIcar(app: Application, scope: CoroutineScope, state: MutableStateFlow<CarSignal>) {
        if (binding || icar != null) return
        binding = true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                icar = service
                binding = false
                val desc = descriptor(service)
                Log.i(TAG, "ICarInfo 已连接 desc=$desc")
                if (desc != ICAR_DESC) {
                    Log.w(TAG, "descriptor 与预期不符，读值可能无效：$desc != $ICAR_DESC")
                }
                service?.let { registerPush(it, state) }
                state.update { it.copy(source = "binder") }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                icar = null
                binding = false
                state.update { it.copy(source = "binder-down") }
                Log.w(TAG, "ICarInfo 断开")
            }

            override fun onBindingDied(name: ComponentName?) {
                icar = null
                binding = false
                Log.w(TAG, "ICarInfo 绑定失效，2s 后重试")
                scope.launch(Dispatchers.IO) {
                    delay(2000)
                    appRef?.let { bindIcar(it, scope, state) }
                }
            }
        }
        runCatching {
            val it = Intent().apply { setClassName(PKG, SVC) }
            val ok = app.bindService(it, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService(CarInfo) -> $ok")
            if (!ok) binding = false
        }.onFailure {
            binding = false
            Log.w(TAG, "bindService(CarInfo) 失败", it)
        }
    }

    private fun bindTire(app: Application) {
        if (tire != null) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                tire = service
                // 同一个 binder 复用给 6.0.31 的车厂 SPI 兜底（续航 / 油量 / 冷却液温度）
                CarApiFallback.spiBinder = service
                Log.i(TAG, "CarSensor 已连接 desc=${descriptor(service)}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                tire = null
                CarApiFallback.spiBinder = null
                Log.w(TAG, "CarSensor 断开")
            }
        }
        runCatching {
            val it = Intent().apply { setClassName(TIRE_PKG, TIRE_CLS) }
            val ok = app.bindService(it, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService(CarSensor) -> $ok")
        }.onFailure { Log.w(TAG, "bindService(CarSensor) 失败", it) }
    }

    // ------------------------------------------------------------------ 读：ICarInfo

    /**
     * 读一个 item。返回 -1 表示通道不可用/读失败（与"值本身就是 -1"不区分，
     * 业务上 -1 一律当未知）。
     */
    private fun readItem(fx: Int, item: Int): Int {
        val b = icar ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ICAR_DESC)
            data.writeInt(fx)
            data.writeInt(item)
            if (!b.transact(TX_READ, data, reply, 0)) return -1
            reply.readException()
            joinBytes(reply.createIntArray())
        } catch (t: Throwable) {
            Log.w(TAG, "readItem(fx=0x${fx.toString(16)}, item=0x${item.toString(16)}) 失败", t)
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** 服务端把每个字节塞进一个 int 返回，这里按大端拼回去。 */
    private fun joinBytes(arr: IntArray?): Int {
        if (arr == null || arr.isEmpty()) return -1
        var r = 0
        for (v in arr) r = (r shl 8) or (v and 0xFF)
        return r
    }

    // ------------------------------------------------------------------ 读：PlatformAdapter

    private fun platBinder(): IBinder? {
        plat?.let { return it }
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getMethod("getService", String::class.java)
            val b = get.invoke(null, PLAT_SERVICE) as? IBinder
            if (b != null) plat = b
            b
        } catch (t: Throwable) {
            Log.w(TAG, "取 PlatformAdapter 失败", t)
            null
        }
    }

    private fun platGet(key: String): String {
        val b = platBinder() ?: return ""
        val ns = platNs ?: run {
            val app = appRef ?: return ""
            "${app.packageName}_${android.os.Process.myPid()}".also { platNs = it }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PLAT_DESC)
            data.writeString(ns)
            data.writeString(key)
            if (!b.transact(TX_PLAT_GET, data, reply, 0)) return ""
            reply.readException()
            reply.readString() ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "platGet($key) 失败", t)
            ""
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun platInt(key: String): Int =
        platGet(key).trim().let { if (it.isEmpty()) -1 else it.toFloatOrNull()?.roundToInt() ?: -1 }

    /**
     * 把"刚下发、还没回报"的模式盖在真实读值上（6 秒窗口）。
     * 车机一旦回报成目标值就立刻清掉本地覆盖，之后完全以车控为准。
     *
     * 合法值域是 [MODE_MIN]..[MODE_MAX]（1..10）：车控偶尔会回报 0（未定义）
     * 或 0xFF，这些一律归成 -1 表示"读不到"，界面显示「—」，
     * 不要让它变成「未知(0)」这种既不像故障也不像数据的字样。
     */
    private fun effectiveMode(raw: Int): Int {
        val valid = raw in MODE_MIN..MODE_MAX
        val p = pendingMode
        if (p < 0) return if (valid) raw else -1
        if (valid && raw == p) {
            pendingMode = -1
            return raw
        }
        if (android.os.SystemClock.elapsedRealtime() - pendingAt > 6000L) {
            pendingMode = -1
            return if (valid) raw else -1
        }
        return p
    }

    /**
     * PlatformAdapter 写命令。参数顺序照 `requestCmdAsync(appKey, cmd, key, value)`，
     * oneway（不等返回），和原版一致。
     */
    private fun platRequestCmd(cmd: String, key: String, value: String): Boolean {
        val b = platBinder() ?: return false
        val ns = platNs ?: run {
            val app = appRef ?: return false
            "${app.packageName}_${android.os.Process.myPid()}".also { platNs = it }
        }
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PLAT_DESC)
            data.writeString(ns)
            data.writeString(cmd)
            data.writeString(key)
            data.writeString(value)
            b.transact(TX_PLAT_CMD, data, null, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "platRequestCmd($key=$value) 失败", t)
            false
        } finally {
            data.recycle()
        }
    }

    /**
     * 切换驾驶模式。mode 用**原厂 BeanDriveMode 值域**：1 标准 / 2 经济 / 3 运动
     * （另有 4 雪地 / 5 沙地 / 6 泥地 / 7 自动 / 8 纯电 / 9 节能 / 10 四驱）。
     *
     * 两条路一起走（照原版 VehicleData.setDriveModeAsync）：
     *  1) PlatformAdapter `cmd.common.request.set` + `car.drive_setting.driving_mode`
     *  2) ICarInfo CARSETTINGS(0x0A) item 0x25（CAR_DRIVING_MODE_REQUEST）= [mode]
     * 实测哪条生效取决于车机固件，两条都发不会互相打架（同值）。
     */
    fun setDriveMode(mode: Int): Boolean {
        if (mode !in MODE_MIN..MODE_MAX) return false
        // 语义值 → 车机值：不同车型值域不同，统一在这一层换（见 wireOf 的注释）
        val wire = wireOf(mode)
        val platOk = platRequestCmd(CMD_PLAT_SET, K_DRIVE_MODE, wire.toString())
        val carOk = sendItemValues(FX_CARSETTINGS, IT_MODE_REQUEST, intArrayOf(wire))
        if (platOk || carOk) {
            // pending 存**语义值**：读回来的车机值会先经 semOf 再和它比
            pendingMode = mode
            pendingAt = android.os.SystemClock.elapsedRealtime()
        }
        Log.i(
            TAG,
            "切换驾驶模式 → $mode(${driveModeName(mode)}) 下发 $wire" +
                "（rev=${modeRev}, platform=$platOk, icar=$carOk）"
        )
        return platOk || carOk
    }

    // --------------------------------------------------------------- 读/写：自动启停

    /**
     * 读自动启停当前状态。1=开启（有启停）、0=已关闭、-1=读不到。
     *
     * 与旧版 readStartStop 同口径：
     *  1) PlatformAdapter `get(car.drive_setting.ass_state)` → "1"/"0"
     *  2) 读不到再走 ICarInfo CARSETTINGS(0x0A) item 0x2F（旧版还有一条 0x20 兜底）
     * 只要读到就是确定的 0/1，不用 -1 当哨兵混淆。
     */
    fun readStartStop(): Int {
        platGet(K_START_STOP).trim().let {
            if (it == "1") return 1
            if (it == "0") return 0
        }
        val v = readItem(FX_CARSETTINGS, IT_START_STOP)
        if (v == 0 || v == 1) return v
        val alt = readItem(FX_CARSETTINGS, 0x20)
        if (alt == 0 || alt == 1) return alt
        return -1
    }

    /**
     * 下发自动启停开关。与旧版双通道一致：
     *  1) PlatformAdapter `cmd.common.request.set` + `car.drive_setting.ass_state` = "1"/"0"
     *  2) ICarInfo CARSETTINGS(0x0A) item 0x2F = int[]{1|0}（旧版 sendStartStop / oneway）
     * 两条都发、任一成功即视为已下发；不下发失败回滚（调用方下发前已读状态判过）。
     */
    fun setStartStop(on: Boolean): Boolean {
        val value = if (on) "1" else "0"
        val platOk = platRequestCmd(CMD_PLAT_SET, K_START_STOP, value)
        val carOk = sendItemValues(FX_CARSETTINGS, IT_START_STOP, intArrayOf(if (on) 1 else 0))
        Log.i(TAG, "下发自动启停 → ${if (on) "开启" else "关闭"}（platform=$platOk, icar=$carOk）")
        return platOk || carOk
    }

    // ------------------------------------------------------------------ 读：胎压

    /**
     * 四轮胎压 kPa。
     *
     * ★ 主通道是 **SystemProperty** `desay.onlnyread_config_<id>`，不是 Binder：
     *   caninfoservice 把 CAN 原始值写进系统属性，旧版就是这么读的（实车验证过）。
     *   重写版早期改走 CarSensor 的 `transact(11) getTireProp`，那个服务在车上根本
     *   绑不上（`com.desay_svautomotive.caninfoservice` 不在已装应用列表里），
     *   结果四个胎压一直是空 → 车模两侧的数字整块不画，看着就是「胎压没了」。
     *
     * id 排布（旧版 `TIRE_FUNC_KPA`）：左前 0x50 / 右前 0x52 / 左后 0x54 / 右后 0x56，
     * 胎温固定是 +1（0x51/0x53/0x55/0x57）。有效区间 1..500，换算 `kPa = raw × 1.375`。
     */
    private fun readTireKpa(): IntArray {
        val out = IntArray(4) { -1 }
        for (i in 0 until 4) {
            val raw = sysInt(TIRE_PROP + TIRE_KPA_IDS[i])
            if (raw in 1..500) out[i] = (raw * TIRE_KPA_SCALE).roundToInt()
        }
        if (out.any { it > 0 }) return out
        // 兜底：少数车型确实开了 CarSensor，能读到就用它的
        return IntArray(4) { i ->
            val raw = tireProp(PROP_TIRE_PRESSURE, i)
            if (raw > 0) (raw * TIRE_KPA_SCALE).roundToInt() else -1
        }
    }

    /** 四轮胎温 ℃：独立的 0x51/0x53/0x55/0x57，原始值减 50 度。 */
    private fun readTireTemp(): List<Int> {
        val out = IntArray(4) { -1 }
        var any = false
        for (i in 0 until 4) {
            // 注意：别写成 TIRE_PROP + TIRE_KPA_IDS[i] + 1 —— Kotlin 会先拼字符串再加 1，
            // 变成 "..._801" 这种不存在的属性名，胎温永远读不到。
            val raw = sysInt(TIRE_PROP + TIRE_TEMP_IDS[i])
            if (raw in 1..150) {
                out[i] = raw - TIRE_TEMP_OFFSET
                any = true
            }
        }
        return if (any) out.toList() else emptyList()
    }

    private fun sysInt(key: String): Int {
        val m = sysGet ?: return -1
        return try {
            (m.invoke(null, key, "") as? String)?.trim()?.toIntOrNull() ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * 诊断用：把四个胎压属性的原始字符串原样报出来。
     * 设置页「车机 → 车辆数据」里显示，用来区分「属性没值（车没写）」和「有值但没画」。
     */
    fun tireDebug(): String {
        val m = sysGet ?: return "SystemProperties 不可用"
        val sb = StringBuilder()
        for (id in TIRE_KPA_IDS) {
            val v = try {
                (m.invoke(null, TIRE_PROP + id, "") as? String)?.trim().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            if (sb.isNotEmpty()) sb.append("  ")
            sb.append(Integer.toHexString(id)).append('=')
            sb.append(if (v.isEmpty()) "—" else v)
        }
        // 胎温属性（0x51/0x53/0x55/0x57）一起报：卡片上没温度时，靠这一行区分
        // 「车没写这几个属性」和「有值但没画」。
        sb.append("  ｜ 温 ")
        for (id in TIRE_TEMP_IDS) {
            val v = try {
                (m.invoke(null, TIRE_PROP + id, "") as? String)?.trim().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            sb.append(Integer.toHexString(id)).append('=')
            sb.append(if (v.isEmpty()) "—" else v).append(' ')
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ 硫化率诊断

    /** 最近一次从 ICarInfo 读到 / 推送来的硫化率原始值，-1 = 还没收到过。 */
    @Volatile private var sulfurRaw = -1
    @Volatile private var sulfurRawAt = 0L

    private fun noteSulfurRaw(v: Int) {
        if (v < 0) return   // 这次没读到，别把上一条诊断冲掉
        sulfurRaw = v
        sulfurRawAt = SystemClock.elapsedRealtime()
    }

    /** 设置页「车机 → 车辆数据」显示：用来确认那台车是不是推了 0xFF 无效值。 */
    fun sulfurDebug(): String {
        val v = sulfurRaw
        val age = if (sulfurRawAt == 0L) 0
        else ((SystemClock.elapsedRealtime() - sulfurRawAt) / 1000).toInt()
        return when {
            v < 0 -> "尚未收到硫化率数据"
            v !in 0..100 -> "原始值 $v（0xFF / 越界 = 无效，已忽略）· ${age}s 前"
            else -> "原始值 $v%（有效）· ${age}s 前"
        }
    }

    // ------------------------------------------------------------------ 车型兼容诊断

    @Volatile private var rawRange = -1
    @Volatile private var rawFuel = -1
    @Volatile private var rawWater = -1
    @Volatile private var rawVolt = -1
    @Volatile private var rawMileage = -1
    @Volatile private var rawAt = 0L

    /** 记录**清洗前**的原始值，供设置页诊断；清洗后无效也照样记，方便做车型适配。 */
    private fun noteRaw(item: Int, v: Int) {
        if (v < 0) return   // 这次没读到，别冲掉上一条诊断
        when (item) {
            IT_RANGE -> rawRange = v
            IT_FUEL -> rawFuel = v
            IT_WATER -> rawWater = v
            IT_VOLT -> rawVolt = v
            IT_MILEAGE -> rawMileage = v
            else -> return
        }
        rawAt = SystemClock.elapsedRealtime()
    }

    /** 设置页「状态栏原始值」：不同车型 CAN 量纲不同，把这行截图发回来即可针对性适配。 */
    fun signalDebug(): String {
        if (rawAt == 0L) return "尚未读到车况原始数据"
        val age = ((SystemClock.elapsedRealtime() - rawAt) / 1000).toInt()
        fun mark(item: Int, v: Int): String = when {
            v < 0 -> "—"
            cleanInt(item, v) < 0 -> "$v(无效)"
            else -> "$v"
        }
        return "续航 ${mark(IT_RANGE, rawRange)} · 油量 ${mark(IT_FUEL, rawFuel)} · " +
                "水温 ${mark(IT_WATER, rawWater)} · 电压 ${mark(IT_VOLT, rawVolt)} · " +
                "里程 ${mark(IT_MILEAGE, rawMileage)} · ${age}s 前"
    }

    private fun tireProp(propId: Int, wheel: Int): Int {
        val b = tire ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TIRE_DESC)
            data.writeInt(propId)
            data.writeInt(wheel)
            if (!b.transact(TX_TIRE_PROP, data, reply, 0)) return -1
            reply.readInt()
        } catch (t: Throwable) {
            -1
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ------------------------------------------------------------------ 订阅推送

    /** 回调接口描述符（`ICarInfoIntsCallBack$Stub$Proxy` 里写死的 token） */
    private const val CB_DESC = "com.desay.libsvcarinfo.ICarInfoIntsCallBack"

    /**
     * 注册车况推送。服务端会回调 `onChangeEvent(moduleID, cmdID, int[])`（事务码 1）。
     *
     * 订阅只是加速：失败也不影响轮询，所以整体 try 住。
     */
    private fun registerPush(b: IBinder, state: MutableStateFlow<CarSignal>) {
        val cb = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                return try {
                    // 回调 Parcel **带** interface token，不 enforce 会把
                    // token 的字符串长度读成 moduleID，后面全部错位。
                    data.enforceInterface(CB_DESC)
                    data.readInt()                        // moduleID（不关心，只按 item 分发）
                    val item = data.readInt()
                    val arr = data.createIntArray()
                    onPush(item, joinBytes(arr), state)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "回调解析失败", t)
                    true
                }
            }
        }
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ICAR_DESC)
            data.writeInt(FX_READONLY)
            data.writeStrongBinder(cb)
            data.writeIntArray(WATCH)
            val ok = b.transact(TX_REGISTER, data, null, 1)
            Log.i(TAG, "registerPush -> $ok (${WATCH.size} items)")
        } catch (t: Throwable) {
            Log.w(TAG, "registerPush 失败", t)
        } finally {
            data.recycle()
        }
    }

    /** 推送来的单条 item 直接并进当前帧，省一次轮询。 */
    private fun onPush(item: Int, value: Int, state: MutableStateFlow<CarSignal>) {
        // 只认自己订阅过的那几项：回调参数一旦错位，垃圾 item 不会写进 UI
        if (value < 0 || item !in WATCH) return

        // ★ 6.0.31：车控约定 0xFF / 0xFFFF / 0x7F 都是 Invalid（原厂工程模式显示 Invalid，
        //   老版 Vulcan.java 也明确「0xFF 或超出 0..100 一律视为无效」）。
        //   订阅推送和轮询必须走同一套清洗，否则其它车型推的「续航 65535 / 油量 255 /
        //   水温 47℃(=0x7F)」会从这条路径漏进状态栏。原始值先留档便于车型适配。
        if (item == IT_SULFUR) noteSulfurRaw(value)
        noteRaw(item, value)
        val v = cleanInt(item, value)
        if (v < 0) return

        state.update { cur ->
            when (item) {
                IT_WATER -> cur.copy(coolantC = waterC(v))
                IT_VOLT -> cur.copy(volt = v / 100f)
                IT_BATT -> cur.copy(battPct = v)
                IT_GEAR -> cur.copy(gear = gearChar(v))
                IT_RANGE -> cur.copy(rangeKm = v)
                IT_FUEL -> cur.copy(fuelLiters = v.toFloat(), fuelPct = fuelPctOf(v))
                IT_MILEAGE -> cur.copy(mileageKm = v)
                IT_ATF -> cur.copy(atfC = atfC(v))
                IT_SULFUR -> cur.copy(sulfurPct = v)
                IT_ENGINE -> cur.copy(engineOn = v > 0)
                // 0x26 驾驶模式：车机值经 semOf 换成语义值（1 标准 / 2 经济 / 3 运动 …）
                IT_DRIVE_MODE -> cur.copy(driveMode = effectiveMode(semOf(v)))
                // 0x01 大灯：0 白天 / 1 夜间 —— 桌面夜间主题与高德昼夜同步的判据
                IT_HEADLAMP -> cur.copy(night = v)
                // 车门 5 路（6.0.69）：[WATCH] 里一直订阅着这几项，但以前这里没有分支，
                //   推送到了被丢掉，车门只能等 3s 一轮的 refresh() —— 「开门自动 360」
                //   最坏要差 3 秒才上屏。这里并进当前帧，语义与轮询完全一致
                //   （1 开 / 0 关，清洗同上方的 cleanInt），顺带让车模与门播报也跟手。
                IT_DOOR_FL, IT_DOOR_FR, IT_DOOR_RL, IT_DOOR_RR, IT_DOOR_TRUNK -> {
                    val idx = when (item) {
                        IT_DOOR_FL -> 0
                        IT_DOOR_FR -> 1
                        IT_DOOR_RL -> 2
                        IT_DOOR_RR -> 3
                        else -> 4
                    }
                    val doors = cur.doors.toMutableList()
                    while (doors.size < 5) doors.add(-1)
                    doors[idx] = if (v > 0) 1 else 0
                    // 门窗字段要过一次 decorate：模拟信号开着时仍由 SimSignals 接管
                    SignalHub.decorate(cur.copy(doors = doors))
                }
                else -> cur
            }
        }
    }

    // ------------------------------------------------------------------ 轮询

    /** 车速单独跑 1s：走系统属性，代价只有一次反射调用。 */
    private fun startFastLoop(scope: CoroutineScope, state: MutableStateFlow<CarSignal>) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val kmh = readSpeedKmh()
                    if (kmh >= 0) state.update { it.copy(speedKmh = kmh) }
                }
                delay(1000)
            }
        }
    }

    /** 其余信号 3s 一轮（原版 pollMs 也是 3000）。 */
    private fun startSlowLoop(scope: CoroutineScope, state: MutableStateFlow<CarSignal>) {
        var tick = 0
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { refresh(state) }
                // 硫化率不是广播量，必须周期性发请求帧让车控回一帧
                if (tick % 4 == 0) runCatching { requestSulfur() }
                tick++
                delay(3000)
            }
        }
    }

    private data class Fallback(
        val range: Int = -1,
        val fuel: Float = -1f,
        val water: Float = Float.NaN,
        val mileage: Int = -1,
        val origin: String = ""
    )

    /**
     * 6.0.31 Car API 逐层兜底：L2 平台 `car.*` → L3 车厂 SPI → L4 标准 `android.car`。
     * 四个布尔表示 ICarInfo 哪些字段没读到；只有缺的字段才往下走，读到可信值即停。
     * ICarInfo 全都有值时直接返回空 Fallback，一次额外 Binder 调用都不会发生。
     */
    private fun carApiFallback(
        missRange: Boolean, missFuel: Boolean, missWater: Boolean, missMileage: Boolean
    ): Fallback {
        if (!missRange && !missFuel && !missWater && !missMileage) return Fallback()
        var fbRange = -1
        var fbFuel = -1f
        var fbWater = Float.NaN
        var fbMileage = -1
        var fbOrigin = ""

        // L2：PlatformAdapter 的 car.* 候选键（各车型键名不一，探测 + 缓存 + 失败冷却）
        if (missRange) probe("range") { it in 1..2000 }?.let { fbRange = it }
        if (missFuel) {
            val v = probe("fuel") { it in 1..150 }
            fbFuel = when {
                v == null -> -1f
                v in 1..100 -> v / 100f * 55f        // 百分比 → 按 55L 油箱折算
                else -> v.toFloat()                  // 101..150 直接就是升数
            }
        }
        if (missWater) {
            // ★ 6.0.60：谓词显式排除 -1 —— 平台 API 的 -1 是「无数据」，不是 -1℃。
            //   未命中现在回 null（见 [probe]），-1 也不会再被 -40..255 收成合法温度。
            val v = probe("water") { it != -1 && it in -40..255 }
            fbWater = when {
                v == null -> Float.NaN
                v in -40..130 -> v.toFloat()
                else -> (v * 0.75f - 48f).roundToInt().toFloat()
                    .takeIf { it in -40f..130f } ?: Float.NaN
            }
        }
        if (missMileage) probe("mileage") { it in 0..999999 }?.let { fbMileage = it }

        // L3：车厂 SPI ICarSensorService（复用已绑定的 CarSensor binder）
        val needSpi = (missRange && fbRange < 0) || (missFuel && fbFuel < 0f) ||
                (missWater && fbWater.isNaN())
        if (needSpi) {
            val spi = CarApiFallback.readSpi()
            if (missRange && fbRange < 0) spi.rangeKm?.let { fbRange = it }
            if (missFuel && fbFuel < 0f) spi.fuelLiters?.let { fbFuel = it }
            if (missWater && fbWater.isNaN()) spi.coolantC?.let { fbWater = it }
        }

        // L4：标准 android.car.CarSensorManager（单位最明确：油量% / 续航km / 里程km）
        val needCar = (missRange && fbRange < 0) || (missFuel && fbFuel < 0f) ||
                (missMileage && fbMileage < 0)
        if (needCar) {
            appRef?.let { app ->
                val car = CarApiFallback.readStandardCar(app)
                if (missRange && fbRange < 0) car.rangeKm?.let { fbRange = it }
                if (missFuel && fbFuel < 0f) car.fuelLiters?.let { fbFuel = it }
                if (missMileage && fbMileage < 0) car.mileageKm?.let { fbMileage = it }
            }
        }

        fbOrigin = listOfNotNull(
            if (fbRange >= 0) "续航" else null,
            if (fbFuel >= 0f) "油量" else null,
            if (!fbWater.isNaN()) "水温" else null,
            if (fbMileage >= 0) "里程" else null
        ).joinToString("/")
        if (fbOrigin.isNotEmpty()) CarApiFallback.noteHit("Car API 补齐 $fbOrigin")
        return Fallback(fbRange, fbFuel, fbWater, fbMileage, fbOrigin)
    }

    private fun refresh(state: MutableStateFlow<CarSignal>) {
        if (icar == null) {
            // Binder 没连上时至少把驾驶模式/信号等级从 platform 拿回来
            val mode = effectiveMode(semOf(platInt(K_DRIVE_MODE)))
            val sig = platInt(K_NET_SIGNAL)
            // 电源模式（car.basic.power_mode）即使 Binder 没连上也要读：
            // 「熄火未关车窗」提醒靠它由非 0 → 0 的边沿触发。
            val pwr = platInt("car.basic.power_mode")
            // ★ 6.0.31：Binder 连不上时恰恰最需要 Car API 兜底（其它车型可能压根没这个服务）
            val fb = carApiFallback(true, true, true, true)
            if (mode >= 0 || sig >= 0 || pwr >= 0 || fb.origin.isNotEmpty()) {
                state.update { cur ->
                    SignalHub.decorate(cur.copy(
                        driveMode = if (mode >= 0) mode else cur.driveMode,
                        signalLevel = if (sig >= 0) sig.coerceIn(0, 4) else cur.signalLevel,
                        powerMode = if (pwr >= 0) pwr else cur.powerMode,
                        rangeKm = if (fb.range >= 0) fb.range else cur.rangeKm,
                        fuelLiters = if (fb.fuel >= 0f) fb.fuel else cur.fuelLiters,
                        fuelPct = if (fb.fuel >= 0f) fuelPctOf(fb.fuel.roundToInt()) else cur.fuelPct,
                        coolantC = if (!fb.water.isNaN()) fb.water else cur.coolantC,
                        mileageKm = if (fb.mileage >= 0) fb.mileage else cur.mileageKm,
                        source = if (fb.origin.isNotEmpty()) "carapi" else cur.source
                    ))
                }
            }
            return
        }

        // ★ 6.0.31：所有 ICarInfo 读数统一过一遍清洗 + 清洗前原始值留档。
        //   其它车型在没有该信号时会推 0xFF / 0xFFFF / 0x7F 这类 Invalid 标记，
        //   不过滤就会显示「续航 65535km / 油量 255L / 水温 47℃」。
        fun read(item: Int): Int {
            val raw = readItem(FX_READONLY, item)
            if (item == IT_SULFUR) noteSulfurRaw(raw)
            noteRaw(item, raw)
            return cleanInt(item, raw)
        }

        val mileage = read(IT_MILEAGE)
        val range = read(IT_RANGE)
        val water = read(IT_WATER)
        val batt = read(IT_BATT)
        val fuel = read(IT_FUEL)
        val gear = read(IT_GEAR)
        val atf = read(IT_ATF)
        val volt = read(IT_VOLT)
        val sulfur = read(IT_SULFUR)
        val engine = read(IT_ENGINE)
        val head = read(IT_HEADLAMP)
        val dFl = read(IT_DOOR_FL)
        val dFr = read(IT_DOOR_FR)
        val dRl = read(IT_DOOR_RL)
        val dRr = read(IT_DOOR_RR)
        val dTr = read(IT_DOOR_TRUNK)

        val tireKpa = readTireKpa()
        val tireTemp = readTireTemp()

        // 空调内循环（6.0.100）：桌面那个小图标靠它显示「内 / 外」，多一次 transact/3s。
        // 读到别的值（车型不同、AQS 之类）就当没读到，图标显示「空调 · 未读取」。
        val recirc = hvacRead(IT_HVAC_RECIRC_READ)?.takeIf { it == 0 || it == 1 } ?: -1

        // 车门锁（6.0.103）：熄火自动关窗的第二个条件。值域各车型不一（0 未锁 / 1 已锁
        // 是常见约定，也有 2 = 已锁的），所以**原值**交给 AutoClose，由设置页的「反相」
        // 开关决定怎么解释，这里不擅自归一化。
        val lock = read(IT_LOCK_STATUS)

        // 驾驶模式优先取 ICarInfo 0x15/0x26（旧版 VehicleData 的读法），
        // 取不到再退回平台 key —— 只读平台 key 在本车上经常是 -1，状态栏就显示成「—」。
        val modeRaw = read(IT_DRIVE_MODE)
        // 车机值 → 语义值（semOf），再走 pending 覆盖；见 wireOf 上方的值域说明
        val modeItem = if (modeRaw in MODE_MIN..MODE_MAX) semOf(modeRaw) else -1
        val mode = effectiveMode(if (modeItem >= 0) modeItem else semOf(platInt(K_DRIVE_MODE)))
        val sig = platInt(K_NET_SIGNAL)
        val battPlat = platInt(K_BATT_LEVEL)
        val voltPlat = platGet(K_BATT_VOLT).trim().toFloatOrNull()
        val window = readWindow()

        // 6.0.31：ICarInfo 有缺口时才逐层走 Car API 兜底
        val fb = carApiFallback(range < 0, fuel < 0, water < 0, mileage < 0)

        state.update { cur ->
            // 末尾过一次 decorate：模拟信号开启时门窗字段由 SimSignals 接管，
            // 关闭时这一层是直通的，真实门窗读数一个字节都不动。
            SignalHub.decorate(cur.copy(
                mileageKm = if (mileage >= 0) mileage
                            else if (fb.mileage >= 0) fb.mileage else cur.mileageKm,
                rangeKm = if (range >= 0) range
                          else if (fb.range >= 0) fb.range else cur.rangeKm,
                coolantC = if (water >= 0) waterC(water)
                           else if (!fb.water.isNaN()) fb.water else cur.coolantC,
                // 电量同样是百分比：0xFF(255) / 越界值不能显示成「255%」
                battPct = when {
                    batt >= 0 -> batt
                    battPlat in 0..100 -> battPlat
                    else -> cur.battPct
                },
                fuelLiters = if (fuel >= 0) fuel.toFloat()
                             else if (fb.fuel >= 0f) fb.fuel else cur.fuelLiters,
                fuelPct = if (fuel >= 0) fuelPctOf(fuel)
                          else if (fb.fuel >= 0f) fuelPctOf(fb.fuel.roundToInt()) else cur.fuelPct,
                gear = if (gear in 0..6) gearChar(gear) else cur.gear,
                atfC = if (atf >= 0) atfC(atf) else cur.atfC,
                volt = when {
                    volt > 0 -> volt / 100f
                    else -> {
                        // 平台回退值也按 8~20V 兜一层：别的车型可能推 65535 之类哨兵
                        val pv = when {
                            voltPlat == null -> Float.NaN
                            voltPlat > 100f -> voltPlat / 1000f
                            else -> voltPlat
                        }
                        if (pv in 8f..20f) pv else cur.volt
                    }
                },
                sulfurPct = if (sulfur >= 0) sulfur else cur.sulfurPct,
                engineOn = if (engine >= 0) engine > 0 else cur.engineOn,
                powerMode = platInt("car.basic.power_mode").let { p -> if (p >= 0) p else cur.powerMode },
                night = if (head >= 0) head else cur.night,
                tireKpa = if (tireKpa.any { it > 0 }) tireKpa.toList() else cur.tireKpa,
                tireTempC = if (tireTemp.isNotEmpty()) tireTemp else cur.tireTempC,
                doors = listOf(dFl, dFr, dRl, dRr, dTr).map { if (it < 0) -1 else if (it > 0) 1 else 0 },
                hvacRecirc = if (recirc >= 0) recirc else cur.hvacRecirc,
                doorLock = if (lock >= 0) lock else cur.doorLock,
                // 读不到门窗（平台服务偶发超时）就保留上一轮，别让车模闪一下"全关"
                windowPos = if (window != null) window.take(4) else cur.windowPos,
                sunroof = if (window != null) window[4] else cur.sunroof,
                driveMode = if (mode >= 0) mode else cur.driveMode,
                signalLevel = if (sig >= 0) sig.coerceIn(0, 4) else cur.signalLevel,
                source = if (fb.origin.isNotEmpty()) "binder+carapi" else "binder"
            ))
        }
    }

    // ------------------------------------------------------------ 车窗 / 天窗

    /**
     * 读四门 + 天窗位置（0~100，-1 未知），顺序：左前 / 右前 / 左后 / 右后 / 天窗。
     *
     * 一条都读不到时返回 null，调用方保留上一轮的值 —— 平台服务偶发超时不该
     * 让车模闪一下"四窗全关"。天窗读不到时退一步用 `window_pos_status` 的第 5 位
     * （旧版 5.97.22 的兜底，本车第 5 位恒为 1，靠 UI 侧阈值挡在"关"这一侧）。
     */
    private fun readWindow(): IntArray? {
        val win = parseWinArray(platGet(K_WINDOW_POS))
        var roof = parseWinOne(readRoofRaw())
        if (roof < 0 && win != null && win.size > 4) roof = win[4]
        if (win == null && roof < 0) return null
        val out = IntArray(5) { -1 }
        if (win != null) for (i in 0 until 4) out[i] = win.getOrElse(i) { -1 }
        out[4] = roof
        return out
    }

    /**
     * 天窗原始值：`window.sunroofKey` 配了就直接读它，否则按 [ROOF_KEYS] 探测。
     * 探测结果缓存 60 秒；缓存键读空了立刻重探（平台服务晚启动的情况）。
     */
    private fun readRoofRaw(): String {
        val forced = sunroofKeyOverride?.trim().orEmpty()
        if (forced.isNotEmpty()) return platGet(forced)

        val now = SystemClock.elapsedRealtime()
        val cached = roofKey
        if (cached != null && now - roofKeyAt < 60_000L) {
            val v = platGet(cached)
            if (useful(v)) return v
        }
        for (k in ROOF_KEYS) {
            val v = platGet(k)
            if (useful(v)) {
                roofKey = k
                roofKeyAt = now
                return v
            }
        }
        roofKey = null
        roofKeyAt = now
        return ""
    }

    private fun useful(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        return s.isNotEmpty() && !s.equals("null", true) && s != "-1"
    }

    /**
     * 解析 `[12,0,0,8]` / `{..}` / 带引号 / 竖线分隔 / 单个大整数（4 字节小端拆）。
     * 超出 0~100 的值（255 / 65535 这类无效标记）保留 -1，交给 UI 画成"未知"。
     */
    private fun parseWinArray(raw: String?): IntArray? {
        if (!useful(raw)) return null
        val s = raw!!.trim()
            .replace("[", "").replace("]", "")
            .replace("{", "").replace("}", "")
            .replace("\"", "").replace("'", "")
        var parts = s.split(',')
        if (parts.size < 4) {
            val one = s.trim()
            val hex = one.startsWith("0x", true)
            val packed = try {
                if (hex) java.lang.Long.parseLong(one.substring(2), 16) else one.toLong()
            } catch (t: Throwable) {
                -1L
            }
            if ((hex && packed >= 0L) || (!hex && packed > 100L)) {
                return IntArray(5) { i ->
                    val b = ((packed shr (8 * i)) and 0xFFL).toInt()
                    if (b > 100) -1 else b
                }
            }
            parts = s.split(Regex("[^0-9\\-]+"))
        }
        val tmp = IntArray(8)
        var got = 0
        for (p in parts) {
            val v = p.trim()
            if (v.isEmpty()) continue
            val n = v.toIntOrNull() ?: return null
            if (got >= tmp.size) break
            tmp[got++] = if (n < 0 || n > 100) -1 else n
        }
        if (got < 4) return null
        return IntArray(if (got >= 5) 5 else 4) { tmp[it] }
    }

    /** 单个位置值：0~100 直接取，其它一律 -1。 */
    private fun parseWinOne(raw: String?): Int {
        if (!useful(raw)) return -1
        val digits = raw!!.replace(Regex("[^0-9]"), "")
        if (digits.isEmpty()) return -1
        val v = digits.toIntOrNull() ?: return -1
        return if (v > 100) -1 else v
    }

    // ------------------------------------------------------------ 6.0.31 车型兼容：无效值清洗

    /**
     * ★ 其他车型 CAN 信号的「Invalid」标记（2026-11-12 车机截图实证）。
     *
     * `getItemValues` 回的是字节数组，`joinBytes` 按大端拼成一个 int：
     * 单字节全 FF → 255、双字节全 FF → 65535、三/四字节还可能更大。
     * 其它车型在没有这个信号 / 信号未就绪时会直接推这种标记，于是状态栏出现
     * 「续航 65535km」「油量 255L」；水温更隐蔽 —— 单字节 0x7F(127) 经
     * [waterC] 正好换算成 47℃，看着像正常冷车水温，其实没有数据。
     *
     * 旧版只在 `onPush` 挡了 0xFF 和百分比越界，`refresh` 对续航/油量/水温/
     * 电压/里程一律 `>= 0` 就上屏。这里按「宽哨兵 + 单字节哨兵 + 各字段物理量程」
     * 统一收口，两条读数路径共用；查不到的车型宁可显示「--」，也不显示离谱数字。
     */
    private const val RANGE_MAX_KM = 2000        // 乘用车满油/满电续航上限
    private const val FUEL_MAX_L = 150           // 油箱容积上限（本车 55L）
    private const val MILEAGE_MAX_KM = 999999
    private const val VOLT_MAX_RAW = 2000        // 单位 0.01V → 20.00V
    /**
     * 变速箱油温原始值量程：`°C = raw - 40` → raw 0..240 对应 -40..200℃。
     * 见 [atfC] 的标定说明（以前直接拿 raw 当摄氏度，状态栏显示 74℃、原厂页 34℃）。
     */
    private const val ATF_RAW_MIN = 0
    private const val ATF_RAW_MAX = 240
    private const val WATER_MIN_C = -40
    private const val WATER_MAX_C = 130

    /** 多字节「全 FF / 满量程」哨兵：任何字段出现都不可能是真实值。 */
    private fun isWideSentinel(v: Int): Boolean = when (v) {
        0x7FFF, 0xFFFE, 0xFFFF, 0x7FFFFF, 0xFFFFFF, 0x7FFFFFFF -> true
        else -> false
    }

    /** 单字节 0xFE / 0xFF：原厂工程模式显示 Invalid（项目既定约定）。 */
    private fun isByteSentinel(v: Int): Boolean = v == 0xFE || v == 0xFF

    /**
     * 把车控原始值洗成「有效值 / -1(无效)」。
     * 里程是例外：255km 是合法新车里程，不能按单字节哨兵误杀，只挡宽哨兵。
     */
    private fun cleanInt(item: Int, raw: Int): Int {
        if (raw < 0 || isWideSentinel(raw)) return -1
        return when (item) {
            IT_MILEAGE -> if (raw in 0..MILEAGE_MAX_KM) raw else -1
            IT_RANGE -> if (!isByteSentinel(raw) && raw in 0..RANGE_MAX_KM) raw else -1
            IT_FUEL -> if (!isByteSentinel(raw) && raw in 0..FUEL_MAX_L) raw else -1
            IT_VOLT -> if (!isByteSentinel(raw) && raw in 1..VOLT_MAX_RAW) raw else -1
            IT_ATF -> if (!isByteSentinel(raw) && raw in ATF_RAW_MIN..ATF_RAW_MAX) raw else -1
            IT_BATT, IT_SULFUR -> if (raw in 0..100) raw else -1
            IT_WATER -> if (!isByteSentinel(raw) && raw != 0x7F &&
                waterC(raw).toInt() in WATER_MIN_C..WATER_MAX_C) raw else -1
            // 门 / 大灯 / 挡位 / 发动机 / 驾驶模式：沿用 0xFF=Invalid 的既定约定
            else -> if (!isByteSentinel(raw)) raw else -1
        }
    }

    /** 水温：`round(raw * 0.75 - 48)`，原版 waterScale/waterOffset。 */
    private fun waterC(raw: Int): Float = (raw * 0.75f - 48f).roundToInt().toFloat()

    /**
     * 变速箱油温：`°C = raw - 40`。
     *
     * ★ 6.0.105 实车标定：原厂「车辆状态」页同一时刻显示
     *   冷却液 86.2℃ / **变速箱油温 34.0℃**，而桌面状态栏读同一个
     *   `BASIC_TRANSMISSION_OILTEMP(0x1D)` 显示 74℃ —— 74 - 40 = 34，正好对上。
     *   CAN 上温度信号普遍是「+40 偏移」编码（0 表示 -40℃，240 表示 200℃），
     *   以前把 raw 直接当摄氏度上屏，所以一直偏高 40 度。
     */
    private fun atfC(raw: Int): Float = (raw - 40).toFloat()

    /** 挡位：0=P 1=N 2=R 3=D（原版 GEAR_CHARS = {"P","N","R","D"}）。 */
    private fun gearChar(v: Int): String = when (v) {
        0 -> "P"
        1 -> "N"
        2 -> "R"
        3 -> "D"
        4 -> "S"
        5 -> "M"
        6 -> "L"
        else -> "?"
    }

    /** 油箱按 55L 折算百分比（与 mock 分支同口径）。 */
    private fun fuelPctOf(liters: Int): Int =
        ((liters.coerceIn(0, 60) * 100f) / 55f).roundToInt().coerceIn(0, 100)

    /** 车速：`sys.vehicle.hardware.carspeed` 单位 m/s，有效区间 0..120。 */
    private fun readSpeedKmh(): Int {
        val m = sysGet ?: return -1
        val raw = try {
            m.invoke(null, K_SPEED, "") as? String
        } catch (t: Throwable) {
            return -1
        } ?: return -1
        val mps = raw.trim().toFloatOrNull() ?: return -1
        if (mps < 0f || mps > 120f) return -1
        return (mps * 3.6f).roundToInt().coerceIn(0, 300)
    }

    // ------------------------------------------------------------------ 控制

    /**
     * 下发一组 Item 值。返回是否成功写入 Binder（不代表车控已执行）。
     */
    fun sendItemValues(funcIndex: Int, cmdId: Int, values: IntArray): Boolean {
        val b = icar ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ICAR_DESC)
            data.writeInt(funcIndex)
            data.writeInt(cmdId)
            data.writeIntArray(values)
            b.transact(TX_SEND, data, null, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "sendItemValues(0x${cmdId.toString(16)}) 失败", t)
            false
        } finally {
            data.recycle()
        }
    }

    /**
     * 原厂背光协议：level = 0 息屏，1..10 亮度。
     *
     * 与旧版 NightScreen 完全一致，用于"只在桌面前台时自动息屏"。
     */
    fun setBacklight(level: Int, autoMode: Int = 0, dayNight: Int = 1): Boolean {
        val v = level.coerceIn(0, 10)
        return sendItemValues(FX_BACKLIGHT, CMD_BACKLIGHT, intArrayOf(autoMode, dayNight, v, v))
    }

    /** 车控写入通道是否可用（ICarInfo binder 已连上）。给夜间息屏等控制类功能做状态提示。 */
    fun canControl(): Boolean = icar != null

    // ---------------------------------------------------------------- 控制：空调（HVAC）

    /**
     * 读空调模块的一个 item。null = 通道没连上或这一项读不到（别用 -1 当哨兵）。
     */
    fun hvacRead(item: Int): Int? {
        if (icar == null) return null
        val v = readItem(FX_HVAC, item)
        return if (v < 0) null else v
    }

    /** 内循环当前状态：1 内 / 0 外 / 其它（含 null）未知。 */
    fun recircState(): Int? = hvacRead(IT_HVAC_RECIRC_READ)?.takeIf { it == 0 || it == 1 }

    /**
     * 请求切内 / 外循环。**value 是发到车机上的原值**（1 / 0），
     * 值域是否反相由调用方（[Hvac]）按设置页开关决定，这里不做语义解释。
     *
     * @param viaVr true 时改走 VR（语音助手）那条请求 item —— 直接写 `HvacID$Send` 的
     *   车辆若被 MCU 忽略，这条通常还有效；两条都试完由调用方回读裁决。
     * @return 只代表写进了 Binder，**不代表车控执行**（见 [setCarSetting] 的说明）。
     */
    fun setRecirc(value: Int, viaVr: Boolean = false): Boolean =
        setRecircItem(if (viaVr) IT_HVAC_VR_RECIRC else IT_HVAC_RECIRC_SEND, value)

    /**
     * 按 item 编号直接写循环请求（6.0.104 空调自检用）。
     *
     * `0x09` = 直连请求，`0x66` = 语音助手那条请求。实车上「哪条被 MCU 接受」
     * 因平台版本而异，自检就是逐条试出来的。
     */
    fun setRecircItem(item: Int, value: Int): Boolean =
        sendItemValues(FX_HVAC, item, intArrayOf(if (value != 0) 1 else 0))

    /** 空调电源开关读数（HvacID 0x12）。null = 读不到。 */
    fun hvacPower(): Int? = hvacRead(IT_HVAC_POWER_READ)

    /** 制冷（A/C）开关读数（HvacID 0x01）。null = 读不到。 */
    fun hvacAc(): Int? = hvacRead(IT_HVAC_AC_READ)

    /** 空调自检串：设置页与导出日志共用，出问题一张图说清。 */
    fun hvacDebug(): String {
        if (icar == null) return "车控通道未连"
        val p = hvacPower()?.toString() ?: "读不到"
        val a = hvacAc()?.toString() ?: "读不到"
        val r = recircState()?.toString() ?: "读不到"
        return "电源 $p · 制冷 $a · 循环 $r（0/1 原值）"
    }

    // ---------------------------------------------------------------- 控制：车窗 / 天窗

    /**
     * 车门锁原始值，null = 通道没连上或这一项读不到。
     *
     * 值域在不同平台版本上不一致（常见 0 未锁 / 1 已锁，也有 2 = 已锁），
     * **这里不做语义解释**，交给 [AutoClose] 按设置页的「反相」开关裁决。
     */
    fun lockRaw(): Int? {
        if (icar == null) return null
        val v = readItem(FX_READONLY, IT_LOCK_STATUS)
        return if (v < 0) null else v
    }

    /**
     * 请求关闭车窗 / 天窗。
     *
     * `CockpitID` 是**语音助手用的请求条目**，没有「执行结果」的返回值，
     * 所以返回值只代表写进了 Binder。是否真的关上，由 [AutoClose] 读
     * `window_pos_status` 开度裁决。
     *
     * ★ 6.0.107 实车纠正：@param value 是**功能点编号**，不是 0/1 开关。
     *   车窗条目实测 `0 = 四窗全降`、`1 = 主驾窗降`，两个都是「往下走」。
     *   所以「关窗」得先标定出这张编号表里代表「上升」的那个值
     *   （见 [AutoClose.calibrate]，候选表从 5 / 16 试起）。
     */
    fun closeWindows(windows: Boolean, roof: Boolean, value: Int): Boolean {
        var sent = false
        if (windows) {
            sent = sendItemValues(FX_SEAT_WINDOW, IT_WIN_VR, intArrayOf(value)) || sent
        }
        if (roof) {
            sent = sendItemValues(FX_SEAT_WINDOW, IT_SKY_VR, intArrayOf(value)) || sent
        }
        return sent
    }

    /** 锁车诊断串：设置页与导出日志都用它。 */
    fun lockDebug(): String {
        val v = lockRaw() ?: return "未读到（通道未连或该车型无此项）"
        val bits = buildString {
            append("原始值 ").append(v)
            append(" · bit0=").append(v and 1)
            if (v > 1) append("（该项不是简单 0/1，可能带多路门锁位）")
        }
        return bits
    }


    // ---------------------------------------------------------------- 控制：车设项读写

    /**
     * 读一个车设项。返回 **null** 表示通道没连上或这一项读不到。
     *
     * 这里不再用 -1 当"没读到"的哨兵 —— 6.0.60 的水温 -1℃ 就是这么来的：
     * 真实值域里出现 -1 时，哨兵和读数无法区分。宁可返回 null 让调用方显示 `--`。
     */
    fun carSetting(item: Int): Int? {
        if (icar == null) return null
        val v = readItem(FX_CARSETTINGS, item)
        return if (v < 0) null else v
    }

    /**
     * 写一个车设项（单值）。返回 true 只代表**写进了 Binder**，不代表车控真的执行了。
     *
     * MCU 对每项都有自己的值域，越界时常见两种处理：静默忽略，或者钳到边界值。
     * 所以调用方必须回读确认，别拿返回值当结果。
     */
    fun setCarSetting(item: Int, value: Int): Boolean =
        sendItemValues(FX_CARSETTINGS, item, intArrayOf(value))

    /** 伴我回家延时当前值（秒）；null = 读不到，0 = 已关闭。 */
    fun followHomeDelay(): Int? = carSetting(IT_FOLLOW_HOME)

    /** 顶灯延时当前值（秒）；null = 读不到。 */
    fun roofLightDelay(): Int? = carSetting(IT_ROOF_DELAY)

    /**
     * 把伴我回家延时写成 [sec]（0 = 关闭），写完等 [settleMs] 毫秒再回读，返回**实际生效值**。
     *
     * 为什么不是直接返回 Boolean：原厂只给 30 秒起的档位，写 0 能不能生效取决于 MCU。
     * 回读才是唯一可信的答案 —— 返回 null 表示写不进去或读不回来，调用方要如实告诉用户。
     */
    fun setFollowHomeDelay(sec: Int, settleMs: Long = 400L): Int? {
        if (!setCarSetting(IT_FOLLOW_HOME, sec.coerceIn(0, 255))) return null
        try {
            Thread.sleep(settleMs)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return followHomeDelay()
    }

    /**
     * 请求一帧电瓶硫化率。
     *
     * `sendValues` 是直通 CarVendorExtension 的原始帧通道（transact 7），
     * 帧格式 `[0x7523, 0xFE, cmdId, 0]`，与工程模式 `setGlobalProperty` 同构。
     * 硫化率不主动推，必须周期性请求。
     */
    fun requestSulfur(): Boolean {
        val b = icar ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ICAR_DESC)
            data.writeInt(PROP_CAN_RAW)
            data.writeIntArray(intArrayOf(FRAME_HEAD, MODULE_READONLY_ID, IT_SULFUR, 0))
            b.transact(TX_SEND_FRAME, data, null, 1)
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
        }
    }

    private fun descriptor(b: IBinder?): String = try {
        b?.interfaceDescriptor ?: "null"
    } catch (t: Throwable) {
        "?"
    }
}
