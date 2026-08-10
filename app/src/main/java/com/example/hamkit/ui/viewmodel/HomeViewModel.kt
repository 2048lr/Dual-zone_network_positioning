package com.example.hamkit.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.hamkit.radioApp
import com.example.hamkit.ui.screen.home.HomeUiState
import com.example.hamkit.ui.screen.home.getAppVersion
import com.example.hamkit.ui.util.LatestVersionInfo

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            // 更新检查统一由 SettingsViewModel.checkUpdateNow 处理（含节流），
            // 此处不再调用 checkNewVersion 以免重复消耗 GitHub API 限额。
        }
    }

    private fun buildState(): HomeUiState {
        val appVersion = getAppVersion(radioApp)

        return HomeUiState(
            checkUpdateEnabled = radioApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("check_update", true),
            latestVersionInfo = LatestVersionInfo(),
            currentAppVersionCode = appVersion.versionCode,
        )
    }
}
