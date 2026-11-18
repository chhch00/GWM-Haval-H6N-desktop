package com.h6n.launcher.core

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log

/**
 * 原厂天气：直接读**原车状态栏用的那一份**数据。
 *
 * ## 数据从哪来（从 system.img 实证，不是猜的）
 * 系统里有个 `OperatorCenterService`（包名 `com.beantechs.operatorcenterservice`），
 * 它对外暴露了一个 ContentProvider：
 *
 * ```
 * content://com.beantechs.operatorcenterservice/weather
 * ```
 *
 * 原厂 SystemUI 的状态栏天气（布局里的 `status_bar_weather_info_text` /
 * `status_bar_weather_temp_text`）就是由 `com.bean.external.BeanWeatherManager`
 * 从这里查出来再显示的，所以读它等于"和原车显示完全一致"。
 *
 * 服务端 manifest 里这个 provider 是 `exported=true` 且**没有** readPermission，
 * 第三方应用可以直接查，不需要 root、不需要签名。
 *
 * ## 表结构（provider 自己的建表语句）
 * ```
 * create table weather (
 *   _id integer primary key autoincrement,
 *   district text, curCondition text, curConditionCode INTEGER,
 *   curTemp INTEGER default 0, maxTemp INTEGER default 0, minTemp INTEGER default 0,
 *   pm25Level text, pm25Value INTEGER, tempUnit text, lastUpdateTime text,
 *   windDirection text, windForce INTEGER, curHumidity INTEGER,
 *   airQulityLevel text, dressIndex INTEGER, carWashIndex INTEGER
 * )
 * ```
 *
 * ## 读法
 * 照抄 `BeanWeatherManager.updateWeatherInfoFromDatabase()`：
 * `query(uri, null, null, null, null)` → 取最后一行 → 按列名取。
 * SystemUI 拿 `curTemp` 时是 `getString` 后**直接 setText**（单位是布局里的固定
 * 「℃」），所以这里的数值就是摄氏度，不需要再换单位。
 */
object FactoryWeather {

    private const val TAG = "H6FactoryWeather"

    const val AUTHORITY = "com.beantechs.operatorcenterservice"
    val URI: Uri = Uri.parse("content://$AUTHORITY/weather")

    private const val C_DISTRICT = "district"
    private const val C_COND = "curCondition"
    private const val C_CODE = "curConditionCode"
    private const val C_TEMP = "curTemp"
    private const val C_UNIT = "tempUnit"
    private const val C_UPDATE = "lastUpdateTime"
    private const val C_PM25 = "pm25Value"

    /**
     * @param city    区县名（原厂状态栏不显示，备查）
     * @param state   中文天气，如「多云」「小雨」—— 直接喂给 StatusBarView.iconFor
     * @param code    原厂天气码（字符串形式，可能为空）
     * @param tempC   摄氏度；列缺失/为空时为 null
     * @param at      原厂自己的更新时间（毫秒，未知为 0）
     */
    data class Snap(
        val city: String,
        val state: String,
        val code: Int,
        val tempC: Int?,
        val unit: String,
        val at: Long,
        val pm25: Int
    )

    /**
     * 查一次原厂天气。取不到（没有这个 provider / provider 关着 / 表是空的）返回 null，
     * 调用方自行决定是否回退到在线天气。
     */
    fun read(ctx: Context): Snap? {
        val cursor = try {
            ctx.contentResolver.query(URI, null, null, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "查询原厂天气失败: ${t.javaClass.simpleName} ${t.message}")
            null
        } ?: run {
            Log.i(TAG, "没有原厂天气 provider（这台车不是这套系统？）")
            return null
        }

        return cursor.use { c ->
            // 正常只有一行；万一有多行（多城市缓存），取最后写入的那条
            if (!c.moveToLast()) return@use null
            val state = str(c, C_COND)
            if (state.isEmpty()) return@use null
            Snap(
                city = str(c, C_DISTRICT),
                state = state,
                code = num(c, C_CODE) ?: 0,
                tempC = num(c, C_TEMP),
                unit = str(c, C_UNIT),
                at = str(c, C_UPDATE).toLongOrNull() ?: 0L,
                pm25 = num(c, C_PM25) ?: 0
            )
        }
    }

    /**
     * 订阅原厂天气变化。原厂服务写库后会通知 observer，我们据此立刻刷新状态栏，
     * 不用等下一轮轮询 —— 这也让"车机天气变了"和"桌面天气变了"几乎同时。
     */
    fun observe(ctx: Context, onChange: () -> Unit): ContentObserver? = runCatching {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        ctx.contentResolver.registerContentObserver(URI, true, o)
        o
    }.onFailure { Log.w(TAG, "注册原厂天气观察者失败: ${it.message}") }.getOrNull()

    fun unobserve(ctx: Context, o: ContentObserver?) {
        if (o == null) return
        runCatching { ctx.contentResolver.unregisterContentObserver(o) }
    }

    // ---------------- 取值小工具（列可能不存在，一律容错） ----------------

    private fun str(c: android.database.Cursor, name: String): String {
        val i = c.getColumnIndex(name)
        if (i < 0 || c.isNull(i)) return ""
        return runCatching { c.getString(i) }.getOrDefault("").trim()
    }

    private fun num(c: android.database.Cursor, name: String): Int? {
        val i = c.getColumnIndex(name)
        if (i < 0 || c.isNull(i)) return null
        return runCatching { c.getInt(i) }
            .getOrElse { c.getString(i).toFloatOrNull()?.toInt() }
    }
}
