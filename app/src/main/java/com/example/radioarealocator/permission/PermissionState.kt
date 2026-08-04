package com.example.radioarealocator.permission

data class PermissionState(
    val location: Boolean = false,
    val notification: Boolean = false,
    val exactAlarm: Boolean = true,
    val microphone: Boolean = false,
) {
    val requiredGranted: Boolean
        get() = location
}
