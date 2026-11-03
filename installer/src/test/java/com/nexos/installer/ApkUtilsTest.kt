package com.nexos.installer

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUtilsTest {

    private val us = Locale.US

    @Test
    fun formatSize_kilobytes() {
        assertEquals("512 KB", ApkUtils.formatSize(512L * 1024, us))
    }

    @Test
    fun formatSize_megabytes() {
        assertEquals("1.5 MB", ApkUtils.formatSize((1.5 * 1024 * 1024).toLong(), us))
    }

    @Test
    fun formatSize_unknownWhenEmpty() {
        assertEquals("tamaño desconocido", ApkUtils.formatSize(0, us))
        assertEquals("tamaño desconocido", ApkUtils.formatSize(-5, us))
    }

    @Test
    fun bytesToHex_converts() {
        assertEquals("00ff10", ApkUtils.bytesToHex(byteArrayOf(0, -1, 16)))
        assertEquals("", ApkUtils.bytesToHex(byteArrayOf()))
    }

    @Test
    fun sha256HexOf_emptyIsWellKnownDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkUtils.sha256HexOf(byteArrayOf())
        )
    }

    @Test
    fun versionRelation_classifies() {
        assertEquals("UPDATE", ApkUtils.versionRelation(1, 2))
        assertEquals("SAME", ApkUtils.versionRelation(3, 3))
        assertEquals("DOWNGRADE", ApkUtils.versionRelation(5, 2))
    }

    @Test
    fun shortPermName_stripsPrefix() {
        assertEquals("CAMERA", ApkUtils.shortPermName("android.permission.CAMERA"))
        assertEquals("com.otro.PERMISO", ApkUtils.shortPermName("com.otro.PERMISO"))
    }

    @Test
    fun shortPermName_realManifestPermissions() {
        // Permisos reales declarados en installer/src/main/AndroidManifest.xml.
        assertEquals(
            "REQUEST_INSTALL_PACKAGES",
            ApkUtils.shortPermName("android.permission.REQUEST_INSTALL_PACKAGES")
        )
        assertEquals(
            "POST_NOTIFICATIONS",
            ApkUtils.shortPermName("android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun isSplitContainerName_detects() {
        assertTrue(ApkUtils.isSplitContainerName("app.apks"))
        assertTrue(ApkUtils.isSplitContainerName("game.XAPK"))
        assertTrue(ApkUtils.isSplitContainerName("tool.apkm"))
        assertFalse(ApkUtils.isSplitContainerName("app.apk"))
        assertFalse(ApkUtils.isSplitContainerName("notas.zip"))
    }

    @Test
    fun orderSplitCandidates_baseFirstThenBiggest() {
        val ordered = ApkUtils.orderSplitCandidates(
            listOf(
                SplitEntry("split_config.arm64.apk", 100),
                SplitEntry("other.apk", 99999),
                SplitEntry("base.apk", 50)
            )
        ).map { it.name }
        assertEquals(listOf("base.apk", "other.apk", "split_config.arm64.apk"), ordered)
    }

    @Test
    fun orderSplitCandidates_realisticBundleSplits() {
        // Nombres reales generados por bundletool para un split (.apks).
        val ordered = ApkUtils.orderSplitCandidates(
            listOf(
                SplitEntry("split_config.es.apk", 48_000),
                SplitEntry("split_config.xxhdpi.apk", 1_200_000),
                SplitEntry("split_config.arm64_v8a.apk", 8_400_000),
                SplitEntry("base.apk", 23_500_000)
            )
        ).map { it.name }
        assertEquals(
            listOf(
                "base.apk",
                "split_config.arm64_v8a.apk",
                "split_config.xxhdpi.apk",
                "split_config.es.apk"
            ),
            ordered
        )
    }

    @Test
    fun formatSize_realisticApkSize() {
        assertEquals("45.0 MB", ApkUtils.formatSize(45L * 1024 * 1024, us))
    }

    @Test
    fun versionRelation_realisticVersionCodes() {
        assertEquals("UPDATE", ApkUtils.versionRelation(104, 107))
    }
}
