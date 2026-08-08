package com.example.radioarealocator.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isNewerVersion] 语义化版本号比较单元测试。
 *
 * 覆盖：
 * - 主/次/修订号逐级比较
 * - 预发布（alpha/beta/rc）与正式版的关系
 * - 不同长度版本号（2.1 vs 2.1.0）
 * - v 前缀与非法输入
 */
class VersionCompareTest {

    @Test
    fun `patch version is newer`() {
        assertTrue(isNewerVersion("2.1.3", "2.1.2"))
    }

    @Test
    fun `minor version is newer`() {
        assertTrue(isNewerVersion("2.2.0", "2.1.9"))
    }

    @Test
    fun `major version is newer`() {
        assertTrue(isNewerVersion("3.0.0", "2.9.9"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(isNewerVersion("2.1.2", "2.1.2"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(isNewerVersion("2.1.1", "2.1.2"))
    }

    @Test
    fun `prerelease is newer than lower stable`() {
        assertTrue(isNewerVersion("2.1.3-beta.1", "2.1.2"))
    }

    @Test
    fun `prerelease is older than same stable`() {
        assertFalse(isNewerVersion("2.1.3-beta.1", "2.1.3"))
    }

    @Test
    fun `same prerelease level with higher build is newer`() {
        assertTrue(isNewerVersion("2.1.3-beta.2", "2.1.3-beta.1"))
    }

    @Test
    fun `alpha is older than beta at same version`() {
        assertFalse(isNewerVersion("2.1.3-alpha.1", "2.1.3-beta.1"))
    }

    @Test
    fun `short version compared with padded zeros`() {
        assertTrue(isNewerVersion("2.1", "2.0.9"))
        assertFalse(isNewerVersion("2.1", "2.1.0"))
    }

    @Test
    fun `v prefix is tolerated`() {
        assertTrue(isNewerVersion("v2.1.3", "2.1.2"))
    }

    @Test
    fun `invalid input is not newer`() {
        assertFalse(isNewerVersion("", "2.1.2"))
        assertFalse(isNewerVersion("abc", "2.1.2"))
        assertFalse(isNewerVersion("2.1.3", ""))
    }
}
