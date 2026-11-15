package com.wear.wearbuddy.data

/**
 * 产物 URI 到 COS 对象键的规范化。
 * Web 端同时存在原样、解码、重新编码几种写法，这里一次给出所有变体供预签名尝试。
 */
internal object ArtifactKeys {

    private val TAIL_CHARS = charArrayOf(
        ' ', '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？', '、'
    )
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun workspacePath(uri: String): String? {
        val raw = when {
            uri.startsWith("file:///workspace/") -> uri.removePrefix("file://")
            uri.startsWith("/workspace/") -> uri
            uri.startsWith("agent://files/") -> "/" + uri.removePrefix("agent://files/").trimStart('/')
            uri.startsWith("agent:///") -> uri.removePrefix("agent://")
            uri.startsWith("agent://") -> "/" + uri.removePrefix("agent://")
            else -> return null
        }
        val decoded = raw.split('/').joinToString("/") { decodeSegment(it) }
        val twice = decoded.split('/').joinToString("/") { decodeSegment(it) }
        if (listOf(raw, decoded, twice).any { path ->
            !path.startsWith("/workspace/") || path.contains('\\') || path.contains('\u0000') ||
                path.split('/').any { it == "." || it == ".." }
        }) return null
        return "/" + decoded.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }

    fun variantsOf(uri: String): List<String> {
        val raw = trimTail(
            when {
                uri.startsWith("agent://files/") -> uri.removePrefix("agent://files/")
                uri.startsWith("agent:///") -> uri.removePrefix("agent:///")
                uri.startsWith("agent://") -> uri.removePrefix("agent://")
                else -> uri
            }.trimStart('/').substringBefore('#').substringBefore('?')
        )
        if (raw.isBlank() || raw.startsWith("http://") || raw.startsWith("https://")) return emptyList()
        val decoded = raw.split('/').joinToString("/") { decodeSegment(it) }
        val encoded = decoded.split('/').joinToString("/") { encodeSegment(it) }
        return listOf(raw, decoded, encoded).filter { it.isNotBlank() }.distinct()
    }

    /** 去掉正文里抓链接时残留的句末标点。 */
    fun trimTail(value: String): String = value.trimEnd(*TAIL_CHARS)

    /** 解码路径段里的 %XX；保留字面量 +，与 decodeURIComponent 一致。 */
    fun decodeSegment(segment: String): String {
        if ('%' !in segment) return segment
        val out = StringBuilder(segment.length)
        val pending = java.io.ByteArrayOutputStream(segment.length)
        fun flush() {
            if (pending.size() > 0) {
                out.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
        }
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val code = segment.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    pending.write(code)
                    i += 3
                    continue
                }
            }
            flush()
            // 按码点追加，避免代理对被拆开后编码成非法 UTF-8。
            val codePoint = segment.codePointAt(i)
            out.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        flush()
        return out.toString()
    }

    /** 等价于 JS 的 encodeURIComponent。 */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && (char.isLetterOrDigit() || char in "-_.!~*'()")) {
                out.append(char)
            } else {
                out.append('%')
                    .append(HEX_DIGITS[value shr 4])
                    .append(HEX_DIGITS[value and 0x0F])
            }
        }
        return out.toString()
    }
}
