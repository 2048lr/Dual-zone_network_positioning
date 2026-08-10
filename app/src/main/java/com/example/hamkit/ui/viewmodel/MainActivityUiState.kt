package com.example.hamkit.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val uiMode: UiMode,
)
