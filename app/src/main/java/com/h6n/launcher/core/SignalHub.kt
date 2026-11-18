package com.h6n.launcher.core

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一帧车身/行驶信号。字段全部给默认值，读不到就是默认值，UI 自行决定显示占位。
 *
 * 保留为不可变 data class：UI 侧只需要比较新旧两份就能判断该刷新哪块，
 * 不用为每个字段各挂一个监听器。
 */
data class CarSignal(
    /** 车速 km/h —— SystemProperties `sys.vehicle.hardware.carspeed`（m/s ×3.6） */
    val speedKmh: Int = 0,
    /** 本次行程均速 km/h，-1 表示未知 */
    val avgSpeed: Int = -1,
    /** 本次行程最高车速 km/h，-1 表示未知 */
    val maxSpeed: Int = -1,
    /** 挡位：P/R/N/D/S/M/L；未知为 "?" —— ICarInfo fx=0x15 item=0x1A */
    val gear: String = "?",
    /** 驾驶模式：1 标准 / 2 经济 / 3 运动（4 雪地 / 5 沙地 / 6 泥地 / 7 自动 / 8 纯电 / 9 节能 / 10 四驱），-1 未知 —— 原厂 BeanDriveMode 值域 */
    val driveMode: Int = -1,
    /** 冷却液温度 ℃ —— ICarInfo 0x08，raw×0.75-48 */
    val coolantC: Float = Float.NaN,
    /** 变速箱油温 ℃ —— ICarInfo 0x1D，原值 */
    val atfC: Float = Float.NaN,
    /** 四轮胎压 kPa（左前/右前/左后/右后）—— CarSensor transact(11) prop=2，raw×1.375 */
    val tireKpa: List<Int> = emptyList(),
    /** 四轮温度 ℃ —— 真实通道无此 item，恒为空（原版也只有 mock） */
    val tireTempC: List<Int> = emptyList(),
    /** 续航 km —— ICarInfo 0x06，-1 未知 */
    val rangeKm: Int = -1,
    /** 油量百分比 —— ICarInfo 0x29 按 55L 折算，-1 未知 */
    val fuelPct: Int = -1,
    /** 油量升数 —— ICarInfo 0x29，-1 未知 */
    val fuelLiters: Float = -1f,
    /** 电瓶电压 V —— ICarInfo 0x1E /100（回退 PlatformAdapter `car.basic.battery_voltage`） */
    val volt: Float = Float.NaN,
    /** 电瓶电量百分比 —— ICarInfo 0x0D（回退 PlatformAdapter `car.basic.battery_power_level`） */
    val battPct: Int = -1,
    /** 电瓶硫化率百分比 —— ICarInfo 0x1B，不是广播量，需周期性发 CAN 请求帧 */
    val sulfurPct: Int = -1,
    /** 总里程 km —— ICarInfo 0x00，-1 未知 */
    val mileageKm: Int = -1,
    /** 机油寿命百分比 —— 本地保养计划估算，不走 Binder */
    val oilLifePct: Int = -1,
    /** 距下次保养剩余 km —— 本地保养计划估算，不走 Binder */
    val maintKmLeft: Int = -1,
    /** 车窗位置 4 路（左前/右前/左后/右后）0~100，-1 未知 —— 平台属性 `car.basic.window_pos_status` */
    val windowPos: List<Int> = emptyList(),
    /** 天窗开度 0~100，-1 未知 —— 平台属性 `car.basic.sunroof_pos_status`（键名按候选表探测） */
    val sunroof: Int = -1,
    /**
     * 车门锁原始值，-1 未知 —— ICarInfo `fx=0x15 item=0x4C DOOR_LOCK_STATUS`。
     *
     * 保留原值不归一化：值域各车型不一致（常见 0 未锁 / 1 已锁，也有 2 = 已锁），
     * 「熄火自动关窗」按设置页的「锁车读数反相」开关解释它，见 [AutoClose]。
     */
    val doorLock: Int = -1,
    /**
     * 空调内循环：1 内循环 / 0 外循环 / -1 未知。
     * 来源 ICarInfo `fx=0x07 item=0x08`（模块 ID_HVAC，见《H6N-ICarInfo信号总表》）。
     * 桌面那枚空调图标、「空调内外循环」动作都读它判断当前该往哪边切。
     */
    val hvacRecirc: Int = -1,
    /** 车门 5 路（左前/右前/左后/右后/后备箱），1 开 0 关 -1 未知 —— ICarInfo 0x10..0x14 */
    val doors: List<Int> = emptyList(),
    /** 蓝牙是否已连接 */
    val btConnected: Boolean = false,
    /** 热点是否开启 */
    val hotspotOn: Boolean = false,
    /** 4G 信号等级 0..4 —— PlatformAdapter `sys.network.mobile_network_signal_level` */
    val signalLevel: Int = 0,
    /** WiFi 信号等级 0..4 */
    val wifiLevel: Int = 0,
    /** 发动机是否运转 —— ICarInfo 0x1C > 0 */
    val engineOn: Boolean = false,
    /**
     * 整车电源模式：0 熄火 / 1 ACC / 2 ON / 3 启动中，-1 未知。
     * 平台属性 `car.basic.power_mode`（旧版 SceneEngine 用来判「熄火未关车窗」的边沿）。
     * 读不到时 SceneAnnouncer 会自动退回 [engineOn] 的 true→false 兜底。
     */
    val powerMode: Int = -1,
    /**
     * 昼夜 / 大灯：1 夜间（大灯亮）0 白天 -1 未知。
     *
     * 来源 ICarInfo `fx=0x15 item=0x01 HEAD_LIGHT_STATUS`（信号总表：0 白天 / 1 夜间）。
     * 桌面夜间主题与高德昼夜同步都以它为准，读不到才退回时间判断。
     */
    val night: Int = -1,
    /** 数据来源，仅用于诊断浮层：binder / gps / mock / none */
    val source: String = "none",
    /** 门窗字段是否正被「模拟信号」覆盖（仅诊断显示，真实信号永远优先） */
    val simActive: Boolean = false,
    /** 整车读数（车速/挡位/温度/胎压/油量/里程…）是否也被模拟覆盖 */
    val simAll: Boolean = false
) {
    companion object {
        val EMPTY = CarSignal()
    }
}

/**
 * 车身信号总线。
 *
 * 单一 [StateFlow] 出口，UI 只订阅这一条流；新增信号源（Binder / GPS / 广播）
 * 时不需要改动任何 UI 代码。
 */
object SignalHub {

    private val _state = MutableStateFlow(CarSignal.EMPTY)
    val state: StateFlow<CarSignal> = _state.asStateFlow()

    @Volatile
    private var attached = false

    fun attach(app: Application, scope: CoroutineScope) {
        if (attached) return
        attached = true

        // 车身 Binder 优先：车速走 sys.vehicle.hardware.carspeed（CAN），
        // 挡位/温度/胎压/车门也都走各自 Binder，不依赖卫星。
        // ★ 车速只认车辆数据：以前这里还挂了 GpsSpeedSource 兜底，
        //   结果 Binder 偶尔掉帧时车速会从 CAN 值跳到 GPS 值（或一直 0），
        //   用户看到的就是"桌面车速不对"。已按需求整体去掉 GPS 车速源。
        CarBinderSource.attach(app, scope, _state)
    }

    /** 供调试浮层/日志使用。 */
    fun current(): CarSignal = _state.value

    /** 外部数据源（Binder / GPS / mock）写入一帧。 */
    fun push(s: CarSignal) {
        _state.value = decorate(s)
    }

    /**
     * 局部改一帧。所有写入都过 [decorate]，模拟信号开启时门窗字段会被盖成模拟帧，
     * 关闭时完全放行真实值 —— 车机上不会因为开了调试而丢掉真实门窗状态。
     */
    fun update(block: (CarSignal) -> CarSignal) {
        _state.update { decorate(block(it)) }
    }

    /** 供 CarBinderSource 在直接持有 MutableStateFlow 的分支里复用同一层叠加。 */
    fun decorate(s: CarSignal): CarSignal = SimSignals.decorate(s)
}
