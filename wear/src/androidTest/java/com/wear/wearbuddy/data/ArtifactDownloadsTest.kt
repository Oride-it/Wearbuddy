package com.wear.wearbuddy.data

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class ArtifactDownloadsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val profile = "download-test-${UUID.randomUUID()}"
    private val published = mutableListOf<Uri>()

    @org.junit.After fun cleanUp() {
        published.forEach { context.contentResolver.delete(it, null, null) }
        java.io.File(context.filesDir, "artifacts/$profile").deleteRecursively()
    }

    private fun save(key: String, name: String, bytes: ByteArray, mime: String? = null): ArtifactDownloads.Saved =
        ArtifactDownloads.save(context, profile, key, name, mime, bytes).also { published += it.publicUri }

    @Test fun savesCompleteFilesInPublicDownloadsAndKeepsPreviewWorking() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val bytes = "<!doctype html><h1>中文报告 + 完整内容</h1>".toByteArray()
        val result = save("task|agent:///workspace/report.html", "中文报告.html", bytes)
        assertArrayEquals(bytes, result.previewFile.readBytes())
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.publicUri)!!.use { it.readBytes() })
        context.contentResolver.query(result.publicUri,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.MIME_TYPE), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Download/Wearbuddy/$profile/", cursor.getString(0))
            assertTrue(cursor.getString(1).endsWith("中文报告.html"))
            assertEquals(0, cursor.getInt(2))
            assertEquals("text/html", cursor.getString(3))
        }
        result.previewFile.delete()
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.publicUri)!!.use { it.readBytes() })
    }

    @Test fun existingPrivateDownloadsCanBeCopiedToPublicStorageWithoutDownloadingAgain() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val key = "old-task|agent:///workspace/old.txt"
        val bytes = "previously downloaded file".toByteArray()
        val legacy = ArtifactDownloads.previewFile(context, profile, key, "old.txt")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(bytes)
        val cached = ArtifactDownloads.cachedBytes(context, profile, key, "old.txt")!!
        val result = save(key, "old.txt", cached)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.publicUri)!!.use { it.readBytes() })
    }

    @Test fun repeatedDownloadsReuseExistingFileAndDeletedPublicFilesAreRecreated() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val bytes = byteArrayOf(0, 1, 2, -1)
        val first = save("task|file", "report.pdf", bytes)
        val repeated = save("task|file", "report.pdf", bytes)
        assertEquals(first.publicUri, repeated.publicUri)
        context.contentResolver.delete(first.publicUri, null, null)
        val restored = save("task|file", "report.pdf", bytes)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(restored.publicUri)!!.use { it.readBytes() })
        val otherTask = save("other-task|file", "report.pdf", "another report".toByteArray())
        assertNotEquals(restored.publicUri, otherTask.publicUri)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(restored.publicUri)!!.use { it.readBytes() })
    }

    @Test fun anExistingPrivateDownloadCanBeExportedWithoutDownloadingAgain() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val key = "old-task|agent:///workspace/old.txt"
        val old = ArtifactDownloads.previewFile(context, profile, key, "old.txt")
        old.parentFile!!.mkdirs()
        old.writeText("previously downloaded offline file")
        val cached = ArtifactDownloads.cachedBytes(context, profile, key, "old.txt")!!
        val result = save(key, "old.txt", cached)
        assertEquals("previously downloaded offline file",
            context.contentResolver.openInputStream(result.publicUri)!!.bufferedReader().use { it.readText() })
    }
}
