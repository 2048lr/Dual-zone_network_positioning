package com.example.radioarealocator.data.satellite

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatellitePredictCacheStoreTest {

    @Test
    fun `future prediction timestamp is not fresh`() {
        val cache = CachedPrediction(
            satellites = emptyList(),
            latitude = 0.0,
            longitude = 0.0,
            predictedAt = Instant.parse("2026-08-07T00:00:00Z"),
            tleFingerprint = ""
        )

        assertFalse(cache.isFresh(Instant.parse("2026-08-06T00:00:00Z")))
    }

    @Test
    fun `recent prediction timestamp is fresh`() {
        val cache = CachedPrediction(
            satellites = emptyList(),
            latitude = 0.0,
            longitude = 0.0,
            predictedAt = Instant.parse("2026-08-06T00:00:00Z"),
            tleFingerprint = ""
        )

        assertTrue(cache.isFresh(Instant.parse("2026-08-06T01:00:00Z")))
    }
}
