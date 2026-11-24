package com.wear.wearbuddy.data

import org.json.JSONObject
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URL

/** 工作区目录里的一条文件 / 子目录。 */
data class SandboxEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L
)

/** File gateway protocol recovered from the official Flutter client's Dart AOT snapshot. */
internal object SandboxFiles {
    private val portHost = Regex("""^(https?://)\d+-([0-9a-z]+)(\.(?:ap-(?:beijing|shanghai|guangzhou|chongqing))\.(?:agentos-run\.net|tencentags\.com)|\.e2b\.[^/]+\.sandbox\.(?:cloudstudio\.club|codebuddy\.woa\.com))(/.*)?$""")
    private val bareHost = Regex("""^(https?://)([0-9a-z]+)(\.(?:ap-(?:beijing|shanghai|guangzhou|chongqing))\.(?:agentos-run\.net|tencentags\.com)|\.e2b\.[^/]+\.sandbox\.[^/]+)(/.*)?$""")

    fun dataEndpoint(session: JSONObject): String? {
        fun field(key: String) = (session.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        (field("e2bDataEndpoint") ?: field("sandboxDataLink") ?: field("dataEndpoint"))?.let {
            return it.replace(Regex("^http://"), "https://").trimEnd('/')
        }
        val endpoint = field("e2bEndpoint") ?: field("sandboxLink") ?: field("link") ?: return null
        val match = portHost.matchEntire(endpoint) ?: bareHost.matchEntire(endpoint) ?: return null
        return "https://49983-${match.groupValues[2]}${match.groupValues[3]}"
    }

    class ReadFailure(val status: Int) : IllegalStateException("沙箱文件读取失败：HTTP $status")

    fun read(endpoint: String, token: String, path: String): ByteArray {
        val encodedPath = ArtifactKeys.encodeSegment(path)
        val conn = URL("${endpoint.trimEnd('/')}/files?path=$encodedPath").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("X-Access-Token", token)
        conn.setRequestProperty("Accept", "*/*")
        conn.useCaches = false
        return try {
            val status = conn.responseCode
            if (status !in 200..299) throw ReadFailure(status)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    class ListFailure(val status: Int) : IllegalStateException("沙箱目录读取失败：HTTP $status")

    /** The Android client lists one level with an unframed UTF-8 JSON POST. */
    fun listDir(endpoint: String, token: String, path: String): List<SandboxEntry> {
        val url = URL("${endpoint.trimEnd('/')}/filesystem.Filesystem/ListDir")
        val payload = JSONObject().put("path", path).put("depth", 1).toString().toByteArray(Charsets.UTF_8)
        val response = try {
            postListDir(url, token, "application/json", payload)
        } catch (e: ListFailure) {
            if (e.status != HttpURLConnection.HTTP_UNSUPPORTED_TYPE) throw e
            // Retry the media negotiation with an explicit charset and the same JSON body.
            postListDir(url, token, "application/json; charset=utf-8", payload)
        }
        return parseEntries(path, response)
    }

    private fun postListDir(url: URL, token: String, contentType: String, payload: ByteArray): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Access-Token", token)
        conn.setFixedLengthStreamingMode(payload.size)
        conn.useCaches = false
        return try {
            conn.outputStream.use { it.write(payload) }
            val status = conn.responseCode
            if (status !in 200..299) throw ListFailure(status)
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseEntries(directory: String, response: String): List<SandboxEntry> {
        val root = try {
            JSONObject(response)
        } catch (e: JSONException) {
            throw IllegalStateException("沙箱目录响应不是 JSON", e)
        }
        val result = root.optJSONObject("result") ?: root
        val entries = result.optJSONArray("entries")
            ?: if (result.length() == 0) return emptyList()
            else throw IllegalStateException("沙箱目录响应缺少文件列表")
        val parent = directory.trimEnd('/')
        return (0 until entries.length()).mapNotNull { i ->
            val entry = entries.optJSONObject(i) ?: return@mapNotNull null
            val rawPath = (entry.opt("path") as? String).orEmpty().trimEnd('/')
            val name = (entry.opt("name") as? String).orEmpty().ifBlank { rawPath.substringAfterLast('/') }
            if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name) {
                return@mapNotNull null
            }
            // The returned path is either an absolute direct child or a relative child name.
            val entryPath = "$parent/$name"
            if (rawPath.isNotEmpty() && rawPath != name && rawPath != entryPath) return@mapNotNull null
            SandboxEntry(
                name = name,
                path = entryPath,
                isDir = entry.optString("type") in setOf("2", "FILE_TYPE_DIRECTORY", "DIRECTORY"),
                size = entry.optLong("size")
            )
        }.distinctBy { it.path }
    }
}
