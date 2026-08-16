package com.example.hamkit.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

/**
 * 获取应用图标（adaptive icon）。
 *
 * 注意：在 Android 8.0+ 上 `R.mipmap.ic_launcher` 会解析为 adaptive-icon XML，
 * Compose 的 `painterResource` 只支持 VectorDrawable 与 PNG/JPG/WEBP，无法直接加载，
 * 因此这里通过 PackageManager 获取系统渲染好的图标并转为位图。
 */
@Composable
fun rememberAppIconPainter(size: Int = 512): Painter {
    val context = LocalContext.current
    val bitmap = remember(context, size) {
        runCatching {
            context.packageManager
                .getApplicationIcon(context.packageName)
                .toBitmap(size, size)
                .asImageBitmap()
        }.getOrNull()
    }
    return remember(bitmap) { bitmap?.let { BitmapPainter(it) } }
        ?: BitmapPainter(ImageBitmap(1, 1))
}
