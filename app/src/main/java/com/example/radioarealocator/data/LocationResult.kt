package com.example.radioarealocator.data

import androidx.compose.runtime.Immutable
import java.time.Instant

@Immutable
data class LocationResult(
    val latitude: Double,
    val longitude: Double,
    val cqZone: Int?,
    val ituZone: Int?,
    val maidenhead: String,
    val address: String = "",
    val timestamp: Instant = Instant.now()
)
