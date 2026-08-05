package com.example.radioarealocator.data

import com.example.radioarealocator.data.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LandscapeImageApiService {

    private val client = HttpClientProvider.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchRandomLandscape(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL?t=${System.currentTimeMillis()}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type", "")
                val bodyBytes = response.body?.bytes() ?: return@withContext null
                if (contentType?.contains("image/", ignoreCase = true) == true) {
                    bodyBytes
                } else {
                    try {
                        val json = JSONObject(String(bodyBytes))
                        val imageUrl = json.optString("url", "")
                        if (imageUrl.isNotEmpty()) {
                            fetchImageFromUrl(imageUrl)
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchImageFromUrl(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_URL = "https://pic.sld.tw"
    }
}