package com.example.hamkit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class LandscapeImageStore(context: Context) {

    private val appContext = context.applicationContext

    private val bgDir = File(appContext.filesDir, BG_DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    val customImageFile: File get() = File(bgDir, CUSTOM_IMAGE_NAME)

    val defaultImageFile: File get() = File(bgDir, DEFAULT_IMAGE_NAME)

    val hasCustomImage: Boolean
        get() = customImageFile.exists() && customImageFile.length() > 0

    val effectiveImageFile: File?
        get() = customImageFile.takeIf { it.exists() && it.length() > 0 }
            ?: defaultImageFile.takeIf { it.exists() && it.length() > 0 }

    init {
        ensureDefaultImage()
    }

    /**
     * 确保默认时间卡背景（the_moon）存在。
     *
     * 通过 assets 原始字节复制到本地，不经过 Bitmap 解码/压缩，保证无损；
     * 仅在文件缺失时复制一次，避免重复 IO。
     */
    fun ensureDefaultImage(): File? {
        val file = defaultImageFile
        if (file.exists() && file.length() > 0) return file
        return try {
            appContext.assets.open(DEFAULT_IMAGE_ASSET).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun saveCustomImage(sourceUri: Uri, context: Context): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            // 用 use 保证流在异常路径（解码失败/OOM）下也被关闭
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            } ?: return false

            val maxWidth = 1920
            options.inSampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight, maxWidth, maxWidth * 9 / 16
            )
            options.inJustDecodeBounds = false

            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return false

            if (bitmap == null) return false

            val scaledBitmap = if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val scaledHeight = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, maxWidth, scaledHeight, true)
            } else {
                bitmap
            }

            // 原子替换：先写临时文件，成功后再替换目标文件，避免写入失败导致旧背景丢失
            val tmpFile = File(bgDir, "$CUSTOM_IMAGE_NAME.tmp")
            var saved = false
            try {
                FileOutputStream(tmpFile).use { fos ->
                    saved = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                if (saved) {
                    // 写入成功后删除旧文件并重命名临时文件为目标文件
                    if (customImageFile.exists()) customImageFile.delete()
                    saved = tmpFile.renameTo(customImageFile)
                }
                if (!saved) tmpFile.delete()
            } catch (_: Exception) {
                tmpFile.delete()
                saved = false
            }
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            saved
        } catch (_: Exception) {
            false
        }
    }

    fun clearCustomImage() {
        customImageFile.delete()
        // 同时清理可能残留的临时文件
        File(bgDir, "$CUSTOM_IMAGE_NAME.tmp").delete()
    }

    companion object {
        private const val BG_DIR_NAME = "bg_landscape"
        private const val CUSTOM_IMAGE_NAME = "custom_image.jpg"
        private const val DEFAULT_IMAGE_NAME = "default_image.jpg"
        private const val DEFAULT_IMAGE_ASSET = "the_moon.jpg"

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
