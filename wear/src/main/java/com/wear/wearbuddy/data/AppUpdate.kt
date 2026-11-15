package com.wear.wearbuddy.data

import com.wear.wearbuddy.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.MessageDigest

internal data class AppRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)

internal object AppUpdates {
    private val versionPattern = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = versionPattern.matchEntire(v)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
        val a = parse(remote) ?: return false
        val b = parse(current) ?: return false
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
    fun parseRelease(raw: String, current: String): AppRelease? {
        val root = JSONObject(raw)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.getString("tag_name")
        if (!isNewer(tag, current)) return null
        val name = "Wearbuddy-${if (tag.startsWith("v")) tag else "v$tag"}-release.apk"
        val assets = root.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == name }
            ?: error("新版本尚未上传正式版安装包")
        val url = asset.getString("browser_download_url")
        check(url.startsWith("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/")) { "更新地址不属于项目仓库" }
        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
        check(Regex("[0-9a-f]{64}").matches(digest)) { "更新包缺少 SHA-256 校验信息" }
        val size = asset.getLong("size")
        check(size in 1..100L*1024*1024) { "更新包大小异常" }
        return AppRelease(tag.removePrefix("v"), root.optString("body").ifBlank { "此版本未提供更新说明" }, url, size, digest)
    }
    fun latest(current: String): AppRelease? {
        val conn = connection("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            val code = conn.responseCode
            check(code == 200) { if (code == 404) "更新源尚未发布正式版本" else "检测更新失败：HTTP $code" }
            parseRelease(conn.inputStream.bufferedReader().use { it.readText() }, current)
        } finally { conn.disconnect() }
    }
    fun download(release: AppRelease, target: File, progress: (Long) -> Unit, checkActive: () -> Unit): File {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile!!.mkdirs()
        val conn = connection(release.url)
        try {
            check(conn.responseCode == 200) { "下载失败：HTTP ${conn.responseCode}" }
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            conn.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(32*1024)
                var next = 0L
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    check(count <= release.size) { "安装包大小与发布信息不符" }
                    hash.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (count >= next) { progress(count); next = count + 64*1024 }
                }
            } }
            check(count == release.size) { "安装包下载不完整，请重试" }
            check(hash.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "安装包校验失败，请重试" }
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            progress(count)
            return target
        } finally { conn.disconnect(); part.delete() }
    }
    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 30000
        useCaches = false
        setRequestProperty("User-Agent", "Wearbuddy/${BuildConfig.VERSION_NAME}")
    }
}
