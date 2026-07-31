package com.example.radioarealocator.data.satellite

import com.github.amsacode.predict4java.TLE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.example.radioarealocator.data.network.HttpClientProvider
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 协程安全的 [runCatching]：捕获异常时重新抛出 [CancellationException]，
 * 避免破坏 Kotlin 协程的结构化并发语义。
 */
private suspend inline fun <R> runCatchingCancellable(block: suspend () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 带 TLE 数据来源标记的包装类。
 */
data class SourcedTLE(
    val tle: TLE,
    val source: String, // "CT" / "SNOGS" / "ALL"
    val status: String = "", // AMSAT 状态：Heard / Telemetry Only / Not Heard / Crew Active
    val rawLines: Array<String> = arrayOf("", "", "") // 原始三行 TLE，用于本地缓存序列化
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourcedTLE) return false
        return tle == other.tle && source == other.source && status == other.status &&
            rawLines.contentEquals(other.rawLines)
    }

    override fun hashCode(): Int {
        var result = tle.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + rawLines.contentHashCode()
        return result
    }
}

/**
 * 卫星 TLE 数据源，同时从 SatNOGS 和 CelesTrak 获取并合并去重，
 * 再附加 AMSAT 状态报告。返回全部业余卫星（不做 catalog 过滤），
 * 不在 SatelliteCatalog 中的卫星 modes 为空（UI 显示"未知"）。
 */
class SatelliteDataSource {

    // 基于共享单例派生：共享连接池/线程池，仅覆盖本服务的超时配置
    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val amsatStatusApi = AmsatStatusApiService()

    /**
     * 获取业余卫星 TLE 列表（返回数据源提供的全部业余卫星，不做 catalog 过滤）。
     *
     * 从 SatNOGS 与 CelesTrak 并行拉取 TLE，按 NORAD 编号合并去重；
     * 同时出现在两个源的卫星标记为 ALL。随后附加 AMSAT 状态报告。
     *
     * 任一 TLE 源失败时容忍，使用另一个源的结果；
     * 仅当所有 TLE 源都失败时才抛 IOException，避免返回空列表覆盖本地缓存。
     * AMSAT 状态源失败不影响 TLE 结果。
     */
    suspend fun fetchAmateurTLEs(): List<SourcedTLE> = withContext(Dispatchers.IO) {
        coroutineScope {
            val satnogsDeferred = async { runCatchingCancellable { fetchSatnogsTLEs() } }
            val celesTrakDeferred = async { runCatchingCancellable { fetchCelesTrakTLEs() } }
            // AMSAT 状态与 TLE 源正交，始终附加状态标签
            val amsatStatusDeferred = async { runCatchingCancellable { amsatStatusApi.fetchStatusSummaries() } }

            val satnogsResult = satnogsDeferred.await()
            val celesTrakResult = celesTrakDeferred.await()
            val amsatStatusResult = amsatStatusDeferred.await()

            // 两源都失败时抛异常，避免返回空列表覆盖本地缓存。AMSAT 状态失败不在此判定内。
            val tleResults = listOf(satnogsResult, celesTrakResult)
            if (tleResults.all { it.isFailure }) {
                throw IOException(
                    "TLE 下载失败：SatNOGS=${satnogsResult.exceptionOrNull()?.message}, " +
                        "CelesTrak=${celesTrakResult.exceptionOrNull()?.message}"
                )
            }

            // 按 NORAD 编号合并，记录来源；两源都有的卫星标记为 ALL
            val merged = LinkedHashMap<Int, SourcedTLE>()
            satnogsResult.getOrNull()?.forEach { stle ->
                merged[stle.tle.catnum] = stle
            }
            celesTrakResult.getOrNull()?.forEach { stle ->
                val existing = merged[stle.tle.catnum]
                merged[stle.tle.catnum] = if (existing != null && existing.source != stle.source) {
                    existing.copy(source = "ALL")
                } else {
                    stle
                }
            }

            // 附加 AMSAT 状态（失败时不影响 TLE 结果）
            val statusMap = amsatStatusResult.getOrNull() ?: emptyMap()
            merged.values.toList().map { sourcedTle ->
                val amsatName = SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[sourcedTle.tle.catnum]
                val status = if (amsatName != null) statusMap[amsatName] ?: "" else ""
                sourcedTle.copy(status = status)
            }
        }
    }

    /**
     * 获取 SatNOGS 维护的卫星 TLE（参考 Look4Sat 实现）。
     *
     * 不直接请求 db.satnogs.org DB API（国内访问不稳定），而是请求 CelesTrak 整理好的
     * `satnogs` 分组（3le 文本格式）。CelesTrak 已过滤失效/再入卫星，无需再做 alive 过滤。
     *
     * 实测约 649 颗，含 SatNOGS 跟踪的全部可观测卫星；不在 SatelliteCatalog 中的
     * 卫星 modes 为空列表（UI 显示"未知"），AMSAT 状态为空字符串。
     */
    private fun fetchSatnogsTLEs(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(SATNOGS_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SatNOGS 请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("SatNOGS 响应为空")
            val triples = parseThreeLineTLEs(body)
            val tles = mutableListOf<SourcedTLE>()
            for ((tle0, tle1, tle2) in triples) {
                // NORAD 编号位于 line1 的第 3-7 列（0-based 索引 2..6）
                if (tle1.length < 7) continue
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue
                if (noradCatId <= 0) continue

                try {
                    tles.add(
                        SourcedTLE(
                            tle = TLE(arrayOf(tle0, tle1, tle2)),
                            source = "SNOGS",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            if (tles.isEmpty()) {
                return emptyList()
            }
            return tles
        }
    }

    /**
     * 从 CelesTrak 批量获取业余卫星 TLE，返回全部（不做 catalog 过滤）。
     *
     * 使用 gp.php 3le 文本接口（GROUP=amateur&FORMAT=3le），返回标准三行 TLE：
     * 第一行卫星名称，后两行为 TLE line1/line2，可直接喂给 predict4java 的 [TLE]。
     * 不在 SatelliteCatalog 中的卫星 modes 为空列表（UI 显示"未知"）。
     *
     * NORAD 编号从 line1 第 3-7 列解析（标准 TLE 格式），避免依赖名称行匹配。
     */
    private fun fetchCelesTrakTLEs(): List<SourcedTLE> {
        val request = Request.Builder()
            .url(CELESTRAK_URL)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("CelesTrak 请求失败：${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("CelesTrak 响应为空")
            val tles = mutableListOf<SourcedTLE>()

            val triples = parseThreeLineTLEs(body)
            for ((tle0, tle1, tle2) in triples) {
                // NORAD 编号位于 line1 的第 3-7 列（0-based 索引 2..6）
                if (tle1.length < 7) continue
                val noradCatId = tle1.substring(2, 7).trim().toIntOrNull() ?: continue

                try {
                    tles.add(
                        SourcedTLE(
                            tle = TLE(arrayOf(tle0, tle1, tle2)),
                            source = "CT",
                            rawLines = arrayOf(tle0, tle1, tle2)
                        )
                    )
                } catch (_: IllegalArgumentException) {
                    // 跳过解析失败的 TLE
                }
            }
            if (tles.isEmpty()) {
                return emptyList()
            }
            return tles
        }
    }

    /**
     * 解析标准三行文本格式 TLE，返回 (name, line1, line2) 三元组列表。
     * 适用于 CelesTrak 3le 格式：每三行为一组，依次是名称行、line1、line2。
     */
    private fun parseThreeLineTLEs(text: String): List<Triple<String, String, String>> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = mutableListOf<Triple<String, String, String>>()
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
                result.add(Triple(name, line1, line2))
            }

            i = line2Index + 1
        }
        return result
    }

    companion object {
        // SatNOGS 源改用 CelesTrak 整理的 satnogs 分组（3le 文本格式），
        // 避免直接请求 db.satnogs.org（国内访问不稳定），CelesTrak 有 CDN 且已过滤失效卫星
        private const val SATNOGS_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=satnogs&FORMAT=3le"
        private const val CELESTRAK_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=3le"
    }
}
