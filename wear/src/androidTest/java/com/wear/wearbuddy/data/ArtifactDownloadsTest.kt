package com.wear.wearbuddy.data

import android.content.ContentValues
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
    private val published = mutableSetOf<Uri>()
    private val otherProfile = "other-$profile"

    @org.junit.After fun cleanUp() {
        published.forEach { context.contentResolver.delete(it, null, null) }
        java.io.File(context.filesDir, "artifacts/$profile").deleteRecursively()
        java.io.File(context.filesDir, "artifacts/$otherProfile").deleteRecursively()
    }

    private fun save(key: String, name: String, bytes: ByteArray, mime: String? = null): ArtifactDownloads.Saved =
        ArtifactDownloads.save(context, profile, key, name, mime, bytes).also { published += it.publicUri }

    @Test fun savesCompleteFilesInPublicDownloadsAndKeepsPreviewWorking() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val bytes = "<!doctype html><h1>中文报告 + 完整内容</h1>".toByteArray()
        val original = "中文 报告-${UUID.randomUUID()}.html"
        val result = save("task|agent:///workspace/report.html", original, bytes)
        assertArrayEquals(bytes, result.previewFile.readBytes())
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.publicUri)!!.use { it.readBytes() })
        context.contentResolver.query(result.publicUri,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.MIME_TYPE), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Download/", cursor.getString(0))
            assertEquals(original, cursor.getString(1))
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
        val name = "report-${UUID.randomUUID()}.pdf"
        val first = save("task|file", name, bytes)
        val repeated = save("task|file", name, bytes)
        assertEquals(first.publicUri, repeated.publicUri)
        context.contentResolver.delete(first.publicUri, null, null)
        val restored = save("task|file", name, bytes)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(restored.publicUri)!!.use { it.readBytes() })
        val otherTask = save("other-task|file", name, "another report".toByteArray())
        val otherAccount = ArtifactDownloads.save(context, otherProfile, "task|file", name, null, "account report".toByteArray())
        published += otherAccount.publicUri
        assertNotEquals(otherAccount.publicUri, restored.publicUri)
        assertNotEquals(otherAccount.publicUri, otherTask.publicUri)
        assertNotEquals(restored.publicUri, otherTask.publicUri)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(restored.publicUri)!!.use { it.readBytes() })
        assertEquals("another report", context.contentResolver.openInputStream(otherTask.publicUri)!!.bufferedReader().use { it.readText() })
        assertEquals("account report", context.contentResolver.openInputStream(otherAccount.publicUri)!!.bufferedReader().use { it.readText() })
    }

    @Test fun previousVersionsIndexedDownloadIsCopiedToRootWithoutNetworkOrDeletingUserFiles() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val key = "old-task|agent:///workspace/old.txt"
        val bytes = "previously downloaded offline file".toByteArray()
        val name = "old-${UUID.randomUUID()}.txt"
        val old = save(key, name, bytes)
        val resolver = context.contentResolver
        // 1.0.12 used the same index format but published the record in a profile subfolder.
        assertEquals(1, resolver.update(old.publicUri, ContentValues().apply {
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Wearbuddy/$profile/")
        }, null, null))
        assertTrue(old.previewFile.delete())
        val cached = ArtifactDownloads.cachedBytes(context, profile, key, name)
        assertArrayEquals(bytes, cached)
        val result = save(key, name, cached!!)
        assertNotEquals(old.publicUri, result.publicUri)
        assertArrayEquals(bytes, resolver.openInputStream(old.publicUri)!!.use { it.readBytes() })
        assertArrayEquals(bytes, resolver.openInputStream(result.publicUri)!!.use { it.readBytes() })
        resolver.query(result.publicUri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)!!.use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("Download/", row.getString(0))
        }
    }
}
