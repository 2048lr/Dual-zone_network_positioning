package com.example.hamkit.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SettingsStore] 集成测试。
 *
 * 使用真实 SharedPreferences，覆盖读写一致性与默认值回归。
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreIntegrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPrefs() {
        // 每个测试前清空 SharedPreferences，避免相互污染
        context.getSharedPreferences("radio_area_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun lastLocation_defaultsToZero_whenNeverSet() {
        val store = SettingsStore(context)
        assertEquals(0.0, store.lastLatitude, 0.0)
        assertEquals(0.0, store.lastLongitude, 0.0)
        assert(!store.hasLastLocation())
    }

    @Test
    fun lastLocation_persistsAcrossStoreInstances() {
        // 模拟进程重启：先写入，再新建 Store 读取
        SettingsStore(context).lastLatitude = 39.9042
        SettingsStore(context).lastLongitude = 116.4074
        val restored = SettingsStore(context)
        assertEquals(39.9042, restored.lastLatitude, 0.0001)
        assertEquals(116.4074, restored.lastLongitude, 0.0001)
        assert(restored.hasLastLocation())
    }

    @Test
    fun dailyQuoteEpochDay_defaultsToMinusOne_whenNeverSet() {
        val store = SettingsStore(context)
        assertEquals(-1L, store.dailyQuoteEpochDay)
    }

    @Test
    fun dailyQuoteEpochDay_persistsAcrossStoreInstances() {
        SettingsStore(context).dailyQuoteEpochDay = 20000L
        val restored = SettingsStore(context)
        assertEquals(20000L, restored.dailyQuoteEpochDay)
    }

    @Test
    fun amsatStatusEnabled_defaultsToTrue_whenNeverSet() {
        val store = SettingsStore(context)
        assertEquals(true, store.amsatStatusEnabled)
    }

    @Test
    fun amsatStatusEnabled_persistsAcrossStoreInstances() {
        SettingsStore(context).amsatStatusEnabled = false
        val restored = SettingsStore(context)
        assertEquals(false, restored.amsatStatusEnabled)
    }
}
