package com.example.hamkit.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.Navigator
import com.example.hamkit.ui.navigation3.Route
import com.example.hamkit.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val settingsViewModel = appViewModel<SettingsViewModel>()
    val mainViewModel = LocalMainViewModel.current
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    // MainViewModel 的状态是 Compose State<T>（非 StateFlow），直接用 by 委托即可
    val reminderSettings by mainViewModel.reminderSettings
    val reminderItems by mainViewModel.reminderItems

    LifecycleResumeEffect(Unit) {
        settingsViewModel.refresh()
        onPauseOrDispose { }
    }

    val businessState = SettingsBusinessState(
        reminderSettings = reminderSettings,
        reminderItems = reminderItems,
    )

    val actions = SettingsScreenActions(
        onSetCheckUpdate = settingsViewModel::setCheckUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onSetUiModeIndex = { index ->
            settingsViewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenAbout = { navigator.push(Route.About) },
        onUpdateReminderSettings = mainViewModel::updateReminderSettings,
        onOpenReminderList = { navigator.push(Route.ReminderList) },
        onCheckUpdateNow = settingsViewModel::checkUpdateNow,
        onDownloadAndInstall = settingsViewModel::downloadAndInstall,
        onClearUpdateResult = settingsViewModel::clearUpdateResult,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, businessState, actions, bottomInnerPadding)
        UiMode.Material -> SettingPagerMaterial(uiState, businessState, actions, bottomInnerPadding)
    }
}
