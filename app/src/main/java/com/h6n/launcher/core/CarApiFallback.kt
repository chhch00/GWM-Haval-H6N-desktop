package com.h6n.launcher.core

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.h6n.launcher.core.H6Log as Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

/**
 * Car API 兜底源（v6.0.31，2026-11-12）。
 *
 * ## 为什么要这个东西
 * 其它车型的 ICarInfo 对 `0x06 续航 / 0x29 油量 / 0x08 水温` 会直接推 Invalid 标记
 * （0xFFFF / 0xFF / 0x7F），[CarBinderSource.cleanInt] 把它们过滤后状态栏就是「--」。
 * 但车机上还有两套独立的 Car API 能读到同类信号，这里做成「只补缺口」的兜底：
 *
 * | 层 | 通道 | 依据 |
 * |---|---|---|
 * | L2 | PlatformAdapter `car.*` 候选键 | [CarBinderSource] 已有通道，键名按车型探测 |
 * | L3 | 车厂 SPI `ICarSensorService` | `SVMapService` 的 `ICarSensorService$Stub$Proxy` 反编译：事务码 2=`getProperty(id,zone)`、4=`getContinueVoyage()`、5=`getEngineOilState()`、6=`getEngineCoolingFluidState()`；SPI ID 3/4/5 = 续航 / 油量 / 冷却液 |
 * | L4 | 标准 `android.car.Car` → `CarSensorManager` | `SENSOR_TYPE_FUEL_LEVEL=5` 的 `floatValues[0]`=油量百分比、`[1]`=续航距离(km)；`SENSOR_TYPE_ODOMETER=4` 的 `floatValues[0]`=km（`car_api_scan.txt` 常量表 + 老版 AmapDrBridge 的实装） |
 *
 * ## 三条硬规矩
 * 1. **全部反射 / 全程 runCatching**：本机没有 `android.car` 编译期依赖，类不存在、
 *    权限不足、服务未起来都只降级，绝不影响桌面启动。
 * 2. **只补缺口**：ICarInfo 读到有效值时一次都不调这些通道。
 * 3. **一律过量程校验**：Car API 的单位在不同车型上可能不同（尤其 SPI），
 *    落不进物理量区间的原始值只进诊断、不上屏；诊断行显示原始值供车机校准。
 */
internal object CarApiFallback {

    private const val TAG = "H6CarApi"

    // ------------------------------------------------------------------ 车厂 SPI

    private const val SPI_DESC = "com.desay_svautomotive.caninfoservice.ICarSensorService"
    private const val TX_GET_PROPERTY = 2       // getProperty(int id, int zone) -> int
    private const val TX_CONTINUE_VOYAGE = 4    // getContinueVoyage() -> int
    private const val TX_ENGINE_OIL = 5         // getEngineOilState() -> int
    private const val TX_COOLING_FLUID = 6      // getEngineCoolingFluidState() -> int

    private const val ID_CONTINUE_VOYAGE = 3
    private const val ID_OIL_STATE = 4
    private const val ID_ENGINE_COOLING_FLUID = 5

    /** 由 [CarBinderSource.bindTire] 连接成功时注入，复用同一个 binder，不重复 bind。 */
    @Volatile var spiBinder: IBinder? = null

    // ------------------------------------------------------------------ 标准 android.car

    private const val SENSOR_ODOMETER = 4
    private const val SENSOR_FUEL_LEVEL = 5
    private const val RATE_NORMAL = 3           // 固件里是枚举不是 Hz：0 FASTEST / 1 FAST / 2 UI / 3 NORMAL

    @Volatile private var carObj: Any? = null
    @Volatile private var sensorMgr: Any? = null
    @Volatile private var listener: Any? = null
    @Volatile private var carTriedAt = 0L
    @Volatile private var carState = "未初始化"
    @Volatile private var registered = false

    /** 一次兜底读取的结果；null = 这条通道没读到可信值。 */
    data class Reading(
        val rangeKm: Int? = null,
        val fuelLiters: Float? = null,
        val coolantC: Float? = null,
        val mileageKm: Int? = null,
        val origin: String = ""
    )

    // ------------------------------------------------------------------ 诊断

    @Volatile private var spiDebug = "未绑定"
    @Volatile private var carDebug = "未初始化"
    @Volatile private var lastHit = "无"
    @Volatile private var lastHitAt = 0L

    fun debug(): String {
        val age = if (lastHitAt == 0L) "—"
        else "${((SystemClock.elapsedRealtime() - lastHitAt) / 1000).toInt()}s 前"
        return "SPI: $spiDebug · Car: $carDebug · 最近命中: $lastHit ($age)"
    }

    fun noteHit(src: String) {
        lastHit = src
        lastHitAt = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------------ L3：车厂 SPI

    private fun spiCall(code: Int, vararg args: Int): Int? {
        val b = spiBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SPI_DESC)
            for (a in args) data.writeInt(a)
            if (!b.transact(code, data, reply, 0)) null
            else {
                reply.readException()
                reply.readInt()
            }
        } catch (t: Throwable) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun firstValid(calls: List<() -> Int?>, ok: (Int) -> Boolean): Int? {
        for (call in calls) {
            val v = call() ?: continue
            if (ok(v)) return v
        }
        return null
    }

    fun readSpi(): Reading {
        val b = spiBinder ?: run {
            spiDebug = "服务未绑定"
            return Reading()
        }
        if (!b.isBinderAlive) {
            spiDebug = "binder 已失效"
            return Reading()
        }

        // 优先专用无参方法；不同固件的 AIDL 顺序可能不同，再退到通用 getProperty(id, 0)
        val rangeRaw = firstValid(
            listOf(
                { spiCall(TX_CONTINUE_VOYAGE) },
                { spiCall(TX_GET_PROPERTY, ID_CONTINUE_VOYAGE, 0) }
            )
        ) { it in 10..2000 }                       // 排除 0..9 这类状态枚举

        val oilRaw = firstValid(
            listOf(
                { spiCall(TX_ENGINE_OIL) },
                { spiCall(TX_GET_PROPERTY, ID_OIL_STATE, 0) }
            )
        ) { it in 5..150 }

        val coolRaw = firstValid(
            listOf(
                { spiCall(TX_COOLING_FLUID) },
                { spiCall(TX_GET_PROPERTY, ID_ENGINE_COOLING_FLUID, 0) }
            )
        ) { it in 20..255 }                       // 排除 0..19 状态枚举 / 未就绪

        spiDebug = "原始值 续航=${rangeRaw ?: "—"} 油量=${oilRaw ?: "—"} 水温=${coolRaw ?: "—"}（单位待车机校准）"

        // 油量：0..100 视作百分比按本车 55L 折算；101..150 视作升数
        val liters = when {
            oilRaw == null -> null
            oilRaw in 5..100 -> oilRaw / 100f * 55f
            oilRaw in 101..150 -> oilRaw.toFloat()
            else -> null
        }
        // 冷却液：20..130 直接当 ℃；131..255 按 ICarInfo 同一套 raw×0.75-48 换算
        val water = when {
            coolRaw == null -> null
            coolRaw in 20..130 -> coolRaw.toFloat()
            else -> (coolRaw * 0.75f - 48f).roundToInt().toFloat().takeIf { it in 20f..130f }
        }
        return Reading(
            rangeKm = rangeRaw,
            fuelLiters = liters,
            coolantC = water,
            origin = "SPI"
        )
    }

    // ------------------------------------------------------------------ L4：标准 android.car

    /** 连接只做一次；服务晚启动则 60s 后允许重试。 */
    private fun ensureCar(app: Context): Boolean {
        sensorMgr?.let { return true }
        val now = SystemClock.elapsedRealtime()
        if (carTriedAt > 0L && now - carTriedAt < 60_000L) return false
        carTriedAt = now
        return try {
            val cls = Class.forName("android.car.Car")
            val c = cls.getMethod("createCar", Context::class.java).invoke(null, app.applicationContext)
            runCatching { cls.getMethod("connect").invoke(c) }
            val svc = runCatching { cls.getField("SENSOR_SERVICE").get(null) as? String }
                .getOrNull() ?: "sensor"
            val mgr = cls.getMethod("getCarManager", String::class.java).invoke(c, svc)
                ?: error("CarSensorManager=null")
            carObj = c
            sensorMgr = mgr
            carState = "已连接"
            carDebug = "已连接 ($svc)"
            ensureRegistered(mgr)
            true
        } catch (t: Throwable) {
            carState = "不可用：${t.javaClass.simpleName}"
            carDebug = carState
            Log.w(TAG, "标准 Car API 不可用，降级", t)
            false
        }
    }

    /**
     * 老版 AmapDrBridge 实证：慢变信号要先 registerListener，服务才会缓存事件，
     * 之后 getLatestSensorEvent 才取得到。监听器用动态代理，避免编译期依赖接口类。
     */
    private fun ensureRegistered(mgr: Any) {
        if (registered) return
        registered = true
        try {
            val reg = mgr.javaClass.methods.firstOrNull {
                it.name == "registerListener" && it.parameterTypes.size == 3 &&
                        it.parameterTypes[1] == java.lang.Integer.TYPE
            } ?: return
            val lisCls = reg.parameterTypes[0]
            val h = InvocationHandler { _, m, args ->
                // 事件由 getLatestSensorEvent 主动拉取，这里只需保持监听有效
                if (m.name == "onSensorChanged" && args != null && args.isNotEmpty()) Unit
                null
            }
            listener = Proxy.newProxyInstance(lisCls.classLoader, arrayOf<Class<*>>(lisCls), h)
            for (t in intArrayOf(SENSOR_FUEL_LEVEL, SENSOR_ODOMETER)) {
                runCatching { reg.invoke(mgr, listener, t, RATE_NORMAL) }
            }
            carDebug = "已连接 · 已注册 5/4"
        } catch (t: Throwable) {
            Log.w(TAG, "CarSensorManager 注册失败", t)
        }
    }

    private fun latest(type: Int): Any? {
        val mgr = sensorMgr ?: return null
        return try {
            mgr.javaClass.getMethod("getLatestSensorEvent", java.lang.Integer.TYPE)
                .invoke(mgr, type)
        } catch (t: Throwable) {
            null
        }
    }

    private fun floats(ev: Any?): FloatArray? {
        ev ?: return null
        return try {
            val f = try {
                ev.javaClass.getField("floatValues")
            } catch (t: Throwable) {
                ev.javaClass.getDeclaredField("floatValues").also { it.isAccessible = true }
            }
            f.get(ev) as? FloatArray
        } catch (t: Throwable) {
            null
        }
    }

    fun readStandardCar(app: Context): Reading {
        if (!ensureCar(app)) return Reading()
        val fuel = floats(latest(SENSOR_FUEL_LEVEL))
        val odo = floats(latest(SENSOR_ODOMETER))
        val pct = fuel?.getOrNull(0)?.takeIf { it in 0f..100f }
        val range = fuel?.getOrNull(1)?.takeIf { it in 1f..2000f }?.roundToInt()
        val mileage = odo?.getOrNull(0)?.takeIf { it in 0f..999999f }?.roundToInt()
        carDebug = "已连接 · 油量=${pct ?: "—"}% 续航=${range ?: "—"}km 里程=${mileage ?: "—"}km"
        return Reading(
            rangeKm = range,
            fuelLiters = pct?.let { it / 100f * 55f },
            mileageKm = mileage,
            origin = "Car"
        )
    }
}
