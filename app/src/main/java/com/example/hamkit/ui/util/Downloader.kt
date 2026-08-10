package com.example.hamkit.ui.util

import com.example.hamkit.radioApp
import okhttp3.Request
import java.io.File

/** GitHub Releases API：拉取最近若干 release（含预发布），取最新非草稿 */
private const val RELEASES_URL =
    "https://api.github.com/repos/fuxue-linkong/Dual-zone_network_positioning/releases?per_page=5"

/**
 * 查询 GitHub 最新 release 信息。
 *
 * 使用 releases 列表接口（而非 /releases/latest，后者会跳过预发布版本）。
 * versionCode 提取优先级：
 * 1. release body 中的 `Version: <name> (<code>)`（release.yml 自动发布时写入）；
 * 2. 手工上传的 APK 文件名 `HamKit_<name>_<code>(-release).apk`。
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
                val body = response.body.string()
                val releases = org.json.JSONArray(body)

                // 取最新非草稿 release（接口按发布时间倒序返回）
                var json: org.json.JSONObject? = null
                for (i in 0 until releases.length()) {
                    val rel = releases.getJSONObject(i)
                    if (!rel.optBoolean("draft", false)) {
                        json = rel
                        break
                    }
                }
                if (json == null) return defaultValue

                val changelog = json.optString("body")
                val tagName = json.optString("tag_name")
                val versionName = tagName.removePrefix("v")

                // 查找 APK 资源
                val assets = json.optJSONArray("assets") ?: return defaultValue
                var downloadUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                // 优先从 body 的 Version 行提取 versionCode（格式：Version: 1.2.0-beta.1 (10)）
                var versionCode = Regex("Version:\\s*\\S+\\s*\\((\\d+)\\)")
                    .find(changelog)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                // 手工上传的 release body 可能没有 Version 行，退而提取 APK 文件名中的版本号
                if (versionCode == 0 && downloadUrl.isNotEmpty()) {
                    val fileName = downloadUrl.substringAfterLast('/')
                    versionCode = Regex("_(\\d+)(?:-release)?\\.apk$")
                        .find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

                return LatestVersionInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    changelog = changelog,
                )
            }
    }
    return defaultValue
}

/**
 * 下载 APK 到指定文件，回调下载进度。
 *
 * @param url 下载地址（GitHub release asset 的 browser_download_url）
 * @param destFile 目标文件（通常位于 cacheDir，由 FileProvider 共享）
 * @param onProgress 进度回调，参数为 0-100 的整数；在 IO 线程触发
 * @return 下载成功返回 true，失败返回 false
 */
fun downloadApk(url: String, destFile: File, onProgress: (Int) -> Unit): Boolean {
    return runCatching {
        radioApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                destFile.parentFile?.mkdirs()
                var lastReported = -1
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
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
                true
            }
    }.getOrDefault(false)
}
