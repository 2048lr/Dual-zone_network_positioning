package com.example.hamkit.shared

import com.example.hamkit.data.satellite.pass.PassFinder
import com.example.hamkit.data.satellite.predict.PropagatedPosition
import com.example.hamkit.data.satellite.predict.SatellitePropagator
import com.example.hamkit.data.satellite.predict.TleElements
import com.example.hamkit.data.satellite.tle.TleClient
import com.example.hamkit.data.satellite.tle.TleGroup
import com.example.hamkit.data.satellite.tle.TleRecord
import com.example.hamkit.data.satellite.tle.TleRepository
import com.example.hamkit.data.zone.ZoneResolver
import com.example.hamkit.shared.net.HttpTransport
import kotlin.math.PI
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * 分区定位结果（Swift 友好）。
 *
 * 与 [com.example.hamkit.data.ZoneInfo] 的区别：这里把「未知」编码为 `0`，
 * 避免 Kotlin 的 `Int?` 在 Swift 侧变成 `KotlinInt?` 再解包。
 * CQ 分区取值范围 1–40、ITU 分区 1–90，`0` 不会与真实值冲突。
 */
data class ZoneSnapshot(
    /** 6 位 Maidenhead 网格（例如 `OM89ev`） */
    val grid: String,
    /** CQ 分区；0 表示未知 */
    val cqZone: Int,
    /** ITU 分区；0 表示未知 */
    val ituZone: Int,
)

/**
 * 一次过境 + 所属卫星：供「附近过境卫星」跨星预测使用。
 * 顶层类（非 HamKitApi 嵌套），保证 Swift 侧导出名为 `SatelliteNextPass`。
 */
data class SatelliteNextPass(
    val name: String,
    val norad: Int,
    /** 该卫星的下一次过境（含当前在境那一次） */
    val pass: PassSnapshot,
)

/**
 * 某一时刻卫星的地平坐标与星下点（角度均为度，距离为 km）。
 */
data class PositionSnapshot(
    /** 采样时刻（UTC 毫秒） */
    val timeMs: Long,
    /** 方位角（度，0=正北，顺时针） */
    val azimuthDeg: Double,
    /** 仰角（度，负值在地平线以下） */
    val elevationDeg: Double,
    /** 斜距（km） */
    val rangeKm: Double,
    /** 径向速度（m/s，>0 远离，<0 接近） */
    val rangeRateMps: Double,
    /** 星下点纬度（度） */
    val subLatitudeDeg: Double,
    /** 星下点经度（度） */
    val subLongitudeDeg: Double,
    /** 轨道高度（km） */
    val altitudeKm: Double,
    /** 是否在地平线以上 */
    val isAboveHorizon: Boolean,
)

/**
 * 一次过境（Swift 友好）。
 */
data class PassSnapshot(
    /** 过境开始（UTC 毫秒） */
    val aosMs: Long,
    /** 过境结束（UTC 毫秒） */
    val losMs: Long,
    /** 最大仰角（度） */
    val maxElevationDeg: Double,
    /** 最大仰角时刻（UTC 毫秒） */
    val maxElevationMs: Long,
    /** AOS 方位角（度） */
    val aosAzimuthDeg: Int,
    /** LOS 方位角（度） */
    val losAzimuthDeg: Int,
    /** 过境时长（秒） */
    val durationSeconds: Long,
    /** 查询时刻是否已经可见（此时 [aosMs] 等于查询时刻） */
    val isCurrentlyVisible: Boolean,
)

/**
 * 一次 TLE 列表拉取的结果（Swift 友好）。
 *
 * 失败时 [records] 可能是上一次成功拉取的缓存（[fromCache] = true），
 * 这样 iOS 端断网时仍能显示卫星列表，只需要在 UI 上标一下「离线」。
 */
data class TleListSnapshot(
    val records: List<TleRecord>,
    /** 数据获取时间（UTC 毫秒）；0 表示从未成功拉取 */
    val fetchedAtMs: Long,
    val fromCache: Boolean,
    /** 非空表示本次拉取失败 */
    val error: String?,
)

/**
 * iOS / Swift 侧的**唯一入口**。
 *
 * 设计原则：只暴露 Swift 能直接映射的类型（String / Double / Int / Long / Bool，
 * 以及本文件里的三个 data class），不暴露 Kotlin 的可空包装类型、`Array`、
 * 异常或协程——这样 Swift 侧不需要理解 Kotlin 运行时细节。
 *
 * 所有方法都是同步的纯计算（单颗卫星 48 小时搜索约 576 次传播），
 * iOS 侧建议放到 `Task.detached` / 后台队列，避免阻塞主线程。
 */
object HamKitApi {

    /**
     * 内核版本号。
     *
     * iOS 壳工程在「关于」里展示它，排查问题时可以快速确认 Swift 侧链接的
     * framework 与仓库里的 `commonMain` 是不是同一版。
     */
    fun version(): String = HamKitCore.VERSION

    /** 网络实现由平台注入（iOS: URLSession；Android: OkHttp/JVM）。 */
    private var tleRepository: TleRepository? = null

    /** 保留一份供非 TLE 数据源（SatNOGS 等）复用。 */
    private var httpTransport: HttpTransport? = null

    /**
     * 安装平台的 HTTP 实现。必须在 [fetchTles] 之前调用一次。
     *
     * 之所以用「注入」而不是 `expect/actual`：iOS 的 `URLSession` 由 Swift 侧实现更稳
     * （TLS / ATS / 代理策略都走系统默认行为），也避免把 cinterop 细节带进 commonMain。
     */
    fun installHttpTransport(transport: HttpTransport) {
        httpTransport = transport
        tleRepository = TleRepository(TleClient(transport))
    }

    /**
     * 拉取 SatNOGS DB 全部转发器（含 active/inactive）。
     * 与 Android `TransmitterDataSource` 同源同解析规则。
     */
    fun fetchTransmitters(callback: (TransmitterSnapshot) -> Unit) {
        val transport = httpTransport
        if (transport == null) {
            callback(TransmitterSnapshot(emptyList(), "未安装网络实现"))
            return
        }
        SatnogsTransmitters.fetch(transport, callback)
    }

    /**
     * 拉取 AMSAT 状态摘要（过去 24 小时，每颗卫星一条代表状态）。
     * 与 Android `AmsatStatusApiService.fetchStatusSummaries` 同源。
     */
    fun fetchAmsatStatus(callback: (AmsatStatusSnapshot) -> Unit) {
        val transport = httpTransport
        if (transport == null) {
            callback(AmsatStatusSnapshot(emptyList(), "未安装网络实现"))
            return
        }
        AmsatStatus.fetchSummary(transport, 24, callback)
    }

    /**
     * 某颗卫星的 AMSAT 状态名（用于把 summary 的 name 映射回 NORAD）；
     * 目录中没有该星时返回空串。
     */
    fun amsatStatusName(norad: Int): String =
        com.example.hamkit.data.satellite.SatelliteCatalog.AMSAT_STATUS_NAME_BY_CATALOG_NUMBER[norad].orEmpty()

    /**
     * 拉取 TLE 列表（默认带 6 小时内存缓存）。
     *
     * @param group CelesTrak 分组名：`amateur` / `satnogs` / `active` / `weather`，
     *              未知值回落到 `amateur`
     * @param forceRefresh true 时忽略缓存强制刷新
     * @param nowMs 当前时间（UTC 毫秒，由调用方提供，用于判断缓存是否过期）
     */
    fun fetchTles(
        group: String,
        forceRefresh: Boolean,
        nowMs: Long,
        callback: (TleListSnapshot) -> Unit,
    ) {
        val repository = tleRepository
        if (repository == null) {
            callback(
                TleListSnapshot(
                    records = emptyList(),
                    fetchedAtMs = 0L,
                    fromCache = false,
                    error = "未安装网络实现：请先调用 installHttpTransport",
                )
            )
            return
        }
        repository.load(TleGroup.fromWireName(group), forceRefresh, nowMs) { snapshot ->
            callback(
                TleListSnapshot(
                    records = snapshot.records,
                    fetchedAtMs = snapshot.fetchedAtMs,
                    fromCache = snapshot.fromCache,
                    error = snapshot.error,
                )
            )
        }
    }

    /**
     * 计算给定经纬度的 CQ / ITU 分区与 Maidenhead 网格。
     *
     * 标注 `@Throws` 是为了让 Swift 侧得到 `throws` 方法：Kotlin/Native 的异常
     * 若不声明 `@Throws`，跨语言调用会直接终止进程而不是抛出。
     *
     * @throws IllegalArgumentException 纬度不在 -90..90 或经度不在 -180..180
     */
    @Throws(IllegalArgumentException::class)
    fun zone(latitude: Double, longitude: Double): ZoneSnapshot {
        val info = ZoneResolver.resolve(latitude, longitude)
        return ZoneSnapshot(
            grid = info.maidenhead,
            cqZone = info.cqZone ?: 0,
            ituZone = info.ituZone ?: 0,
        )
    }

    /**
     * 计算卫星在 [atMs] 时刻的位置。
     *
     * @param tle 三行 TLE（名称行 + line1 + line2）
     * @return TLE 非法或轨道计算失败（再入 / 数值越界）时返回 null
     */
    fun satellitePosition(
        tle: List<String>,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        atMs: Long,
    ): PositionSnapshot? {
        val elements = parseTleOrNullForSwift(tle) ?: return null
        val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
        return propagator.getPosition(atMs)?.toSnapshotForSwift()
    }

    /**
     * 计算 [fromMs, toMs] 内的等间隔轨迹采样，供 iOS 雷达图绘制历史/未来轨迹。
     *
     * 采样点包含窗口两端；单次窗口最多 2000 个点，超出返回空列表，
     * 避免误传大窗口把主线程/电量拖垮。
     *
     * @return 按时间升序的采样点；TLE 非法或参数不合法时为空列表
     */
    fun satelliteTrack(
        tle: List<String>,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        fromMs: Long,
        toMs: Long,
        stepSeconds: Int,
    ): List<PositionSnapshot> {
        val elements = parseTleOrNullForSwift(tle) ?: return emptyList()
        if (stepSeconds <= 0 || toMs <= fromMs) return emptyList()
        val stepMs = stepSeconds * 1000L
        if ((toMs - fromMs) / stepMs > 2_000L) return emptyList()

        val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
        val result = ArrayList<PositionSnapshot>()
        var timeMs = fromMs
        while (timeMs <= toMs) {
            propagator.getPosition(timeMs)?.let { result.add(it.toSnapshotForSwift()) }
            timeMs += stepMs
        }
        return result
    }

    /**
     * 计算 [fromMs] 之后 [searchHours] 小时内的下一次过境。
     *
     * @param tle 三行 TLE（名称行 + line1 + line2）
     * @return TLE 非法或窗口内没有完整过境时返回 null
     */
    fun nextPass(
        tle: List<String>,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        fromMs: Long,
        searchHours: Int,
    ): PassSnapshot? = nextPasses(tle, latitude, longitude, altitudeM, fromMs, searchHours, 1).firstOrNull()

    /**
     * 计算 [fromMs] 之后 [searchHours] 小时内的前 [maxCount] 次过境。
     *
     * 上一次过境的 LOS 之后 1 分钟开始找下一次，避免同一次过境被重复计入。
     *
     * @return 按 AOS 升序的过境列表；TLE 非法或参数不合法时为空列表
     */
    fun nextPasses(
        tle: List<String>,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        fromMs: Long,
        searchHours: Int,
        maxCount: Int,
    ): List<PassSnapshot> {
        val elements = parseTleOrNullForSwift(tle) ?: return emptyList()
        if (searchHours <= 0 || maxCount <= 0) return emptyList()
        val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
        val toMs = fromMs + searchHours * 3_600_000L

        val result = ArrayList<PassSnapshot>(maxCount)
        var cursor = fromMs
        while (result.size < maxCount && cursor < toMs) {
            val pass = PassFinder.nextPass(propagator, cursor, toMs) ?: break
            result.add(
                PassSnapshot(
                    aosMs = pass.aosMs,
                    losMs = pass.losMs,
                    maxElevationDeg = pass.maxElevationDeg,
                    maxElevationMs = pass.maxElevationMs,
                    aosAzimuthDeg = pass.aosAzimuthDeg,
                    losAzimuthDeg = pass.losAzimuthDeg,
                    durationSeconds = pass.durationSeconds,
                    isCurrentlyVisible = pass.isCurrentlyVisible,
                )
            )
            // 防御：若传播器给出「零长度」过境，cursor 不前进会死循环
            if (pass.losMs <= cursor) break
            cursor = pass.losMs + 60_000L
        }
        return result
    }

    /**
     * 判断 TLE 是否可解析（用于设置页校验用户粘贴的 TLE）。
     */
    fun isValidTle(tle: List<String>): Boolean = parseTleOrNullForSwift(tle) != null

    /**
     * 对多颗卫星分别预测下一次过境（含当前在境），在境优先、按 AOS 升序返回。
     *
     * 与 Android 端 `SatellitePredictor.predictUpcomingPasses` 语义一致：
     * 每颗卫星最多取 1 次过境，isCurrentlyVisible 优先，其次按 AOS 排序。
     *
     * @param tles 卫星记录列表（名称 + 两行 TLE）
     * @param maxCount 最多返回多少颗（如首页 top5 传 5）
     */
    fun nextPassesAcrossSatellites(
        tles: List<TleRecord>,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        fromMs: Long,
        searchHours: Int = 48,
        maxCount: Int = 5,
    ): List<SatelliteNextPass> {
        if (tles.isEmpty() || maxCount <= 0) return emptyList()
        val toMs = fromMs + searchHours * 3_600_000L
        return tles.asSequence()
            .map { record ->
                val elements = parseTleOrNullForSwift(listOf(record.name, record.line1, record.line2))
                    ?: return@map null
                // 几何可达性预检（对齐 Android SatellitePredictor）：
                // 远地点+倾角覆盖不到观测者纬度 → 本站永不可见，跳过；
                // meanMotion≈0 的已衰变/无效 TLE 也在此过滤。
                // 全量 1.6 万颗时能把无效 SGP4 计算砍掉大半。
                if (!PassFinder.isGeoPeriod(elements.meanMotion) &&
                    !PassFinder.willBeSeenAt(
                        elements.meanMotion,
                        elements.eccentricity,
                        elements.inclinationDeg,
                        latitude,
                    )
                ) {
                    return@map null
                }
                val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
                val pass = PassFinder.nextPass(propagator, fromMs, toMs)
                    ?: return@map null
                if (pass.aosMs >= toMs) return@map null
                SatelliteNextPass(
                    name = record.name.trim().ifEmpty { record.norad.toString() },
                    norad = record.norad,
                    pass = PassSnapshot(
                        aosMs = pass.aosMs,
                        losMs = pass.losMs,
                        maxElevationDeg = pass.maxElevationDeg,
                        maxElevationMs = pass.maxElevationMs,
                        aosAzimuthDeg = pass.aosAzimuthDeg,
                        losAzimuthDeg = pass.losAzimuthDeg,
                        durationSeconds = pass.durationSeconds,
                        isCurrentlyVisible = pass.isCurrentlyVisible,
                    ),
                )
            }
            .filterNotNull()
            .sortedWith(
                compareByDescending<SatelliteNextPass> { it.pass.isCurrentlyVisible }
                    .thenBy { it.pass.aosMs }
            )
            .take(maxCount)
            .toList()
    }

    // ════════════════════════════════════════════════════════════════════
    // 卫星分类（逻辑从 Android SatelliteCategory.kt 移植；持久化在 Swift 侧）
    //
    // 边界类型约定：Swift 侧不传 Kotlin 集合类型，配置快照以 JSON 字符串
    // 往返（kotlinx.serialization 在 Android/JVM 可用；commonMain 无该依赖，
    // 这里手写轻量 JSON 转义，schema 与 iOS Codable 端一致）。
    // ════════════════════════════════════════════════════════════════════

    /**
     * 新建分类。
     *
     * @param name 分类名称（空白时返回原 JSON）
     * @param id 新分类 id；传空串时由内核生成
     * @return 变换后的配置 JSON
     */
    fun categoryAdd(configJson: String, name: String, id: String): String =
        withConfig(configJson) { it.addCategory(name, resolveCategoryId(id)).first }

    /** 重命名分类。 */
    fun categoryRename(configJson: String, categoryId: String, newName: String): String =
        withConfig(configJson) { it.renameCategory(categoryId, newName) }

    /** 删除分类（内置分类不可删除）。 */
    fun categoryRemove(configJson: String, categoryId: String): String =
        withConfig(configJson) { it.removeCategory(categoryId) }

    /** 切换某颗卫星在某个分类中的归属。 */
    fun categoryToggleAssignment(configJson: String, catalogNumber: Int, categoryId: String): String =
        withConfig(configJson) { it.toggleAssignment(catalogNumber, categoryId) }

    /** 切换某颗卫星的过境提醒开关。 */
    fun categoryToggleReminder(configJson: String, catalogNumber: Int): String =
        withConfig(configJson) { it.toggleReminder(catalogNumber) }

    /** 某颗卫星所属的分类 id 集合（JSON 字符串数组）。 */
    fun categoryIdsOf(configJson: String, catalogNumber: Int): String =
        jsonArrayString(parseCategoryConfig(configJson).categoryIdsOf(catalogNumber).toList())

    /** 某个分类下的卫星数量。 */
    fun categorySatelliteCount(configJson: String, categoryId: String): Int =
        parseCategoryConfig(configJson).satelliteCountOf(categoryId)

    /** 分类配置快照转 JSON（iOS 持久化时直接存这个字符串）。 */
    fun categoryConfigToJson(configJson: String): String = withConfig(configJson) { it }.let { configJson }

    /**
     * 卫星工作模式（逗号分隔，如 "FM,SSTV"）；未知卫星返回空串。
     *
     * 数据来自共享目录 [com.example.hamkit.data.satellite.SatelliteCatalog]，
     * 与 Android 列表卡片上的模式 chip 同源。
     */
    fun satelliteModes(norad: Int): String =
        com.example.hamkit.data.satellite.SatelliteCatalog.MODES_BY_CATALOG_NUMBER[norad]
            .orEmpty()
            .joinToString(",")

    private fun resolveCategoryId(id: String): String =
        if (id.isBlank()) {
            "user-" + TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds.toString(36) + "-" +
                Random.nextInt(0x10000, 0x100000).toString(36)
        } else {
            id
        }

    private inline fun withConfig(configJson: String, block: (SatelliteCategoryConfig) -> SatelliteCategoryConfig): String {
        val config = parseCategoryConfig(configJson)
        return encodeCategoryConfig(block(config))
    }

    internal fun parseCategoryConfig(json: String): SatelliteCategoryConfig {
        if (json.isBlank()) return SatelliteCategoryConfig()
        return try {
            CategoryConfigCodec.decode(json)
        } catch (_: Exception) {
            SatelliteCategoryConfig()
        }
    }

    internal fun encodeCategoryConfig(config: SatelliteCategoryConfig): String =
        CategoryConfigCodec.encode(config)

    /** 某颗卫星的分类 id 集合（JSON 字符串数组）。 */
    private fun jsonArrayString(ids: List<String>): String =
        ids.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
}

/**
 * 分类配置的轻量 JSON 编解码（commonMain 无 kotlinx-serialization，
 * schema 与 iOS 侧 `SatelliteCategoryManager` 的 Codable 约定一致）。
 */
internal object CategoryConfigCodec {

    private const val KEY_CATEGORIES = "categories"
    private const val KEY_MEMBERSHIP = "membership"
    private const val KEY_REMINDER_FLAGS = "reminderFlags"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_SORT_ORDER = "sortOrder"

    fun encode(config: SatelliteCategoryConfig): String {
        val parts = mutableListOf<String>()
        val categories = config.categories.joinToString(",") { category ->
            buildString {
                append("{\"").append(KEY_ID).append("\":").append(quote(category.id))
                append(",\"").append(KEY_NAME).append("\":").append(quote(category.name))
                append(",\"").append(KEY_SORT_ORDER).append("\":").append(category.sortOrder)
                append("}")
            }
        }
        parts.appendPair(KEY_CATEGORIES, "[$categories]")
        val membership = config.membership.entries.joinToString(",") { (norad, ids) ->
            val idArray = ids.joinToString(",") { id -> quote(id) }
            "\"$norad\":[$idArray]"
        }
        parts.appendPair(KEY_MEMBERSHIP, "{$membership}")
        val flags = config.reminderFlags.joinToString(",") { it.toString() }
        parts.appendPair(KEY_REMINDER_FLAGS, "[$flags]")
        return "{" + parts.joinToString(",") + "}"
    }

    fun decode(json: String): SatelliteCategoryConfig {
        val root = MiniJson.parseObject(json)
        val categories = (root[KEY_CATEGORIES] as? List<*>).orEmpty().mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val id = (obj[KEY_ID] as? String)?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            SatelliteCategory(
                id = id,
                name = (obj[KEY_NAME] as? String).orEmpty(),
                sortOrder = (obj[KEY_SORT_ORDER] as? Double)?.toInt() ?: 0,
            )
        }
        val validIds = categories.mapTo(HashSet()) { it.id }
        val membership = ((root[KEY_MEMBERSHIP] as? Map<*, *>).orEmpty()).mapNotNull { (key, value) ->
            val norad = (key as? String)?.toIntOrNull() ?: return@mapNotNull null
            if (norad <= 0) return@mapNotNull null
            val ids = (value as? List<*>).orEmpty()
                .mapNotNull { it as? String }
                .filter { it.isNotBlank() }
            if (ids.isEmpty()) return@mapNotNull null
            val filtered = if (validIds.isEmpty()) ids.toSet() else ids.toSet().intersect(validIds)
            if (filtered.isEmpty()) null else norad to filtered
        }.toMap()
        val reminderFlags = (root[KEY_REMINDER_FLAGS] as? List<*>).orEmpty()
            .mapNotNull { (it as? Double)?.toInt() }
            .filter { it > 0 }
            .toSet()
        return SatelliteCategoryConfig(
            categories = categories,
            membership = membership,
            reminderFlags = reminderFlags,
        )
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun MutableList<String>.appendPair(key: String, value: String) {
        add("\"" + key + "\":" + value)
    }
}

/** 极小 JSON 解析器：只解析本 codec 产出的结构（对象/数组/字符串/数字）。 */
internal object MiniJson {

    fun parseObject(json: String): Map<String, Any?> {
        val parser = Parser(json)
        parser.skipWs()
        return parser.parseObject()
    }

    /** 解析顶层 JSON 数组（SatNOGS 等 API 的根节点是数组）。 */
    fun parseArrayRoot(json: String): List<Any?> {
        val parser = Parser(json)
        parser.skipWs()
        return parser.parseArrayRoot()
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }

        fun parseArrayRoot(): List<Any?> = parseArray()

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                result[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> { pos++ }
                    '}' -> { pos++; return result }
                    else -> fail("对象分隔符")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return result }
            while (true) {
                skipWs()
                result.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { pos++ }
                    ']' -> { pos++; return result }
                    else -> fail("数组分隔符")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> {
                    if (c == '-') pos++
                    val start = pos
                    while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) pos++
                    if (pos == start) fail("数字")
                    text.substring(start, pos).toDoubleOrNull() ?: fail("数字")
                }
            }
        }

        private fun expectWord(word: String) {
            if (pos + word.length > text.length || text.substring(pos, pos + word.length) != word) fail(word)
            pos += word.length
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'u' -> {
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> fail("转义字符")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char {
            if (pos >= text.length) fail("输入结束")
            return text[pos]
        }

        private fun expect(c: Char) {
            if (pos >= text.length || text[pos] != c) fail("'$c'")
            pos++
        }

        private fun fail(expected: String): Nothing =
            throw IllegalArgumentException("JSON 解析失败（位置 $pos，期望 $expected）")
    }
}

private fun PropagatedPosition.toSnapshotForSwift(): PositionSnapshot = PositionSnapshot(
    timeMs = timeMs,
    azimuthDeg = azimuthRad * 180.0 / PI,
    elevationDeg = elevationRad * 180.0 / PI,
    rangeKm = rangeKm,
    rangeRateMps = rangeRateKmPerSec * 1000.0,
    subLatitudeDeg = latitudeRad * 180.0 / PI,
    subLongitudeDeg = longitudeRad * 180.0 / PI,
    altitudeKm = altitudeKm,
    isAboveHorizon = isAboveHorizon,
)

/** 内部工具：把可能非法的三行 TLE 转成元素集，失败返回 null。 */
private fun parseTleOrNullForSwift(tle: List<String>): TleElements? = try {
    if (tle.size < 3) null else TleElements.fromThreeLines(arrayOf(tle[0], tle[1], tle[2]))
} catch (_: IllegalArgumentException) {
    null
}
