package com.example.hamkit.shared

import com.example.hamkit.shared.net.HttpTransport

/**
 * SatNOGS DB 转发器（transmitter）数据模型。
 *
 * 从 Android `data/satellite/TransmitterDataSource.kt` + `RadioInfo.kt` 移植：
 * 字段语义、缺省处理（频率 0 视为缺失、name/description 为字面量 "null" 视为缺失、
 * 状态缺省 unknown）保持一致。
 */
data class Transmitter(
    val norad: Int,
    val name: String,
    val mode: String,
    /** 上行频率（Hz）；0 表示无 */
    val uplinkHz: Long,
    /** 下行频率（Hz）；0 表示无 */
    val downlinkHz: Long,
    val inverted: Boolean,
    /** active / inactive / future / unknown */
    val status: String,
) {
    val isActive: Boolean get() = status == STATUS_ACTIVE

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_INACTIVE = "inactive"
        const val STATUS_FUTURE = "future"
        const val STATUS_UNKNOWN = "unknown"
    }
}

/** 一次转发器拉取结果；[error] 非空表示失败（列表可能为空）。 */
data class TransmitterSnapshot(
    val transmitters: List<Transmitter>,
    val error: String?,
)

/**
 * SatNOGS DB 转发器数据源（KMP 版）。
 *
 * 依赖注入的 [HttpTransport]，与 TLE 共用平台网络栈；JSON 解析复用本文件的
 * 轻量解析器，避免为 iOS 引入 org.json / kotlinx-serialization 依赖。
 */
object SatnogsTransmitters {

    /** SatNOGS DB transmitters API（含全部状态，状态区分在 UI 层） */
    const val URL = "https://db.satnogs.org/api/transmitters/?format=json"

    /**
     * 兜底源：自建 CDN 上的静态快照（同 [URL] 的 JSON 数组）。
     *
     * db.satnogs.org 在国内容易被 DNS 污染/超时（Android 侧已在
     * `SatelliteDataSource` 注释里写明同类问题，只是转发器表没做兜底），
     * 这里允许把同一份数据镜像到自有 CDN：`scripts/sync_satnogs_transmitters.sh`。
     */
    const val MIRROR_URL = "https://tle.hamkit.click/transmitters.json"

    private val SOURCES: List<String> = listOf(URL, MIRROR_URL)

    /** 拉取全部转发器；全部源都失败时回调 [TransmitterSnapshot.error]。 */
    fun fetch(transport: HttpTransport, callback: (TransmitterSnapshot) -> Unit) {
        fetchFrom(transport, 0, emptyList(), callback)
    }

    private fun fetchFrom(
        transport: HttpTransport,
        index: Int,
        failures: List<String>,
        callback: (TransmitterSnapshot) -> Unit,
    ) {
        if (index >= SOURCES.size) {
            callback(
                TransmitterSnapshot(
                    transmitters = emptyList(),
                    error = failures.joinToString("；").ifEmpty { "没有可用的转发器数据源" },
                )
            )
            return
        }
        val url = SOURCES[index]
        // 全量转发器 JSON 有几 MB，30s 在弱网下经常读不完（Android 端同样给了 30s，
        // 但它有 Room 缓存兜底）；iOS 放宽到 60s，并配合 UI 的一次自动重试。
        transport.get(url, 60_000) { response ->
            if (!response.isSuccess) {
                val reason = response.error ?: "HTTP ${response.status}"
                fetchFrom(transport, index + 1, failures + "$url → $reason", callback)
                return@get
            }
            val body = response.body
            if (body.isNullOrBlank()) {
                fetchFrom(transport, index + 1, failures + "$url → 响应为空", callback)
                return@get
            }
            val parsed = try {
                parse(body)
            } catch (e: Exception) {
                fetchFrom(transport, index + 1, failures + "$url → 解析失败：${e.message}", callback)
                return@get
            }
            if (parsed.isEmpty()) {
                fetchFrom(transport, index + 1, failures + "$url → 解析结果为空", callback)
                return@get
            }
            callback(TransmitterSnapshot(parsed, null))
        }
    }

    /** 解析 SatNOGS transmitters API 的 JSON 数组。解析失败抛异常。 */
    fun parse(json: String): List<Transmitter> {
        val items = MiniJson.parseArrayRoot(json)
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseItem(raw: Any?): Transmitter? {
        val item = raw as? Map<*, *> ?: return null
        val norad = (item["norad_cat_id"] as? Double)?.toInt() ?: return null
        if (norad <= 0) return null

        val uplink = (item["uplink_low"] as? Double)?.toLong() ?: 0L
        val downlink = (item["downlink_low"] as? Double)?.toLong() ?: 0L
        if (uplink <= 0 && downlink <= 0) return null

        val mode = cleanField(item["uplink_mode"] as? String)
            ?: cleanField(item["downlink_mode"] as? String)
            ?: cleanField(item["mode"] as? String)
            ?: ""

        val rawName = cleanField(item["name"] as? String)
        val description = cleanField(item["description"] as? String)
        val name = rawName ?: description ?: run {
            val freqHz = if (downlink > 0) downlink else uplink
            val freqMhz = formatMhz(freqHz)
            if (mode.isNotBlank()) "$mode $freqMhz" else freqMhz
        }

        val status = (item["status"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: Transmitter.STATUS_UNKNOWN

        return Transmitter(
            norad = norad,
            name = name,
            mode = mode.trim(),
            uplinkHz = uplink,
            downlinkHz = downlink,
            inverted = item["inverted"] as? Boolean ?: false,
            status = status,
        )
    }

    /** 空串/空白/字面量 "null" 视为缺失；剔除中括号（"[Beacon]" → "Beacon"）。 */
    private fun cleanField(value: String?): String? =
        value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.filterNot { it == '[' || it == ']' }
            ?.trim()
            ?.ifEmpty { null }

    private fun formatMhz(hz: Long): String {
        val mhz = hz / 1_000_000.0
        val whole = mhz.toLong()
        val frac = ((mhz - whole) * 1000).toLong()
        return "$whole.${frac.toString().padStart(3, '0')} MHz"
    }
}
