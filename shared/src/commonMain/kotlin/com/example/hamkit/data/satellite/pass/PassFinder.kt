package com.example.hamkit.data.satellite.pass

import com.example.hamkit.data.satellite.predict.SatellitePropagator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.ln

/**
 * 一次过境的窗口信息（跨平台内核版本）。
 *
 * 与 Android 侧 `SatelliteInfo` 的区别：这里只有**纯数值 + 毫秒时间戳**，
 * 不携带 UI 文案、数据来源、AMSAT 状态等展示字段，因此可以在 iOS 上直接使用。
 *
 * 角度单位统一为度（UI 友好），时间统一为 UTC 毫秒。
 */
data class SatellitePassWindow(
    /** 过境开始时刻（UTC 毫秒）；当前已在境内时等于查询起点 */
    val aosMs: Long,
    /** 过境结束时刻（UTC 毫秒） */
    val losMs: Long,
    /** 过境最大仰角（度） */
    val maxElevationDeg: Double,
    /** 最大仰角时刻（UTC 毫秒） */
    val maxElevationMs: Long,
    /** 过境开始方位角（度，0=正北，顺时针） */
    val aosAzimuthDeg: Int,
    /** 过境结束方位角（度） */
    val losAzimuthDeg: Int,
    /** 查询起点时卫星是否已经在地平线以上（本次窗口是"剩余可见时间"） */
    val isCurrentlyVisible: Boolean,
) {
    /** 过境时长（秒） */
    val durationSeconds: Long
        get() = (losMs - aosMs) / 1000L
}

/**
 * 卫星过境搜索（SGP4/SDP4 之上的一层薄封装），Android / iOS 共用。
 *
 * 算法与 Android 端 `SatellitePredictor` 完全一致：
 * 1. 以 [SAMPLE_STEP_MINUTES] 为步长粗扫仰角，找 ±0 穿越点；
 * 2. 命中后用二分法把 AOS / LOS 精化到 [BISECTION_PRECISION_MS] 精度；
 * 3. 在过境窗口内以 1 分钟步长精化最大仰角，并取该时刻的方位角。
 *
 * 性能：单颗卫星 48 小时窗口约 576 次传播计算，纯 CPU、无分配热点，
 * 在手机上运行于后台线程即可；iOS 侧建议放在 `Task.detached` 或后台队列。
 */
object PassFinder {

    /** 仰角粗扫步长（分钟）：LEO 过境持续 10-20 分钟，5 分钟足以命中所有窗口。 */
    const val SAMPLE_STEP_MINUTES: Long = 5L

    /** GEO / 高轨卫星周期阈值（分钟）。周期超过此值的卫星按"恒定可见/永不可见"特判。 */
    const val GEO_PERIOD_THRESHOLD_MINUTES: Double = 1200.0

    /** 二分法精化 AOS/LOS 的时间精度（毫秒）。 */
    const val BISECTION_PRECISION_MS: Long = 10_000L

    /** 地球半径（km），与 Look4Sat / PREDICT 对齐。 */
    private const val EARTH_RADIUS_KM = 6378.137

    /**
     * 是否为 GEO / 深空轨道（周期 > [GEO_PERIOD_THRESHOLD_MINUTES]）。
     * 周期 = 1440 / meanMotion（meanMotion 单位：转/天）。
     */
    fun isGeoPeriod(meanMotion: Double): Boolean =
        meanMotion > 0.0 && 1440.0 / meanMotion > GEO_PERIOD_THRESHOLD_MINUTES

    /**
     * 判断卫星是否可能从地面站纬度可见（几何可达性预检）。
     *
     * 基于 Look4Sat 的 `willBeSeen` 算法：由平均运动估算远地点，
     * 检查轨道倾角覆盖范围能否到达观测者纬度。
     * meanMotion ≈ 0 视为已衰变 / 无效 TLE，直接返回 false。
     */
    fun willBeSeenAt(
        meanMotion: Double,
        eccentricity: Double,
        inclinationDeg: Double,
        observerLatDeg: Double,
    ): Boolean {
        if (meanMotion < 1e-8) return false
        val sma = 331.25 * exp(ln(1440.0 / meanMotion) * (2.0 / 3.0))
        val apogee = sma * (1.0 + eccentricity) - EARTH_RADIUS_KM
        var inclination = inclinationDeg
        if (inclination >= 90.0) inclination = 180.0 - inclination
        return acos(EARTH_RADIUS_KM / (apogee + EARTH_RADIUS_KM)) + inclination * PI / 180.0 >
            abs(observerLatDeg) * PI / 180.0
    }

    /**
     * 单次过境的合理时长上限（毫秒）。
     *
     * SGP4 在 TLE 严重过期 / 近再入时可能给出"恒定可见"的假象，导致 AOS-LOS
     * 跨满整个搜索窗口。以轨道周期的 1.5 倍作为上限把它筛掉。
     */
    fun maxPassDurationMs(meanMotion: Double): Long {
        if (meanMotion <= 0.0) return 3L * 60L * 60L * 1000L
        val periodMin = 1440.0 / meanMotion
        return ((periodMin * 1.5) * 60L * 1000L).toLong()
    }

    /**
     * 在 [[fromMs], [toMs]] 内寻找下一次过境（仰角由 ≤0 穿越到 >0）。
     *
     * 若查询起点卫星已在地平线以上，则返回**本次过境的剩余窗口**
     * （AOS 回退到 [fromMs]），保证「在境卫星」不会从列表里消失。
     *
     * @return 过境窗口；窗口内没有完整过境、或已出境但找不到 LOS 时返回 null
     */
    fun nextPass(
        propagator: SatellitePropagator,
        fromMs: Long,
        toMs: Long,
    ): SatellitePassWindow? {
        val stepMs = SAMPLE_STEP_MINUTES * 60_000L
        var prevEl = elevationDegAt(propagator, fromMs)

        // 已在境内：把当前过境的剩余部分作为结果
        if (prevEl > 0.0) {
            var losMs = -1L
            var t = fromMs + stepMs
            var maxEl = prevEl
            var maxElMs = fromMs
            while (t <= toMs) {
                val el = elevationDegAt(propagator, t)
                if (el > maxEl) {
                    maxEl = el
                    maxElMs = t
                }
                if (el <= 0.0) {
                    losMs = refineZeroCrossing(propagator, t - stepMs, t, crossingUp = false)
                    break
                }
                t += stepMs
            }
            // 整个窗口内都在境内（异常）→ 放弃，交由上层按 GEO 特判处理
            if (losMs < 0L) return null
            val (elevation, azimuth) = maxElevationAzimuth(propagator, maxElMs, fromMs, losMs)
            return SatellitePassWindow(
                aosMs = fromMs,
                losMs = losMs,
                maxElevationDeg = elevation,
                maxElevationMs = maxElMs,
                aosAzimuthDeg = azimuthAt(propagator, fromMs),
                losAzimuthDeg = azimuthAt(propagator, losMs),
                isCurrentlyVisible = true,
            )
        }

        // 常规搜索：仰角由 ≤0 穿越到 >0 = AOS
        var t = fromMs + stepMs
        while (t <= toMs) {
            val el = elevationDegAt(propagator, t)
            if (prevEl <= 0.0 && el > 0.0) {
                val aosMs = refineZeroCrossing(propagator, t - stepMs, t, crossingUp = true)
                val (losMs, maxEl, maxElMs) = findLosAfter(propagator, aosMs, toMs, stepMs) ?: return null
                val maxElevation = if (maxElMs == aosMs) {
                    maxEl
                } else {
                    maxElevationAzimuth(propagator, maxElMs, aosMs, losMs).first
                }
                return SatellitePassWindow(
                    aosMs = aosMs,
                    losMs = losMs,
                    maxElevationDeg = maxElevation,
                    maxElevationMs = maxElMs,
                    aosAzimuthDeg = azimuthAt(propagator, aosMs),
                    losAzimuthDeg = azimuthAt(propagator, losMs),
                    isCurrentlyVisible = false,
                )
            }
            prevEl = el
            t += stepMs
        }
        return null
    }

    /**
     * 在 [aosMs] 之后寻找 LOS（仰角由 >0 穿越到 ≤0），同时记录窗口内最大仰角。
     */
    private fun findLosAfter(
        propagator: SatellitePropagator,
        aosMs: Long,
        toMs: Long,
        stepMs: Long,
    ): Triple<Long, Double, Long>? {
        var t = aosMs + stepMs
        var prevEl = elevationDegAt(propagator, t - stepMs)
        var maxEl = prevEl
        var maxElMs = aosMs
        while (t <= toMs) {
            val el = elevationDegAt(propagator, t)
            if (el > maxEl) {
                maxEl = el
                maxElMs = t
            }
            if (prevEl > 0.0 && el <= 0.0) {
                val losMs = refineZeroCrossing(propagator, t - stepMs, t, crossingUp = false)
                return Triple(losMs, maxEl, maxElMs)
            }
            prevEl = el
            t += stepMs
        }
        return null
    }

    /**
     * 二分法精化仰角过零点：AOS 为上升过零，LOS 为下降过零。
     */
    private fun refineZeroCrossing(
        propagator: SatellitePropagator,
        loMs: Long,
        hiMs: Long,
        crossingUp: Boolean,
    ): Long {
        var lo = loMs
        var hi = hiMs
        while (hi - lo > BISECTION_PRECISION_MS) {
            val mid = (lo + hi) / 2
            val el = elevationDegAt(propagator, mid)
            if (crossingUp) {
                if (el > 0.0) hi = mid else lo = mid
            } else {
                if (el <= 0.0) hi = mid else lo = mid
            }
        }
        return if (crossingUp) hi else lo
    }

    /**
     * 在 [aosMs, losMs] 内以 1 分钟步长精化最大仰角及其方位角。
     *
     * 粗扫给出的 [maxElMs] 只到 5 分钟精度，这里在 ±5 分钟邻域内细化。
     */
    private fun maxElevationAzimuth(
        propagator: SatellitePropagator,
        maxElMs: Long,
        aosMs: Long,
        losMs: Long,
    ): Pair<Double, Int> {
        val fineStepMs = 60_000L
        val searchStart = maxOf(maxElMs - 5 * 60_000L, aosMs)
        val searchEnd = minOf(maxElMs + 5 * 60_000L, losMs)
        var bestEl = elevationDegAt(propagator, maxElMs)
        var bestMs = maxElMs
        var t = searchStart
        while (t <= searchEnd) {
            val el = elevationDegAt(propagator, t)
            if (el > bestEl) {
                bestEl = el
                bestMs = t
            }
            t += fineStepMs
        }
        val azimuth = propagator.getPosition(bestMs)?.let { toDegrees(it.azimuthRad).toInt() } ?: 0
        return bestEl to azimuth
    }

    /** 指定时刻的仰角（度）；传播失败时返回 -1.0（视为不可见）。 */
    private fun elevationDegAt(propagator: SatellitePropagator, timeMs: Long): Double {
        val pos = propagator.getPosition(timeMs) ?: return -1.0
        return toDegrees(pos.elevationRad)
    }

    /** 指定时刻的方位角（度，整数）。 */
    private fun azimuthAt(propagator: SatellitePropagator, timeMs: Long): Int {
        val pos = propagator.getPosition(timeMs) ?: return 0
        return toDegrees(pos.azimuthRad).toInt()
    }

    private fun toDegrees(rad: Double): Double = rad * 180.0 / PI
}
