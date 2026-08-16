package com.example.hamkit.ui.util

import com.example.hamkit.HamKitApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HamKitApplication::class)
class DownloaderTest {

    @Test
    fun `release metadata stays on GitHub while APK uses CloudFront`() {
        val info = parseLatestVersion(
            """
            [
              {
                "draft": false,
                "tag_name": "v3.0.0",
                "body": "Changes from GitHub\n\nVersion: 3.0.0 (16)",
                "assets": [
                  {
                    "name": "HamKit_3.0.0_16-release.apk",
                    "browser_download_url": "https://github.com/example/releases/download/v3.0.0/HamKit_3.0.0_16-release.apk"
                  }
                ]
              }
            ]
            """.trimIndent()
        )

        assertEquals(16, info.versionCode)
        assertEquals("3.0.0", info.versionName)
        assertEquals("Changes from GitHub\n\nVersion: 3.0.0 (16)", info.changelog)
        assertEquals(
            "https://apk.hamkit.click/release/HamKit_3.0.0_16-release.apk",
            info.downloadUrl,
        )
    }

    @Test
    fun `draft release is skipped and version code falls back to APK name`() {
        val info = parseLatestVersion(
            """
            [
              {
                "draft": true,
                "tag_name": "v9.0.0",
                "body": "draft",
                "assets": [{"name": "HamKit_9.0.0_99-release.apk"}]
              },
              {
                "draft": false,
                "tag_name": "v3.1.0-beta.1",
                "body": "Beta notes from GitHub",
                "assets": [{"name": "HamKit_3.1.0-beta.1_17-release.apk"}]
              }
            ]
            """.trimIndent()
        )

        assertEquals(17, info.versionCode)
        assertEquals("3.1.0-beta.1", info.versionName)
        assertEquals("Beta notes from GitHub", info.changelog)
        assertTrue(info.downloadUrl.startsWith("https://apk.hamkit.click/release/"))
    }

    @Test
    fun `APK filename is encoded as one CDN path segment`() {
        assertEquals(
            "https://apk.hamkit.click/release/HamKit%203.0.0%20release.apk",
            buildCdnApkUrl("HamKit 3.0.0 release.apk"),
        )
    }

    @Test
    fun `release without APK has no download URL`() {
        val info = parseLatestVersion(
            """[{"draft":false,"tag_name":"v3.0.0","body":"notes","assets":[]}]"""
        )

        assertEquals("", info.downloadUrl)
        assertEquals("notes", info.changelog)
    }

    @Test
    fun `parseUpdateJson parses all four fields`() {
        val info = parseUpdateJson(
            """
            {
              "versionCode": 42,
              "versionName": "2.1.2",
              "downloadUrl": "https://apk.hamkit.click/release/HamKit_2.1.2_42-release.apk",
              "changelog": "## 更新日志\n- 修复若干问题"
            }
            """.trimIndent()
        )

        assertEquals(42, info.versionCode)
        assertEquals("2.1.2", info.versionName)
        assertEquals(
            "https://apk.hamkit.click/release/HamKit_2.1.2_42-release.apk",
            info.downloadUrl,
        )
        assertEquals("## 更新日志\n- 修复若干问题", info.changelog)
    }

    @Test
    fun `parseUpdateJson tolerates missing fields with optXxx defaults`() {
        // versionCode 缺失 → 默认 0；其余字段按实际返回。是否回退由 checkNewVersion() 判定。
        val missingCode = parseUpdateJson(
            """{"versionName":"2.1.2","downloadUrl":"https://apk.hamkit.click/x.apk","changelog":"x"}"""
        )
        assertEquals(0, missingCode.versionCode)
        assertEquals("2.1.2", missingCode.versionName)
        assertEquals("https://apk.hamkit.click/x.apk", missingCode.downloadUrl)
        assertEquals("x", missingCode.changelog)

        // downloadUrl 缺失 → 空串
        val missingUrl = parseUpdateJson(
            """{"versionCode":42,"versionName":"2.1.2","changelog":"x"}"""
        )
        assertEquals(42, missingUrl.versionCode)
        assertEquals("2.1.2", missingUrl.versionName)
        assertEquals("", missingUrl.downloadUrl)
        assertEquals("x", missingUrl.changelog)
    }

    @Test
    fun `parseUpdateJson returns default on invalid JSON`() {
        val info = parseUpdateJson("not json")
        assertEquals(0, info.versionCode)
        assertEquals("", info.versionName)
        assertEquals("", info.downloadUrl)
        assertEquals("", info.changelog)
    }
}
