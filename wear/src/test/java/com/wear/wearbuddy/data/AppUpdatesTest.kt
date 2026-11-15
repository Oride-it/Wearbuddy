package com.wear.wearbuddy.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest

class AppUpdatesTest {
    private fun release(tag: String = "v1.0.10") = JSONObject().put("tag_name", tag).put("body", "修复连接和更新")
        .put("assets", JSONArray().put(JSONObject().put("name", "Wearbuddy-$tag-release.apk")
            .put("browser_download_url", "https://github.com/Oride-it/Wearbuddy/releases/download/$tag/Wearbuddy-$tag-release.apk")
            .put("size", 3).put("digest", "sha256:" + "a".repeat(64))))
    @Test fun versionsAreComparedNumericallyAndIgnorePreviewAndDowngrades() {
        assertTrue(AppUpdates.isNewer("v1.0.10", "1.0.9"))
        for (v in listOf("1.0.9", "1.0.8", "v1.1.0-beta", "latest", "999999999999999999999999.0.0"))
            assertFalse(v, AppUpdates.isNewer(v, "1.0.9"))
        assertNull(AppUpdates.parseRelease(release().put("prerelease", true).toString(), "1.0.9"))
        assertNull(AppUpdates.parseRelease(release("v1.0.8").toString(), "1.0.9"))
    }
    @Test fun onlyOfficialReleaseApksWithChecksumsAreAccepted() {
        assertEquals("1.0.10", AppUpdates.parseRelease(release().toString(), "1.0.9")!!.version)
        for (field in listOf("name", "browser_download_url", "digest")) {
            val r = release()
            r.getJSONArray("assets").getJSONObject(0).put(field, "invalid")
            assertTrue(field, runCatching { AppUpdates.parseRelease(r.toString(), "1.0.9") }.isFailure)
        }
    }
    @Test fun downloadChecksIntegrityAndNeverLeavesPartialFilesAsApks() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = "real-apk-content".toByteArray()
        server.createContext("/app.apk") { it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.use { out -> out.write(bytes) } }
        server.start()
        val dir = Files.createTempDirectory("wearbuddy-update-test").toFile()
        val target = File(dir, "app.apk")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val r = AppRelease("1.0.10", "notes", "http://127.0.0.1:${server.address.port}/app.apk", bytes.size.toLong(), hash)
        try {
            var progress = 0L
            AppUpdates.download(r, target, { progress = it }, {})
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(bytes.size.toLong(), progress)
            // Repeating a valid download can replace a previous verified download.
            AppUpdates.download(r, target, {}, {})
            for (bad in listOf(r.copy(sha256 = "0".repeat(64)), r.copy(size = 100))) {
                assertTrue(runCatching { AppUpdates.download(bad, target, {}, {}) }.isFailure)
                assertFalse(File(dir, "app.apk.part").exists())
                assertArrayEquals(bytes, target.readBytes())
            }
            assertTrue(runCatching { AppUpdates.download(r, target, {}, { error("cancelled") }) }.isFailure)
            assertFalse(File(dir, "app.apk.part").exists())
        } finally { server.stop(0); dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }
}
