package com.tooodo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** version.json 的解析和版本比较（格式由 scripts/release.sh 生成）。 */
class UpdaterTest {
    private val json = """
        {"versionCode": 12, "versionName": "0.2.0", "apk": "Tooodo-0.2.0.apk",
         "sha256": "ABCDEF", "size": 2747148, "notes": "修复若干问题"}
    """.trimIndent()

    @Test
    fun parse_resolvesApkUrlAndNormalizesHash() {
        val r = Updater.parse(json) { name -> "https://example.test/download/$name" }!!
        assertEquals(12L, r.versionCode)
        assertEquals("0.2.0", r.versionName)
        assertEquals("https://example.test/download/Tooodo-0.2.0.apk", r.apkUrl)
        assertEquals("abcdef", r.sha256)
        assertEquals(2747148L, r.size)
        assertEquals("修复若干问题", r.notes)
    }

    @Test
    fun parse_missingApkAssetGivesNull() {
        assertNull(Updater.parse(json) { null })
    }

    @Test
    fun isNewer_comparesVersionCodeNotName() {
        val r = Updater.parse(json) { "u" }!!
        assertTrue(Updater.isNewer(r, 11))
        assertFalse(Updater.isNewer(r, 12))
        assertFalse(Updater.isNewer(r, 13))
    }
}
