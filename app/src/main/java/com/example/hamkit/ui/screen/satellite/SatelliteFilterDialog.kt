package com.example.hamkit.ui.screen.satellite

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.hamkit.R
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 卫星筛选弹窗（Miuix 风格）。
 *
 * 使用 [OverlayDialog] 从底部滑出，自带遮罩、入场动画与下滑关闭手势，
 * 与项目内 ScaleDialog / SendLogDialog 等弹窗风格一致。
 *
 * 筛选内容复用 [SatelliteFilterDialogContent]，标题由 OverlayDialog 统一渲染。
 */
@Composable
fun SatelliteFilterDialogMiuix(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.filter_title),
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
        content = {
            SatelliteFilterDialogContent(onDismiss = onDismissRequest)
        }
    )
}
