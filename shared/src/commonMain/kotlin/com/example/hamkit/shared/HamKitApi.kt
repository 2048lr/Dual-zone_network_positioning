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

    /**
     * 安装平台的 HTTP 实现。必须在 [fetchTles] 之前调用一次。
     *
     * 之所以用「注入」而不是 `expect/actual`：iOS 的 `URLSession` 由 Swift 侧实现更稳
     * （TLS / ATS / 代理策略都走系统默认行为），也避免把 cinterop 细节带进 commonMain。
     */
    fun installHttpTransport(transport: HttpTransport) {
        tleRepository = TleRepository(TleClient(transport))
    }

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
        val elements = parseTleOrNull(tle) ?: return null
        val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
        return propagator.getPosition(atMs)?.toSnapshot()
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
        val elements = parseTleOrNull(tle) ?: return emptyList()
        if (stepSeconds <= 0 || toMs <= fromMs) return emptyList()
        val stepMs = stepSeconds * 1000L
        if ((toMs - fromMs) / stepMs > 2_000L) return emptyList()

        val propagator = SatellitePropagator(elements, latitude, longitude, altitudeM)
        val result = ArrayList<PositionSnapshot>()
        var timeMs = fromMs
        while (timeMs <= toMs) {
            propagator.getPosition(timeMs)?.let { result.add(it.toSnapshot()) }
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
        val elements = parseTleOrNull(tle) ?: return emptyList()
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
    fun isValidTle(tle: List<String>): Boolean = parseTleOrNull(tle) != null

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
                val elements = parseTleOrNull(listOf(record.name, record.line1, record.line2))
                    ?: return@map null
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

    /** 内部工具：把 SGP4 的弧度结果转成 Swift 友好的度/km 快照。 */
    private fun PropagatedPosition.toSnapshot(): PositionSnapshot = PositionSnapshot(
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
    private fun parseTleOrNull(tle: List<String>): TleElements? = try {
        if (tle.size < 3) null else TleElements.fromThreeLines(arrayOf(tle[0], tle[1], tle[2]))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
