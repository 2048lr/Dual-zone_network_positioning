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

    /** 拉取全部转发器；网络/解析失败时回调 [TransmitterSnapshot.error]。 */
    fun fetch(transport: HttpTransport, callback: (TransmitterSnapshot) -> Unit) {
        transport.get(URL, 30_000) { response ->
            if (!response.isSuccess) {
                callback(
                    TransmitterSnapshot(
                        transmitters = emptyList(),
                        error = response.error ?: "SatNOGS transmitters 请求失败：HTTP ${response.status}",
                    )
                )
                return@get
            }
            val body = response.body ?: run {
                callback(TransmitterSnapshot(emptyList(), "SatNOGS transmitters 响应为空"))
                return@get
            }
            try {
                callback(TransmitterSnapshot(parse(body), null))
            } catch (e: Exception) {
                callback(TransmitterSnapshot(emptyList(), "解析失败：${e.message}"))
            }
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
