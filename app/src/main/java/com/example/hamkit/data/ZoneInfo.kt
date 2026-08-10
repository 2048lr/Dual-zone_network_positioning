package com.example.hamkit.data

import androidx.compose.runtime.Immutable

@Immutable
data class ZoneInfo(
    val cqZone: Int?,
    val ituZone: Int?,
    val maidenhead: String
)
