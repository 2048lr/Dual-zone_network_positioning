package com.example.hamkit.ui.screen.colorpalette

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.example.hamkit.R
import com.example.hamkit.HamKitApplication
import com.example.hamkit.data.LandscapeImageStore
import com.example.hamkit.ui.LocalMainViewModel
import com.example.hamkit.ui.LocalUiMode
import com.example.hamkit.ui.UiMode
import com.example.hamkit.ui.appViewModel
import com.example.hamkit.ui.navigation3.LocalNavigator
import com.example.hamkit.ui.theme.ColorMode
import com.example.hamkit.ui.viewmodel.SettingsViewModel

@Composable
fun ColorPaletteScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val viewModel = appViewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imageStore = remember { LandscapeImageStore(context.applicationContext) }
    val mainViewModel = LocalMainViewModel.current
    val currentPaletteStyle = try {
        PaletteStyle.valueOf(uiState.colorStyle)
    } catch (_: Exception) {
        PaletteStyle.TonalSpot
    }
    val currentColorSpec = try {
        ColorSpec.SpecVersion.valueOf(uiState.colorSpec)
    } catch (_: Exception) {
        ColorSpec.SpecVersion.Default
    }
    val state = ColorPaletteUiState(
        uiState = uiState,
        currentColorMode = ColorMode.fromValue(uiState.themeMode),
        currentPaletteStyle = currentPaletteStyle,
        currentColorSpec = currentColorSpec,
    )
    val actions = ColorPaletteScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSetThemeMode = viewModel::setThemeMode,
        onSetMiuixMonet = viewModel::setMiuixMonet,
        onSetKeyColor = viewModel::setKeyColor,
        onSetColorMode = viewModel::setColorMode,
        onSetColorStyle = viewModel::setColorStyle,
        onSetColorSpec = viewModel::setColorSpec,
        onSetEnableBlur = viewModel::setEnableBlur,
        onSetEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onSetEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        onSetEnablePredictiveBack = {
            viewModel.setEnablePredictiveBack(it)
            HamKitApplication.setEnableOnBackInvokedCallback(context.applicationInfo, it)
            activity?.recreate()
        },
        onSetPageScale = viewModel::setPageScale,
        onSetCustomBackground = { uri ->
            if (imageStore.saveCustomImage(uri, context)) {
                // 存储实际文件路径而非源 URI（源 URI 进程重启后可能失效）
                viewModel.setCustomBackgroundUri(imageStore.customImageFile.absolutePath)
                mainViewModel.refreshLandscapeImage()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.timecard_background_save_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
            // 清理裁剪回退路径产生的临时文件
            java.io.File(context.cacheDir, "crop_temp.jpg").delete()
        },
        onClearCustomBackground = {
            imageStore.clearCustomImage()
            viewModel.clearCustomBackgroundUri()
            mainViewModel.refreshLandscapeImage()
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ColorPaletteScreenMiuix(state, actions)
        UiMode.Material -> ColorPaletteScreenMaterial(state, actions)
    }
}
