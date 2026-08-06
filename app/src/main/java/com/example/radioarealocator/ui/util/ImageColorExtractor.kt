package com.example.radioarealocator.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

object ImageColorExtractor {

    fun extractDominantColor(bitmap: Bitmap): Color {
        val palette = Palette.from(bitmap)
            .maximumColorCount(16)
            .generate()

        val candidates = listOfNotNull(
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.darkMutedSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.lightVibrantSwatch,
            palette.lightMutedSwatch,
        )

        val validSwatch = candidates.firstOrNull { swatch ->
            val c = Color(swatch.rgb)
            !isCloseToWhite(c) && !isTooBright(c)
        }

        val rawColor = Color(validSwatch?.rgb ?: DEFAULT_MASK_COLOR)
        // 强制压暗到目标亮度以下，保证白色文字在遮罩上可读
        return darkenToMaxLuminance(rawColor, MAX_MASK_LUMINANCE)
    }

    private fun isCloseToWhite(c: Color): Boolean {
        return c.red > 0.86f && c.green > 0.86f && c.blue > 0.86f
    }

    private fun isTooBright(c: Color): Boolean {
        val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return luminance > 0.82f
    }

    /**
     * 将颜色按比例压暗，直到其亮度不超过 [maxLuminance]。
     * 保证遮罩色足够深，白色文字叠加上去可读。
     */
    private fun darkenToMaxLuminance(color: Color, maxLuminance: Float): Color {
        val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        if (luminance <= maxLuminance) return color
        val scale = maxLuminance / luminance.coerceAtLeast(0.0001f)
        return Color(
            red = color.red * scale,
            green = color.green * scale,
            blue = color.blue * scale,
            alpha = color.alpha
        )
    }

    val DEFAULT_MASK_COLOR = 0xFF3A5F7F.toInt()

    /** 遮罩色允许的最大亮度，超过则按比例压暗，保证白色文字可读 */
    private const val MAX_MASK_LUMINANCE = 0.35f
}