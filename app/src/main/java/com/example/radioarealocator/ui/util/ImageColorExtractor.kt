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

        return Color(validSwatch?.rgb ?: DEFAULT_MASK_COLOR)
    }

    private fun isCloseToWhite(c: Color): Boolean {
        return c.red > 0.86f && c.green > 0.86f && c.blue > 0.86f
    }

    private fun isTooBright(c: Color): Boolean {
        val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return luminance > 0.82f
    }

    companion object {
        val DEFAULT_MASK_COLOR = 0xFF3A5F7F.toInt()
    }
}