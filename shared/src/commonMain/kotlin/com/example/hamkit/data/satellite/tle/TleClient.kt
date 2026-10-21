package com.example.hamkit.data.satellite.tle

import com.example.hamkit.shared.net.HttpTransport

/**
 * TLE 下载地址。
 *
 * 默认顺序与 Android 端一致：
 * 1. **HamKit 自建 CDN** `https://tle.hamkit.click/tle/<group>.3le`
 *    （国内访问快、格式就是三行 TLE，Android 端 `SatelliteDataSource` 用的同一套）；
 * 2. CelesTrak 官方 `gp.php?GROUP=<group>&FORMAT=tle` 兜底。
 *
 * 任一地址成功即返回，全部失败才把错误抛给 UI；这样 CDN 挂掉或域名未解析时
 * iOS 端仍然能工作。
 */
class TleEndpoints(
    private val cdnBase: String? = DEFAULT_CDN_BASE,
    private val celestrakBase: String? = DEFAULT_CELESTRAK_BASE,
) {

    /** 按顺序返回该分组的候选地址；测试里可以只保留一个。 */
    fun urls(group: TleGroup): List<String> = buildList {
        if (!cdnBase.isNullOrBlank()) add("$cdnBase/${group.wireName}.3le")
        if (!celestrakBase.isNullOrBlank()) add("$celestrakBase?GROUP=${group.wireName}&FORMAT=tle")
    }

    companion object {
        const val DEFAULT_CDN_BASE: String = "https://tle.hamkit.click/tle"
        const val DEFAULT_CELESTRAK_BASE: String = "https://celestrak.org/NORAD/elements/gp.php"
    }
}

/**
 * TLE 拉取客户端：按 [TleEndpoints] 的顺序依次尝试，返回第一份能解析出数据的结果。
 *
 * 选择三行 TLE（而不是 CSV）：返回内容可以直接交给 [TleRecord] / SGP4 引擎，
 * 不需要在客户端重建 line1/line2 的定长格式（Android 端的 CSV → TLE 构造依赖
 * `String.format`，属于 JVM-only API，不适合放进 commonMain）。
 */
class TleClient(
    private val http: HttpTransport,
    private val endpoints: TleEndpoints = TleEndpoints(),
) {

    /**
     * 拉取一个分组的全部 TLE。
     *
     * @param timeoutMs 单个地址的超时（毫秒）
     * @param callback 结果回调；全部地址都失败时 [TleFetchOutcome.error] 非空
     */
    fun fetch(group: TleGroup, timeoutMs: Int = DEFAULT_TIMEOUT_MS, callback: (TleFetchOutcome) -> Unit) {
        val urls = endpoints.urls(group)
        if (urls.isEmpty()) {
            callback(TleFetchOutcome(emptyList(), "没有可用的 TLE 地址"))
            return
        }
        fetchFrom(urls, 0, timeoutMs, emptyList(), callback)
    }

    private fun fetchFrom(
        urls: List<String>,
        index: Int,
        timeoutMs: Int,
        failures: List<String>,
        callback: (TleFetchOutcome) -> Unit,
    ) {
        if (index >= urls.size) {
            callback(TleFetchOutcome(emptyList(), failures.joinToString("；").ifEmpty { "全部 TLE 地址均失败" }))
            return
        }
        val url = urls[index]
        http.get(url, timeoutMs) { response ->
            val records = if (response.isSuccess) TleTextParser.parse(response.body.orEmpty()) else emptyList()
            if (records.isNotEmpty()) {
                callback(TleFetchOutcome(records, null))
            } else {
                val reason = response.error ?: "响应里没有可解析的 TLE"
                fetchFrom(urls, index + 1, timeoutMs, failures + "$url → $reason", callback)
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Int = 15_000
    }
}

/**
 * 带内存缓存的 TLE 仓库。
 *
 * 行为：
 * - 缓存未过期（默认 6 小时）且未强制刷新 → 立即回调缓存，不发请求；
 * - 网络失败 → 回退到上一次成功的数据（离线可用），同时把错误交给 UI 展示；
 * - 只保留最近一次成功拉取的分组，切换分组会自动重新拉取。
 *
 * 时间由调用方传入（`nowMs`），这样跨平台不需要日期库，单测也能精确控制过期。
 */
class TleRepository(
    private val client: TleClient,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    private data class Entry(
        val group: TleGroup,
        val records: List<TleRecord>,
        val fetchedAtMs: Long,
    )

    private var entry: Entry? = null

    fun load(
        group: TleGroup,
        forceRefresh: Boolean,
        nowMs: Long,
        callback: (TleSnapshot) -> Unit,
    ) {
        val cached = entry
        if (!forceRefresh &&
            cached != null &&
            cached.group == group &&
            nowMs - cached.fetchedAtMs in 0..ttlMs
        ) {
            callback(TleSnapshot(cached.records, cached.fetchedAtMs, fromCache = true, error = null))
            return
        }

        client.fetch(group) { outcome ->
            if (outcome.error != null) {
                val fallback = entry?.takeIf { it.group == group }
                callback(
                    TleSnapshot(
                        records = fallback?.records.orEmpty(),
                        fetchedAtMs = fallback?.fetchedAtMs ?: 0L,
                        fromCache = fallback != null,
                        error = outcome.error,
                    )
                )
            } else {
                entry = Entry(group, outcome.records, nowMs)
                callback(TleSnapshot(outcome.records, nowMs, fromCache = false, error = null))
            }
        }
    }

    companion object {
        /** 缓存有效期：6 小时。TLE 每天更新数次，6 小时足够新且不会频繁打数据源。 */
        const val DEFAULT_TTL_MS: Long = 6L * 60L * 60L * 1000L
    }
}
