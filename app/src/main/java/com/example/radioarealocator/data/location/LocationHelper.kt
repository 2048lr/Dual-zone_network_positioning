package com.example.radioarealocator.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 纯 Android Framework [LocationManager] 定位封装。
 *
 * 不依赖 Google Play Services（GMS），避免无 GMS 设备触发"请启用谷歌服务"弹窗。
 * NETWORK_PROVIDER 优先（室内 Wi-Fi/基站毫秒级），GPS 兜底（室外高精度）。
 */
class LocationHelper(private val context: Context) {

    private val locationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getCurrentLocation(): Location {
        if (!hasPermission()) {
            throw SecurityException("缺少定位权限")
        }

        return try {
            withTimeout(10_000) { requestLocationManagerLocation() }
        } catch (e: TimeoutCancellationException) {
            throw Exception("定位超时，请检查手机是否开启 GPS/网络定位")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception(
                "无法获取定位，请检查手机是否开启 GPS/网络定位，或检查是否禁止了本应用定位权限。详情: ${e.message ?: "失败"}"
            )
        }
    }

    /**
     * 持续位置监听（Flow 形式）。
     *
     * 基于 [LocationManager.requestLocationUpdates] 注册持续回调，
     * NETWORK + GPS 双 provider 并行监听，取每次变化的位置上报。
     * Flow 被取消时自动移除回调，避免泄漏。
     *
     * @param intervalMs 期望的位置上报间隔（毫秒）
     * @param minDistanceM 最小位移阈值（米），小于此距离的变化不上报
     */
    fun locationUpdates(
        intervalMs: Long = 5_000L,
        minDistanceM: Float = 5f
    ): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("缺少定位权限"))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        // NETWORK + GPS 并行监听：室内 NETWORK 先出结果，室外 GPS 高精度
        val providers = listOfNotNull(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        ).filter { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (e: Exception) {
                false
            }
        }

        if (providers.isEmpty()) {
            close(Exception("系统未开启任何定位源"))
            return@callbackFlow
        }

        try {
            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDistanceM,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: SecurityException) {
                    // 忽略单个 provider 的权限异常，继续下一个
                }
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
                // 忽略移除回调时的异常
            }
        }
    }

    private suspend fun requestLocationManagerLocation(): Location =
        suspendCancellableCoroutine { continuation ->
            // 协程体与 listener 回调可能在不同线程执行，用 AtomicBoolean 防二次 resume
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!resumed.compareAndSet(false, true)) return
                    removeListener(this)
                    continuation.resume(location)
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            continuation.invokeOnCancellation {
                removeListener(listener)
            }

            try {
                // NETWORK 优先（室内 Wi-Fi/基站毫秒级返回），PASSIVE 次之，GPS 最后（冷启动慢）
                val providers = listOfNotNull(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER
                ).filter { provider ->
                    try {
                        locationManager.isProviderEnabled(provider)
                    } catch (e: Exception) {
                        false
                    }
                }

                if (providers.isEmpty()) {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resumeWithException(Exception("系统未开启任何定位源"))
                    }
                    return@suspendCancellableCoroutine
                }

                // 多源 lastKnown 聚合：取时间戳最新的缓存，秒回避免触发真实定位
                val bestCached = providers
                    .mapNotNull { p ->
                        try {
                            locationManager.getLastKnownLocation(p)
                        } catch (e: SecurityException) {
                            null
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .maxByOrNull { it.time }

                if (bestCached != null) {
                    if (resumed.compareAndSet(false, true)) {
                        continuation.resume(bestCached)
                    }
                    return@suspendCancellableCoroutine
                }

                // 无缓存：并行请求所有 provider（NETWORK 通常 1-2s 先出结果）
                for (provider in providers) {
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            500L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    } catch (e: SecurityException) {
                        // 忽略单个 provider 的权限异常，继续下一个
                    } catch (e: Exception) {
                        // 忽略单个 provider 异常
                    }
                }
            } catch (e: SecurityException) {
                if (resumed.compareAndSet(false, true)) {
                    removeListener(listener)
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun removeListener(listener: LocationListener) {
        try {
            locationManager.removeUpdates(listener)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * 根据经纬度反向地理编码获取地址信息。
     * 在后台线程执行，返回格式化地址字符串；失败或不可用时返回空字符串。
     */
    suspend fun getAddress(latitude: Double, longitude: Double): String {
        return try {
            withTimeout(3_000) {
                if (!Geocoder.isPresent()) {
                    return@withTimeout ""
                }

                val geocoder = Geocoder(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    continuation.resume(formatAddress(addresses.firstOrNull()))
                                }

                                override fun onError(errorMessage: String?) {
                                    continuation.resume("")
                                }
                            }
                        )
                        // Geocoder 异步回调无显式取消 API，注册取消回调明确意图
                        continuation.invokeOnCancellation { /* 迟到回调由 CancellableContinuation 静默忽略 */ }
                    }
                } else {
                    // pre-TIRAMISU 的同步阻塞调用，切到 IO 调度器避免阻塞主线程
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        formatAddress(addresses?.firstOrNull())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 仅返回市级地址（locality 优先，缺失时回退到 adminArea）。
     * 失败或不可用时返回空字符串。
     */
    suspend fun getCityAddress(latitude: Double, longitude: Double): String {
        return try {
            withTimeout(3_000) {
                if (!Geocoder.isPresent()) {
                    return@withTimeout ""
                }

                val geocoder = Geocoder(context)
                val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    continuation.resume(addresses.firstOrNull())
                                }

                                override fun onError(errorMessage: String?) {
                                    continuation.resume(null)
                                }
                            }
                        )
                        continuation.invokeOnCancellation { /* 迟到回调由 CancellableContinuation 静默忽略 */ }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                    }
                }

                address?.locality?.takeIf { it.isNotBlank() }
                    ?: address?.adminArea?.takeIf { it.isNotBlank() }
                    ?: ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatAddress(address: Address?): String {
        if (address == null) return ""
        val parts = listOfNotNull(
            address.adminArea,
            address.locality,
            address.subLocality,
            address.thoroughfare
        ).filter { it.isNotBlank() }
        return if (parts.isNotEmpty()) {
            parts.joinToString(" ")
        } else {
            address.getAddressLine(0) ?: ""
        }
    }
}
