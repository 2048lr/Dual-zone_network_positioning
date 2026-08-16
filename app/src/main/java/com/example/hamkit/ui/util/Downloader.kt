package com.example.hamkit.ui.util

import com.example.hamkit.radioApp
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.File

/** GitHub Releases API：拉取最近若干 release（含预发布），取最新非草稿 */
private const val RELEASES_URL =
    "https://api.github.com/repos/fuxue-linkong/Dual-zone_network_positioning/releases?per_page=5"

/** 官网使用的 CloudFront APK 分发域名，源站为 S3。 */
private const val APK_CDN_HOST = "apk.hamkit.click"

/**
 * 查询 GitHub 最新 release 信息，并将 APK asset 映射到官网 CDN。
 *
 * 使用 releases 列表接口（而非 /releases/latest，后者会跳过预发布版本）。
 * versionCode 提取优先级：
 * 1. release body 中的 `Version: <name> (<code>)`（release.yml 自动发布时写入）；
 * 2. 手工上传的 APK 文件名 `HamKit_<name>_<code>(-release).apk`。
 *
 * 更新日志和版本信息始终来自 GitHub；APK 文件名来自 GitHub asset，但下载流量走
 * `apk.hamkit.click` 对应的 CloudFront 分配。
 *
 * @return 最新版本信息；网络失败或无 release 时返回默认空值
 */
fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(radioApp)) return LatestVersionInfo()
    val defaultValue = LatestVersionInfo()
    runCatching {
        radioApp.okhttpClient.newCall(Request.Builder().url(RELEASES_URL).build()).execute()
            .use { response ->
                if (!response.isSuccessful) return defaultValue
                return parseLatestVersion(response.body.string())
            }
    }
    return defaultValue
}

/** 将 GitHub Releases API 响应转换为应用更新信息。 */
internal fun parseLatestVersion(body: String): LatestVersionInfo {
    val releases = org.json.JSONArray(body)

    var release: org.json.JSONObject? = null
    for (i in 0 until releases.length()) {
        val candidate = releases.getJSONObject(i)
        if (!candidate.optBoolean("draft", false)) {
            release = candidate
            break
        }
    }
    release ?: return LatestVersionInfo()

    val changelog = release.optString("body")
    val versionName = release.optString("tag_name").removePrefix("v")
    val assets = release.optJSONArray("assets") ?: return LatestVersionInfo()
    var apkFileName = ""
    for (i in 0 until assets.length()) {
        val name = assets.getJSONObject(i).optString("name")
        if (name.endsWith(".apk", ignoreCase = true)) {
            apkFileName = name
            break
        }
    }

    val downloadUrl = if (apkFileName.isEmpty()) "" else buildCdnApkUrl(apkFileName)
    var versionCode = Regex("Version:\\s*\\S+\\s*\\((\\d+)\\)")
        .find(changelog)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    if (versionCode == 0 && apkFileName.isNotEmpty()) {
        versionCode = Regex("_(\\d+)(?:-release)?\\.apk$", RegexOption.IGNORE_CASE)
            .find(apkFileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    return LatestVersionInfo(
        versionCode = versionCode,
        versionName = versionName,
        downloadUrl = downloadUrl,
        changelog = changelog,
    )
}

internal fun buildCdnApkUrl(apkFileName: String): String = HttpUrl.Builder()
    .scheme("https")
    .host(APK_CDN_HOST)
    .addPathSegment("release")
    .addPathSegment(apkFileName)
    .build()
    .toString()

/**
 * 下载 APK 到指定文件，回调下载进度。
 *
 * @param url 下载地址（官网 CloudFront 分发地址）
 * @param destFile 目标文件（通常位于 cacheDir，由 FileProvider 共享）
 * @param onProgress 进度回调，参数为 0-100 的整数；在 IO 线程触发
 * @return 下载成功返回 true，失败返回 false
 */
fun downloadApk(url: String, destFile: File, onProgress: (Int) -> Unit): Boolean {
    val partialFile = File(destFile.parentFile, "${destFile.name}.part")
    val success = runCatching {
        radioApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    false
                } else {
                    val totalBytes = body.contentLength()
                    destFile.parentFile?.mkdirs()
                    partialFile.delete()
                    var lastReported = -1
                    body.byteStream().use { input ->
                        partialFile.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var readBytes = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                readBytes += read
                                if (totalBytes > 0) {
                                    val progress = (readBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                                    if (progress != lastReported) {
                                        lastReported = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                    partialFile.length() > 0L &&
                        (!destFile.exists() || destFile.delete()) &&
                        partialFile.renameTo(destFile)
                }
            }
    }.getOrDefault(false)
    if (!success) partialFile.delete()
    return success
}
