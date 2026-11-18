package com.h6n.launcher.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log
import androidx.core.content.ContextCompat
import com.h6n.launcher.domain.ConfigSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 状态栏天气（照原版 `Weather` 的实现还原，去掉 amap key 依赖）。
 *
 * ## 数据链（原版 refreshFree）
 * ```
 * 缓存(wcode) 先拉一次秒显
 *   ↓ 没有就按顺序找城市
 * 配置 weather.city（城市名或 9 位天气代码）
 *   ↓
 * 配置 fixedLat/fixedLng（固定坐标）
 *   ↓
 * GPS/网络最后已知位置 → 最近区县（assets/cities.json，3384 条）
 *   ↓
 * IP 定位（ip-api.com，免 key）→ 城市名 → cities.json
 *   ↓
 * 上次缓存 / 默认 101090601（廊坊）
 * ```
 *
 * ## 取数
 * `https://d1.weather.com.cn/sk_2d/{code}.html`，必须带 Referer
 * （`http://www.weather.com.cn/weather/{code}.shtml`），否则返回 403。
 * 返回体形如 `var dataSK={"cityname":"廊坊","temp":"11.7","weather":"晴",...}`，
 * 从第一个 `{` 开始就是 JSON —— 原版也是这么切的。
 *
 * 结果落 SharedPreferences `h6_weather`，断网 / 冷启动时先用上次的值，
 * 不会出现"打火后状态栏空一块"。
 */
class WeatherSource(
    private val ctx: Context,
    private val snapshot: () -> ConfigSnapshot?
) {

    data class Info(
        val city: String,
        val state: String,
        val tempC: Int,
        val at: Long
    )

    private val prefs = ctx.getSharedPreferences("h6_weather", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var observer: android.database.ContentObserver? = null

    private val _state = MutableStateFlow<Info?>(null)
    val state: StateFlow<Info?> = _state.asStateFlow()

    /** 城市表：按需加载一次（193KB，解析后常驻）。 */
    private val cities: List<City> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadCities() }

    fun start() {
        if (job != null) return
        // 原厂天气一变就立刻同步：原车状态栏换天气时，我们这边同一秒就跟着换，
        // 不用等 weather.refreshMin 那一轮轮询。
        observer = FactoryWeather.observe(ctx) { refreshNow() }
        job = scope.launch {
            while (isActive) {
                val ok = runCatching { refreshOnce() }
                    .onFailure { Log.w(TAG, "天气刷新失败: $it") }
                    .getOrDefault(false)
                val min = (snapshot()?.int("weather.refreshMin", 20) ?: 20).coerceIn(5, 180)
                // 成功按 weather.refreshMin 轮询；失败 60 秒就重试。
                // 之前失败也要等满 20 分钟，车机刚联网时状态栏会一直挂在「天气 --°」。
                delay(if (ok) min * 60_000L else 60_000L)
            }
        }
    }

    /** 长按天气区 / 进设置改完城市时立即重拉。 */
    fun refreshNow() {
        scope.launch { runCatching { refreshOnce(force = true) } }
    }

    fun stop() {
        FactoryWeather.unobserve(ctx, observer)
        observer = null
        scope.cancel()
    }

    // ---------------- 取数 ----------------

    /** @return 本轮是否拿到了新数据（false 时 start() 会 60 秒后快速重试）。 */
    private suspend fun refreshOnce(force: Boolean = false): Boolean {
        val s = snapshot() ?: return false
        if (!s.bool("weather.enabled", true)) {
            _state.value = null
            return false
        }

        val cached = readCache()
        if (cached != null) _state.value = cached

        // ★ ① 原厂天气优先。
        //   车机自己的天气服务已经把「温度 + 天气」显示在原车状态栏上了，数据就在
        //   `OperatorCenterService` 的 provider 里（见 FactoryWeather）。读它等于
        //   "和原车显示完全一致"，而且不需要 GPS、不需要联网。
        //   取不到（没有这套系统 / 表是空的）才往下走原来的在线链路。
        factoryInfo()?.let { info ->
            if (cached == null || cached.state != info.state ||
                cached.tempC != info.tempC || cached.city != info.city
            ) {
                saveCache(info)
            }
            _state.value = info
            lastSource = SRC_FACTORY
            return true
        }

        val intervalMs = (s.int("weather.refreshMin", 20).coerceIn(5, 180)) * 60_000L
        if (!force && cached != null && System.currentTimeMillis() - cached.at in 0 until intervalMs) {
            return true
        }

        val code = resolveCode(s, cached?.let { prefs.getString(KEY_CODE, null) })
        if (code.isNullOrEmpty()) {
            Log.w(TAG, "天气：没能确定城市代码，沿用缓存")
            return false
        }

        val fresh = fetch(code) ?: return false
        prefs.edit().putString(KEY_CODE, code).apply()
        saveCache(fresh)
        _state.value = fresh
        lastSource = SRC_ONLINE
        return true
    }

    /**
     * 原厂天气 → [Info]。
     *
     * 拿不到数据、天气文案为空、温度缺失（原厂列默认值是 0，缺数据时可能写 0）
     * 都返回 null，让调用方走在线兜底，而不是在状态栏上显示一个假的「0°」。
     */
    private fun factoryInfo(): Info? {
        val f = FactoryWeather.read(ctx) ?: return null
        val t = f.tempC ?: return null
        if (f.state.isEmpty()) return null
        Log.i(TAG, "天气：原厂数据「${f.city} ${f.state} ${t}°」单位=${f.unit} 原厂时间=${f.at}")
        return Info(
            city = f.city,
            state = f.state,
            tempC = t,
            // 采集时间用「现在」：f.at 是原厂自己的同步时间，可能是几小时前，
            // 但它就是车机认为的最新值，缓存有效期理应从这里开始算。
            at = System.currentTimeMillis()
        )
    }

    private fun saveCache(info: Info) {
        prefs.edit()
            .putString(KEY_CITY, info.city)
            .putString(KEY_STATE, info.state)
            .putInt(KEY_TEMP, info.tempC)
            .putLong(KEY_AT, info.at)
            .apply()
    }

    private fun fetch(code: String): Info? {
        val url = "https://d1.weather.com.cn/sk_2d/$code.html"
        val body = http(url, "http://www.weather.com.cn/weather/$code.shtml") ?: return null
        val i = body.indexOf('{')
        if (i < 0) return null
        return runCatching {
            val o = JSONObject(body.substring(i))
            val state = o.optString("weather", "")
            val temp = o.optString("temp", "").toFloatOrNull()?.roundToInt()
            if (state.isEmpty() || temp == null) return null
            Info(
                city = o.optString("cityname", "").ifEmpty { o.optString("city", "") },
                state = state,
                tempC = temp,
                at = System.currentTimeMillis()
            )
        }.getOrNull()
    }

    private fun readCache(): Info? {
        val at = prefs.getLong(KEY_AT, 0L)
        val state = prefs.getString(KEY_STATE, null) ?: return null
        if (at <= 0L || state.isEmpty()) return null
        // 超过 12 小时就不显示了，免得第二天还是"昨天的晴"
        if (System.currentTimeMillis() - at > 12 * 3600_000L) return null
        return Info(
            city = prefs.getString(KEY_CITY, "") ?: "",
            state = state,
            tempC = prefs.getInt(KEY_TEMP, 0),
            at = at
        )
    }

    // ---------------- 选城市 ----------------

    private fun resolveCode(s: ConfigSnapshot, cachedCode: String?): String? {
        // 1) ★ GPS 定位优先：车在哪就报哪的天气（这是主路径）
        val fix = locate()
        if (fix != null) {
            val city = nearest(fix.first, fix.second)
            if (city != null) {
                Log.i(
                    TAG,
                    "天气：定位 ${String.format(Locale.CHINA, "%.4f,%.4f", fix.first, fix.second)}" +
                        " → ${city.name}(${city.code})"
                )
                return city.code
            }
        }

        // 2) 配置里直接给了天气代码（定位不可用 / 想锁定城市时才用）
        val adcode = s.str("weather.adcode", "").trim()
        if (adcode.matches(Regex("\\d{9}"))) return adcode

        // 3) 配置里给的城市名 / 代码
        val cfgCity = s.str("weather.city", "").trim()
        if (cfgCity.isNotEmpty()) {
            if (cfgCity.matches(Regex("\\d{9}"))) return cfgCity
            cityByName(cfgCity)?.let { return it.code }
            Log.w(TAG, "天气：配置城市「$cfgCity」不在表里")
        }

        // 4) 固定坐标
        val flat = s.str("weather.fixedLat", "").toDoubleOrNull()
        val flng = s.str("weather.fixedLng", "").toDoubleOrNull()
        if (flat != null && flng != null) nearest(flat, flng)?.let { return it.code }

        // 5) IP 定位（免 key，最后兜底）
        ipCity()?.let { name -> cityByName(name)?.let { return it.code } }

        // 6) 缓存 / 默认
        return cachedCode?.takeIf { it.matches(Regex("\\d{9}")) } ?: DEFAULT_CODE
    }

    // ---------------- 定位 ----------------

    private fun hasLocationPerm(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) return true
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 取一个可用于查天气的坐标，按可信度依次尝试：
     *
     * 1. 最近已知点（GPS → 网络 → passive 里最新、且 30 分钟内的）；
     * 2. 主动单次取点：车机刚上电时 `getLastKnownLocation` 往往是 null，
     *    GPS 冷启动要 30s+ 才定位，所以这里只等 [FIX_TIMEOUT_MS]，拿不到就交给网络；
     * 3. 上次定位成功时落盘的坐标（6 小时内），避免高架桥下 / 地库短暂丢星时天气乱跳。
     */
    @SuppressLint("MissingPermission")
    private fun locate(): Pair<Double, Double>? {
        if (!hasLocationPerm()) {
            Log.w(TAG, "天气：没有定位权限，按配置 / IP 兜底")
            return storedFix()
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return storedFix()

        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "passive")) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                Log.w(TAG, "天气：定位权限被拒 $p")
                null
            } catch (e: Exception) {
                null
            } ?: continue
            // 超过 30 分钟的旧点不用（跨城跑高速时旧点会把城市判错）
            if (System.currentTimeMillis() - l.time > 30 * 60_000L) continue
            val cur = best
            if (cur == null || l.time > cur.time) best = l
        }
        best?.let { return saveFix(it.latitude, it.longitude) }

        // 没有可用的历史点：主动要一个实时点（GPS 优先，其次网络）
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val on = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            if (!on) continue
            singleFix(lm, p)?.let { return saveFix(it.first, it.second) }
        }
        return storedFix()
    }

    /** 主动请求一次定位，最多等 [FIX_TIMEOUT_MS]；拿不到返回 null。 */
    @SuppressLint("MissingPermission")
    private fun singleFix(lm: LocationManager, provider: String): Pair<Double, Double>? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                box.set(location)
                latch.countDown()
            }

            override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(p: String) = Unit
            override fun onProviderDisabled(p: String) = Unit
        }
        return try {
            // 必须给 Looper：本方法跑在 Dispatchers.IO 上，没有 Looper 会直接抛异常
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            if (latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                box.get()?.let { return it.latitude to it.longitude }
                null
            } else {
                Log.i(TAG, "天气：$provider ${FIX_TIMEOUT_MS}ms 内没取到点")
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "天气：$provider 取点失败 ${t.message}")
            null
        } finally {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    /** 上一次定位成功的坐标落盘（6 小时内有效）。 */
    private fun saveFix(lat: Double, lng: Double): Pair<Double, Double>? {
        prefs.edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LNG, lng.toString())
            .putLong(KEY_FIX_AT, System.currentTimeMillis())
            .apply()
        return lat to lng
    }

    private fun storedFix(): Pair<Double, Double>? {
        val at = prefs.getLong(KEY_FIX_AT, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > 6 * 3600_000L) return null
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun ipCity(): String? {
        val body = http("http://ip-api.com/json/?lang=zh-CN&fields=status,city", null) ?: return null
        return runCatching {
            val o = JSONObject(body)
            if (o.optString("status") != "success") null else o.optString("city").ifEmpty { null }
        }.getOrNull()
    }

    private fun cityByName(raw: String): City? {
        val target = normalize(raw)
        if (target.isEmpty()) return null
        // 先找完全同名（去掉「市/区/县」后缀后比较），找不到再找父级同名
        return cities.firstOrNull { normalize(it.name) == target }
            ?: cities.firstOrNull { normalize(it.parent) == target }
    }

    private fun nearest(lat: Double, lng: Double): City? =
        cities.minByOrNull { distanceKm(lat, lng, it.lat, it.lng) }
            ?.takeIf { distanceKm(lat, lng, it.lat, it.lng) < 300 }

    private fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun normalize(s: String): String {
        var t = s.trim()
        for (suffix in arrayOf("特别行政区", "自治州", "自治县", "自治区", "市辖区", "地区", "盟", "市", "区", "县", "旗")) {
            if (t.length > suffix.length && t.endsWith(suffix)) {
                t = t.substring(0, t.length - suffix.length)
                break
            }
        }
        return t
    }

    // ---------------- 城市表 ----------------

    private data class City(val name: String, val lat: Double, val lng: Double, val code: String, val parent: String)

    /**
     * assets/cities.json：`[[名称, 纬度, 经度, 天气代码, 所属市], ...]`，3384 条区县。
     * 和原版 CityTable 用的是同一份文件。
     */
    private fun loadCities(): List<City> {
        return runCatching {
            val text = ctx.assets.open("cities.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val arr: JSONArray = JSONArray(text)
            val out = ArrayList<City>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                out.add(
                    City(
                        name = row.optString(0),
                        lat = row.optDouble(1, 0.0),
                        lng = row.optDouble(2, 0.0),
                        code = row.optString(3),
                        parent = if (row.length() > 4) row.optString(4) else ""
                    )
                )
            }
            Log.i(TAG, "城市表：载入 ${out.size} 条")
            out
        }.getOrElse {
            Log.w(TAG, "城市表：载入失败 $it")
            emptyList()
        }
    }

    // ---------------- HTTP ----------------

    private fun http(url: String, referer: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "天气：HTTP ${conn.responseCode} $url")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "天气：请求失败 $url ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val TAG = "H6Weather"

        /** 天气来源，设置页「天气来源」一行直接显示，也方便排查"显示的是谁的数据"。 */
        const val SRC_FACTORY = "原厂（原车状态栏同一份数据）"
        const val SRC_ONLINE = "在线（原厂数据不可用）"

        /** 最近一次成功取数的来源，空串 = 还没取到过。 */
        @Volatile
        var lastSource: String = ""

        /** 什么都没探到时用它：廊坊（原版 adcodeFallback=131000 对应的城市）。 */
        const val DEFAULT_CODE = "101090601"
        const val KEY_CODE = "wcode"
        const val KEY_CITY = "city"
        const val KEY_STATE = "state"
        const val KEY_TEMP = "temp"
        const val KEY_AT = "at"
        /** 上次定位成功的坐标（天气只认 GPS，落盘是为了地库/隧道里不跳城市）。 */
        const val KEY_LAT = "fixLat"
        const val KEY_LNG = "fixLng"
        const val KEY_FIX_AT = "fixAt"

        /** 单次主动定位的等待上限：GPS 冷启动慢，网络定位通常 1~2s 就回。 */
        const val FIX_TIMEOUT_MS = 8000L
    }
}
