package com.example.radioarealocator.data.satellite

import com.github.amsacode.predict4java.TLE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 卫星 TLE 数据源，支持 CelesTrak 和 SatNOGS 双源获取并去重。
 */
class SatelliteDataSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 获取业余卫星 TLE 列表。
     * 并行从 CelesTrak 和 SatNOGS 拉取，合并去重（按 NORAD 编号）。
     * 任一源失败时使用另一个源的结果。
     */
    suspend fun fetchAmateurTLEs(): List<TLE> = withContext(Dispatchers.IO) {
        coroutineScope {
            val celestrakDeferred = async { runCatching { fetchCelestrakTLEs() } }
            val satnogsDeferred = async { runCatching { fetchSatnogsTLEs() } }

            val celestrakResult = celestrakDeferred.await()
            val satnogsResult = satnogsDeferred.await()

            if (celestrakResult.isFailure && satnogsResult.isFailure) {
                throw IOException(
                    "TLE 下载失败：CelesTrak=${celestrakResult.exceptionOrNull()?.message}, " +
                        "SatNOGS=${satnogsResult.exceptionOrNull()?.message}"
                )
            }

            // 合并去重，按 NORAD 编号保留第一个出现的
            val merged = LinkedHashMap<Int, TLE>()
            celestrakResult.getOrNull()?.forEach { tle ->
                merged.putIfAbsent(tle.catnum, tle)
            }
            satnogsResult.getOrNull()?.forEach { tle ->
                merged.putIfAbsent(tle.catnum, tle)
            }

            merged.values.toList()
        }
    }

    /**
     * 从 CelesTrak 获取业余卫星 TLE（标准三行文本格式）。
     */
    private fun fetchCelestrakTLEs(): List<TLE> {
        val request = Request.Builder()
            .url(CELESTRAK_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("CelesTrak 请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("CelesTrak 响应为空")
            return parseTextTLEs(body)
        }
    }

    /**
     * 从 SatNOGS 获取业余卫星 TLE（JSON 格式）。
     */
    private fun fetchSatnogsTLEs(): List<TLE> {
        val request = Request.Builder()
            .url(SATNOGS_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SatNOGS 请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("SatNOGS 响应为空")
            return parseJsonTLEs(body)
        }
    }

    /**
     * 解析标准三行文本格式 TLE。
     */
    private fun parseTextTLEs(text: String): List<TLE> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<TLE>()
        var i = 0
        while (i < lines.size) {
            val isNameLine = !lines[i].startsWith("1 ") && !lines[i].startsWith("2 ")
            val name = if (isNameLine) lines[i] else ""
            val line1Index = if (isNameLine) i + 1 else i
            val line2Index = line1Index + 1

            if (line2Index >= lines.size) break

            val line1 = lines[line1Index]
            val line2 = lines[line2Index]

            if (line1.startsWith("1 ") && line2.startsWith("2 ")) {
                try {
                    result.add(TLE(arrayOf(name, line1, line2)))
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }

            i = line2Index + 1
        }
        return result
    }

    /**
     * 解析 SatNOGS JSON 格式 TLE。
     * JSON 数组，每个元素包含 tle0（名称）、tle1（Line1）、tle2（Line2）。
     */
    private fun parseJsonTLEs(json: String): List<TLE> {
        val result = mutableListOf<TLE>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val tle0 = obj.optString("tle0", "")
                val tle1 = obj.optString("tle1", "")
                val tle2 = obj.optString("tle2", "")
                if (tle1.isBlank() || tle2.isBlank()) continue
                try {
                    result.add(TLE(arrayOf(tle0, tle1, tle2)))
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
        } catch (e: Exception) {
            throw IOException("SatNOGS JSON 解析失败：${e.message}")
        }
        return result
    }

    companion object {
        private const val CELESTRAK_URL = "https://celestrak.org/NORAD/elements/amateur.txt"
        private const val SATNOGS_URL = "https://db.satnogs.org/api/tle/"
    }
}
