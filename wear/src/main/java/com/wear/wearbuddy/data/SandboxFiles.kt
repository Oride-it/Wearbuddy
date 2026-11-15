package com.wear.wearbuddy.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
}
