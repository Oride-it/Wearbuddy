package com.wear.wearbuddy.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cloud account avatar with a bounded disk cache and a local-photo fallback. */
@Composable
fun ProfileAvatar(account: String, initial: String, remoteUrl: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(account) {
        val id = java.security.MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        File(context.filesDir, "avatar-$id.png")
    }
    var revision by remember(account) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, file, revision, remoteUrl) {
        value = null
        value = withContext(Dispatchers.IO) {
            val local = BitmapFactory.decodeFile(file.path)
            if (remoteUrl.isBlank() || revision > 0) return@withContext local
            val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
                .digest((account + remoteUrl).toByteArray()).joinToString("") { "%02x".format(it) }
            val cache = File(context.cacheDir, "cloud-avatar-$cacheKey.png")
            val cached = BitmapFactory.decodeFile(cache.path)
            if (cached != null) return@withContext cached
            runCatching {
                val settings = com.wear.wearbuddy.data.CloudSettings(context)
                var url = java.net.URL(remoteUrl)
                var imageBytes: ByteArray? = null
                // Re-evaluate the origin on every redirect before attaching credentials.
                for (redirect in 0..4) {
                    require(url.protocol == "https")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", settings.userAgent)
                        val origin = java.net.URL(settings.baseUrl)
                        if (url.host == origin.host && url.port == origin.port) {
                            settings.cookies.header(url.toString())?.let { setRequestProperty("Cookie", it) }
                        }
                    }
                    try {
                        if (conn.responseCode in 300..399) {
                            url = java.net.URL(url, conn.getHeaderField("Location") ?: error("Missing redirect"))
                            continue
                        }
                        check(conn.responseCode == 200)
                        val bytes = conn.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) }
                        imageBytes = bytes
                        break
                    } finally { conn.disconnect() }
                }
                val bytes = imageBytes ?: error("Too many redirects")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 256) inSampleSize *= 2
                }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid avatar")
                cache.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image
            }.getOrNull() ?: local
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
                    }
                    val image = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: return@runCatching
                    val side = minOf(image.width, image.height)
                    val crop = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
                    val thumbnail = Bitmap.createScaledBitmap(crop, 128, 128, true)
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    temp.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    check(temp.renameTo(file))
                }
            }
            result.onSuccess { revision++ }.onFailure {
                android.widget.Toast.makeText(context, "无法读取这张图片，请重新选择", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClickLabel = "选择头像") { runCatching { launcher.launch(android.content.Intent(context, FilePickerActivity::class.java).setType("image/*")) } },
        contentAlignment = Alignment.Center
    ) {
        val photo = bitmap
        if (photo == null) Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer)
        else Image(photo.asImageBitmap(), contentDescription = "头像", contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp))
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "Avatar exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
