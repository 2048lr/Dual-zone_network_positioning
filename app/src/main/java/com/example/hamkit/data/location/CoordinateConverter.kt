package com.example.hamkit.data.location

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换工具（WGS84 ↔ GCJ02 ↔ BD09）。
 *
 * 国内地图 SDK（高德/腾讯/Google 国内）使用 GCJ02（火星坐标系），
 * GPS / FusedLocationProvider / LocationManager 返回 WGS84，
 * 百度地图使用 BD09。
 *
 * 仅纯数学计算，微秒级开销。境外坐标直接原样返回（不在国内偏移范围）。
 */
object CoordinateConverter {

    private const val A = 6378245.0 // 长半轴
    private const val EE = 0.00669342162296594323 // 偏心率平方
    private const val X_PI = Math.PI * 3000.0 / 180.0

    /**
     * 判断坐标是否在中国境内（粗略判定，含港澳台周边）。
     * 境外坐标不适用 GCJ02 偏移，直接原样返回。
     */
    fun outOfChina(lng: Double, lat: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * X_PI) + 20.0 * sin(2.0 * x * X_PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * X_PI) + 40.0 * sin(y / 3.0 * X_PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * X_PI) + 320.0 * sin(y * X_PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * X_PI) + 20.0 * sin(2.0 * x * X_PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * X_PI) + 40.0 * sin(x / 3.0 * X_PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * X_PI) + 300.0 * sin(x / 30.0 * X_PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS84 → GCJ02（高德/腾讯/Google 国内）。境外坐标原样返回。 */
    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
        if (outOfChina(wgsLng, wgsLat)) return wgsLat to wgsLng
        var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        var dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return (wgsLat + dLat) to (wgsLng + dLng)
    }

    /** GCJ02 → WGS84（粗略逆向，足够地图展示精度）。境外坐标原样返回。 */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (outOfChina(gcjLng, gcjLat)) return gcjLat to gcjLng
        val (adjLat, adjLng) = wgs84ToGcj02(gcjLat, gcjLng)
        return (gcjLat - (adjLat - gcjLat)) to (gcjLng - (adjLng - gcjLng))
    }

    /** GCJ02 → BD09（百度）。 */
    fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        val z = sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * sin(gcjLat * X_PI)
        val theta = Math.atan2(gcjLat, gcjLng) + 0.000003 * cos(gcjLng * X_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return bdLat to bdLng
    }

    /** BD09 → GCJ02（百度 → 高德/腾讯）。 */
    fun bd09ToGcj02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = Math.atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLng = z * cos(theta)
        val gcjLat = z * sin(theta)
        return gcjLat to gcjLng
    }
}
