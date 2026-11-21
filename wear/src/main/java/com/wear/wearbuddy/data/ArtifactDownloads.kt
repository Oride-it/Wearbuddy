package com.wear.wearbuddy.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        return File(context.filesDir, "artifacts/${profileFolder(profileId)}/${hash(key.toByteArray()).take(16)}-$safe")
    }

    fun cachedBytes(context: Context, profileId: String, key: String, name: String): ByteArray? =
        previewFile(context, profileId, key, name).takeIf { it.isFile }?.readBytes()

    @Synchronized
    fun save(context: Context, profileId: String, key: String, name: String, mime: String?, bytes: ByteArray): Saved {
        val preview = previewFile(context, profileId, key, name)
        preview.parentFile!!.mkdirs()
        preview.writeBytes(bytes)
        val mimeType = mime?.substringBefore(';')?.takeIf { it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val folder = "${Environment.DIRECTORY_DOWNLOADS}/Wearbuddy/${profileFolder(profileId)}/"
        val publicUri = if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val index = context.getSharedPreferences("artifact_downloads", Context.MODE_PRIVATE)
            val record = hash("$profileId|$key".toByteArray())
            val digest = hash(bytes)
            val previous = index.getString("uri:$record", null)?.let(Uri::parse)
            val reusable = previous?.takeIf {
                index.getString("hash:$record", null) == digest && runCatching {
                    resolver.openInputStream(it)?.use { stream -> stream.readBytes().contentEquals(bytes) } == true
                }.getOrDefault(false)
            }
            reusable ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, preview.name)
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
                    index.edit().putString("uri:$record", uri.toString()).putString("hash:$record", digest).apply()
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
            @Suppress("DEPRECATION")
            val publicFile = File(Environment.getExternalStorageDirectory(), folder + preview.name)
            check(publicFile.parentFile!!.isDirectory || publicFile.parentFile!!.mkdirs()) { "无法创建公共 Download 文件夹" }
            val temporary = File(publicFile.parentFile, ".${publicFile.name}.part")
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(publicFile)) { "无法保存公共 Download 文件" }
            } finally { temporary.delete() }
            MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath), arrayOf(mimeType), null)
            FileProvider.getUriForFile(context, "${context.packageName}.files", publicFile)
        }
        return Saved(preview, publicUri)
    }

    private fun profileFolder(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "legacy" }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
