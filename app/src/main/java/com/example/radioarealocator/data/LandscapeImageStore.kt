package com.example.radioarealocator.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class LandscapeImageStore(context: Context) {

    private val bgDir = File(context.filesDir, BG_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    val customImageFile: File get() = File(bgDir, CUSTOM_IMAGE_NAME)

    val effectiveImageFile: File?
        get() = customImageFile.takeIf { it.exists() && it.length() > 0 }

    fun saveCustomImage(sourceUri: Uri, context: Context): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return false
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            val maxWidth = 1920
            options.inSampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight, maxWidth, maxWidth * 9 / 16
            )
            options.inJustDecodeBounds = false

            val stream = context.contentResolver.openInputStream(sourceUri) ?: return false
            val bitmap = BitmapFactory.decodeStream(stream, null, options)
            stream.close()

            if (bitmap == null) return false

            val scaledBitmap = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val scaledHeight = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
            } else {
                bitmap
            }

            customImageFile.delete()
            FileOutputStream(customImageFile).use { fos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearCustomImage() {
        customImageFile.delete()
    }

    companion object {
        private const val BG_DIR_NAME = "bg_landscape"
        private const val CUSTOM_IMAGE_NAME = "custom_image.jpg"

        private fun calculateInSampleSize(
            rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int
        ): Int {
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while (halfHeight / inSampleSize >= reqHeight ||
                    halfWidth / inSampleSize >= reqWidth
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }
}
