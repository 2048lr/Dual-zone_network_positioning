package com.example.hamkit.data.weather

import com.example.hamkit.data.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 天气服务（Open-Meteo，无需客户端 API Key）。
 *
 * 以前的实现使用高德 Web 服务 Key（天气 + 逆地理编码），Key 必须随 APK 分发，
 * 任何“三碎片加密”都只是混淆，无法阻止提取。现在：
 * - 天气改用 Open-Meteo 的公开 API，按经纬度查询，不需要 Key；
 * - 逆地理编码地址由系统 [android.location.Geocoder]（见 LocationHelper）提供，
 *   不再调用高德 Web 服务；
 * - 高德只保留 Android 地图 SDK，其 Key 是客户端标识（可提取），按包名/签名限制，
 *   不再把它当秘密。
 *
 * 数据来源：Open-Meteo（https://open-meteo.com/），数据按 CC BY 4.0 提供，
 * 发布/展示时应保留来源说明。
 */
class WeatherApiService {

    // 基于共享单例派生：共享连接池/线程池，仅覆盖本服务的超时配置
    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 获取当前天气与 4 天预报。
     *
     * @param latitude 纬度（WGS84）
     * @param longitude 经度（WGS84）
     * @param cityName 可选的地址/城市名，仅用于展示，不参与请求
     */
    suspend fun fetchWeather(
        latitude: Double,
        longitude: Double,
        cityName: String = "",
    ): WeatherResult = withContext(Dispatchers.IO) {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            throw WeatherApiException("无效坐标: $latitude,$longitude")
        }

        val url = buildUrl(
            WEATHER_BASE_URL,
            mapOf(
                "latitude" to String.format(Locale.US, "%.6f", latitude),
                "longitude" to String.format(Locale.US, "%.6f", longitude),
                "current" to "temperature_2m,relative_humidity_2m,weather_code," +
                    "wind_speed_10m,wind_direction_10m",
                "daily" to "weather_code,temperature_2m_max,temperature_2m_min",
                "timezone" to "auto",
                "forecast_days" to "4",
            ),
        )
        val json = parseJson(executeRequest(url))

        val current = json.optJSONObject("current")
            ?: throw WeatherApiException("响应缺少 current 字段")
        val code = current.optInt("weather_code", -1)
        if (code < 0) throw WeatherApiException("响应缺少 weather_code")

        val now = WeatherNow(
            temp = formatTemperature(current.optDouble("temperature_2m", Double.NaN)),
            text = weatherText(code),
            windDir = windDirection(current.optDouble("wind_direction_10m", Double.NaN)),
            windPower = current.optDouble("wind_speed_10m", Double.NaN)
                .takeIf { it.isFinite() }
                ?.let { String.format(Locale.US, "%.0f km/h", it) }
                .orEmpty(),
            humidity = current.optInt("relative_humidity_2m", -1)
                .takeIf { it >= 0 }
                ?.toString()
                .orEmpty(),
            reportTime = current.optString("time").replace('T', ' ').trim(),
        )

        WeatherResult(
            cityName = cityName,
            now = now,
            daily = parseDaily(json.optJSONObject("daily")),
        )
    }

    private fun parseDaily(daily: JSONObject?): List<WeatherDay> {
        if (daily == null) return emptyList()
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code") ?: return emptyList()
        val maxTemps = daily.optJSONArray("temperature_2m_max") ?: return emptyList()
        val minTemps = daily.optJSONArray("temperature_2m_min") ?: return emptyList()
        val count = minOf(dates.length(), codes.length(), maxTemps.length(), minTemps.length(), 4)
        return (0 until count).mapNotNull { index ->
            val date = dates.optString(index)
            if (date.isBlank()) return@mapNotNull null
            val text = weatherText(codes.optInt(index, -1))
            WeatherDay(
                date = date,
                week = weekday(date),
                dayWeather = text,
                nightWeather = text,
                dayTemp = formatTemperature(maxTemps.optDouble(index, Double.NaN)),
                nightTemp = formatTemperature(minTemps.optDouble(index, Double.NaN)),
            )
        }
    }

    private fun formatTemperature(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.0f", value) else "--"

    private fun windDirection(degrees: Double): String {
        if (!degrees.isFinite()) return ""
        val directions = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val index = (((degrees + 22.5) / 45.0).toInt() % 8 + 8) % 8
        return directions[index]
    }

    private fun weekday(date: String): String = runCatching {
        when (LocalDate.parse(date).dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
    }.getOrDefault("")

    /** WMO weather code → 展示文字；中文环境用中文，其他语言用英文。 */
    private fun weatherText(code: Int): String {
        val zh = Locale.getDefault().language.startsWith("zh")
        return when (code) {
            0 -> if (zh) "晴" else "Clear"
            1 -> if (zh) "晴间多云" else "Mainly clear"
            2 -> if (zh) "多云" else "Partly cloudy"
            3 -> if (zh) "阴" else "Overcast"
            45, 48 -> if (zh) "雾" else "Fog"
            51, 53, 55 -> if (zh) "毛毛雨" else "Drizzle"
            56, 57 -> if (zh) "冻毛毛雨" else "Freezing drizzle"
            61, 63, 65 -> if (zh) "雨" else "Rain"
            66, 67 -> if (zh) "冻雨" else "Freezing rain"
            71, 73, 75, 77 -> if (zh) "雪" else "Snow"
            80, 81, 82 -> if (zh) "阵雨" else "Rain showers"
            85, 86 -> if (zh) "阵雪" else "Snow showers"
            95 -> if (zh) "雷阵雨" else "Thunderstorm"
            96, 99 -> if (zh) "雷阵雨伴冰雹" else "Thunderstorm with hail"
            else -> if (zh) "未知" else "Unknown"
        }
    }

    /**
     * 构建带 query 参数的 URL，参数值自动 URL 编码，避免特殊字符破坏 URL 结构。
     */
    private fun buildUrl(base: String, params: Map<String, String>): String {
        val builder = base.toHttpUrl().newBuilder()
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    /**
     * 安全解析 JSON 响应体。服务器返回 200 但 Body 非 JSON（如代理劫持页）时，
     * 转换为 [WeatherApiException] 而非让 JSONException 直接传播。
     */
    private fun parseJson(response: String): JSONObject {
        return try {
            JSONObject(response)
        } catch (e: JSONException) {
            throw WeatherApiException("响应不是有效 JSON: ${e.message}")
        }
    }

    /**
     * 执行 HTTP 请求，返回响应体字符串。
     *
     * @throws WeatherNetworkException 网络层异常（连接超时、DNS 解析失败、断网等）
     */
    private fun executeRequest(url: String): String {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    throw WeatherNetworkException("HTTP ${response.code}")
                }
            }
        } catch (e: WeatherNetworkException) {
            throw e
        } catch (e: Exception) {
            throw WeatherNetworkException(e.message ?: "网络请求异常")
        }
    }

    companion object {
        private const val WEATHER_BASE_URL = "https://api.open-meteo.com/v1/forecast"
    }
}

/**
 * 网络层异常：连接超时、DNS 解析失败、断网、HTTP 非 2xx 等。
 */
class WeatherNetworkException(message: String) : Exception(message)

/**
 * 天气 API 业务错误：响应缺少字段、JSON 非法等。
 */
class WeatherApiException(message: String) : Exception(message)
