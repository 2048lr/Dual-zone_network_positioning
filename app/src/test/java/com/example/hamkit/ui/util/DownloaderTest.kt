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
}
