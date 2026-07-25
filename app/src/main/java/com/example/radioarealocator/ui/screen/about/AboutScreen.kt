package com.example.radioarealocator.ui.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.radioarealocator.BuildConfig
import com.example.radioarealocator.R
import com.example.radioarealocator.ui.LocalUiMode
import com.example.radioarealocator.ui.UiMode
import com.example.radioarealocator.ui.navigation3.LocalNavigator

private const val PROJECT_REPO = "https://github.com/fuxue-linkong/Dual-zone_network_positioning"

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current

    val links = buildList {
        // 项目主页
        add(LinkInfo(stringResource(R.string.about_project_home), PROJECT_REPO))
        // 问题反馈：直接跳转 Issues
        add(LinkInfo(stringResource(R.string.about_report_issue), "$PROJECT_REPO/issues"))
        // 开源许可
        add(LinkInfo(stringResource(R.string.about_license), "$PROJECT_REPO/blob/miuix/LICENSE"))
    }

    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        description = stringResource(R.string.about_description),
        links = links,
        disclaimer = stringResource(R.string.about_disclaimer),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = uriHandler::openUri,
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}
