package com.wear.wearbuddy.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Public downloads survive app removal; the private copy is used by existing preview readers. */
internal object ArtifactDownloads {
    data class Saved(val previewFile: File, val publicUri: Uri)

    fun previewFile(context: Context, profileId: String, key: String, name: String): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(100).ifBlank { "file" }
        return File(context.filesDir, "artifacts/${profileFolder(profileId)}/${hash(key.toByteArray()).take(16)}-${limitName(safe)}")
    }

    fun cachedBytes(context: Context, profileId: String, key: String, name: String): ByteArray? {
        val preview = previewFile(context, profileId, key, name)
        runCatching { AtomicFile(preview).readFully() }.getOrNull()?.let { return it }
        val index = index(context)
        val record = record(profileId, key)
        val previous = index.getString("uri:$record", null)
        if (previous != null) {
            val cached = runCatching {
                context.contentResolver.openInputStream(Uri.parse(previous))?.use { it.readBytes() }
            }.getOrNull()
            if (cached != null && hash(cached) == index.getString("hash:$record", null)) return cached
        }
        if (Build.VERSION.SDK_INT <= 28) {
            // 1.0.12 did not index public files on these releases.
            val legacy = File(downloadDirectory(), "Wearbuddy/${profileFolder(profileId)}/${preview.name}")
            return runCatching { legacy.takeIf { it.isFile }?.readBytes() }.getOrNull()
        }
        return null
    }

    @Synchronized
    fun save(context: Context, profileId: String, key: String, name: String, mime: String?, bytes: ByteArray): Saved {
        val preview = previewFile(context, profileId, key, name)
        preview.parentFile!!.mkdirs()
        val cache = AtomicFile(preview)
        val stream = cache.startWrite()
        try {
            stream.write(bytes)
            cache.finishWrite(stream)
        } catch (e: Exception) {
            cache.failWrite(stream)
            throw e
        }
        val mimeType = mime?.substringBefore(';')?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val folder = "${Environment.DIRECTORY_DOWNLOADS}/"
        val displayName = publicName(name)
        val index = index(context)
        val record = record(profileId, key)
        val digest = hash(bytes)
        val publicUri = if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val previous = index.getString("uri:$record", null)?.let(Uri::parse)
            val reusable = previous?.takeIf {
                index.getString("hash:$record", null) == digest && runCatching {
                    val inDownloads = resolver.query(it, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)
                        ?.use { row -> row.moveToFirst() && row.getString(0) == folder } == true
                    inDownloads && resolver.openInputStream(it)?.use { input -> input.matches(bytes) } == true
                }.getOrDefault(false)
            }
            reusable ?: run {
                val values = ContentValues().apply {
                    // MediaStore allocates a unique name when another download already uses this one.
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, folder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建公共 Download 文件")
                try {
                    val output = resolver.openOutputStream(uri, "w") ?: error("无法写入公共 Download 文件")
                    output.use { it.write(bytes) }
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                        "无法完成公共 Download 文件保存"
                    }
                    uri
                } catch (error: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                }
            }
        } else {
            check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                "请允许存储权限后重试下载"
            }
            val directory = downloadDirectory()
            check(directory.isDirectory || directory.mkdirs()) { "无法创建公共 Download 文件夹" }
            val existing = index.getString("name:$record", null)?.let { File(directory, it) }
            val publicFile = existing?.takeIf {
                it.isFile && index.getString("hash:$record", null) == digest &&
                    it.inputStream().use { input -> input.matches(bytes) }
            } ?: run {
                val target = reserveFile(directory, displayName)
                try {
                    val temporary = File.createTempFile(".wearbuddy-", ".part", directory)
                    try {
                        temporary.writeBytes(bytes)
                        check(temporary.renameTo(target)) { "无法保存公共 Download 文件" }
                    } finally { temporary.delete() }
                } catch (e: Exception) { target.delete(); throw e }
                index.edit().putString("name:$record", target.name).apply()
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
                target
            }
            FileProvider.getUriForFile(context, "${context.packageName}.files", publicFile)
        }
        index.edit().putString("uri:$record", publicUri.toString()).putString("hash:$record", digest).apply()
        return Saved(preview, publicUri)
    }

    private fun index(context: Context): SharedPreferences =
        context.getSharedPreferences("artifact_downloads", Context.MODE_PRIVATE)
    private fun record(profileId: String, key: String) = hash("$profileId|$key".toByteArray())
    @Suppress("DEPRECATION")
    private fun downloadDirectory() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun publicName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\p{Cntrl}:*?"<>|]"""), "_").trim().trim('.').ifBlank { "file" }
        return limitName(name)
    }

    // Leave room for duplicate suffixes and private preview prefixes on 255-byte filesystems.
    private fun limitName(name: String): String {
        if (name.toByteArray().size <= 220) return name
        val suffix = name.takeLastWhile { it != '.' }.takeIf { '.' in name && it.toByteArray().size <= 32 }
            ?.let { ".$it" }.orEmpty()
        var stem = name.removeSuffix(suffix).take(220)
        while ((stem + suffix).toByteArray().size > 220) stem = stem.dropLast(1)
        return stem.trimEnd { it.isHighSurrogate() } + suffix
    }

    private fun reserveFile(directory: File, name: String): File {
        val suffix = name.lastIndexOf('.').takeIf { it > 0 }?.let(name::substring).orEmpty()
        val stem = name.removeSuffix(suffix)
        var number = 0
        while (true) {
            val file = File(directory, if (number == 0) name else "$stem ($number)$suffix")
            if (file.createNewFile()) return file
            number++
        }
    }

    private fun java.io.InputStream.matches(bytes: ByteArray): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var offset = 0
        while (true) {
            val count = read(buffer)
            if (count == -1) return offset == bytes.size
            if (count > bytes.size - offset) return false
            for (i in 0 until count) if (buffer[i] != bytes[offset + i]) return false
            offset += count
        }
    }

    private fun profileFolder(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "legacy" }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
