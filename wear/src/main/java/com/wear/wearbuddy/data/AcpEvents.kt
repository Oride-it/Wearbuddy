package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

/** SSE frames may contain several data lines, comments, and empty keepalive frames. */
internal fun readSse(reader: BufferedReader, receive: (String) -> Unit) {
    val data = StringBuilder()
    fun flush() {
        if (data.isNotEmpty()) {
            receive(data.toString())
            data.setLength(0)
        }
    }
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) flush()
        else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
        }
    }
    flush()
}

internal data class AcpSnapshot(
    val messages: List<ChatMessage>,
    val files: List<Artifact>,
    val status: TaskStatus? = null,
    val error: String? = null
)

/** A reducer shared by history replay and the live stream; never exposes mutable buffers. */
internal class AcpEvents(private val sessionId: String, private val conversationId: String = sessionId) {
    private val messages = mutableListOf<ChatMessage>()
    private val files = linkedMapOf<String, Artifact>()
    private var status: TaskStatus? = null
    private var error: String? = null
    private var boundary = true
    private var expectedUserEcho: String? = null
    private val toolIndices = mutableMapOf<String, Int>()

    @Synchronized fun snapshot() = AcpSnapshot(messages.toList(), files.values.toList(), status, error)

    @Synchronized fun beginTurn(text: String) {
        status = TaskStatus.WORKING
        error = null
        messages += ChatMessage(ChatRole.USER, text)
        expectedUserEcho = text
        boundary = true
    }

    @Synchronized fun finishTurn(result: JSONObject?, failure: JSONObject? = null) {
        if (failure != null) {
            status = TaskStatus.FAILED
            error = failure.optString("message").ifBlank { "云端执行失败" }
        } else if (result?.has("stopReason") == true) {
            if (result.optString("stopReason") == "end_turn" &&
                status in setOf(TaskStatus.FAILED, TaskStatus.STOPPED)) return
            status = when (result.optString("stopReason")) {
                "cancelled", "canceled" -> TaskStatus.STOPPED
                "refusal" -> TaskStatus.FAILED
                else -> TaskStatus.DONE
            }
        }
        boundary = true
    }

    @Synchronized fun accept(root: JSONObject, replaying: Boolean): Boolean {
        var method = root.optString("method")
        var params = root.optJSONObject("params") ?: return false
        if (method == "extNotification") {
            method = params.optString("method")
            params = params.optJSONObject("params") ?: return false
        }
        val target = params.optString("sessionId")
        if (target.isNotBlank() && target != sessionId && target != conversationId) return false
        if (method == "session/endTurn") {
            if (!replaying) finishTurn(JSONObject().put("stopReason", params.optString("stopReason").ifBlank { "end_turn" }))
            return true
        }
        if (method == "_codebuddy.ai/artifact") {
            val artifact = params.optJSONObject("artifact") ?: return false
            val uri = artifact.optString("uri")
            if (uri.isBlank()) return false
            if (params.optString("event") == "deleted") files.remove(uri)
            else files[uri] = artifactOf(artifact, uri).let {
                it.copy(downloadUrl = it.downloadUrl ?: params.optString("url").takeIf { url -> url.isNotBlank() })
            }
            return true
        }
        if (method != "session/update" && method != "sessionUpdate") return false
        val update = params.optJSONObject("update") ?: return false
        val kind = update.optString("sessionUpdate")
        val meta = update.optJSONObject("_meta")
        val cb = meta?.optJSONObject("codebuddy.ai")
        val history = replaying || meta?.optString("codebuddy.ai/mode") == "history" ||
            cb?.optString("mode") == "history"
        if (meta?.optBoolean("codebuddy.ai/isSubAgent") == true || cb?.optBoolean("isSubAgent") == true) return false
        val role = when (kind) {
            "user_message_chunk" -> ChatRole.USER
            "agent_message_chunk", "assistant_message_chunk" -> ChatRole.ASSISTANT
            "agent_thought_chunk" -> ChatRole.THOUGHT
            else -> null
        }
        if (role != null) {
            val content = update.opt("content")
            collectLinks(content)
            val text = textOf(content)
            if (role == ChatRole.USER && expectedUserEcho != null) {
                val remaining = expectedUserEcho!!
                if (remaining.startsWith(text)) {
                    expectedUserEcho = remaining.removePrefix(text).takeIf { it.isNotEmpty() }
                    return true
                }
                expectedUserEcho = null
            }
            // Whitespace-only chunks are significant between words.
            if (text.isNotEmpty()) {
                val last = messages.lastOrNull()
                if (!boundary && last?.role == role) {
                    messages[messages.lastIndex] = last.copy(text = last.text + text)
                } else messages += ChatMessage(role, text)
                boundary = false
            }
            return true
        }
        if (kind == "session_info_update") {
            val state = meta?.optString("codebuddy.ai/status")?.takeIf { it.isNotBlank() }
                ?: cb?.optString("status")
            if (!history && meta?.optBoolean("codebuddy.ai/isSnapshot") != true && cb?.optBoolean("isSnapshot") != true) {
                status = when (state) {
                    "working", "planning", "pending" -> TaskStatus.WORKING
                    "completed" -> TaskStatus.DONE
                    "failed", "error" -> TaskStatus.FAILED
                    else -> status
                }
            }
            if (state == "completed") boundary = true
            return true
        }
        if (kind == "state_update") {
            if (!history) status = when (update.optString("currentState")) {
                "completed" -> TaskStatus.DONE
                "cancelled" -> TaskStatus.STOPPED
                "failed", "error" -> TaskStatus.FAILED
                "working", "thinking", "planning", "executing" -> TaskStatus.WORKING
                "waiting_for_user", "waiting_for_permission" -> TaskStatus.BLOCKED
                else -> status
            }
            boundary = true
            return true
        }
        // File links may also arrive in completed tool output, before the final answer.
        if (kind == "tool_call" || kind == "tool_call_update") {
            collectLinks(update.opt("content"))
            val id = update.optString("toolCallId")
            val title = update.optString("title").ifBlank { update.optString("toolName") }
            val index = toolIndices[id]
            val previous = index?.let { messages[it].text.substringBefore('\n') }
            if (title.isNotBlank() || previous != null) {
                val label = title.ifBlank { previous.orEmpty() }
                val toolStatus = update.optString("status")
                val indicator = when (toolStatus) {
                    "completed" -> "✓"
                    "failed" -> "✕"
                    "pending", "in_progress" -> "…"
                    else -> ""
                }
                val message = ChatMessage(ChatRole.TOOL, label + if (indicator.isBlank()) "" else "\n$indicator")
                if (index != null) messages[index] = message
                else {
                    if (id.isNotBlank()) toolIndices[id] = messages.size
                    messages += message
                }
                boundary = true
            }
            return true
        }
        return false
    }

    private fun collectLinks(node: Any?) {
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri")
                if (node.optString("type") == "resource_link" && (uri.startsWith("agent://") || uri.startsWith("file:///workspace/") || uri.startsWith("file:///root/"))) {
                    files[uri] = artifactOf(node, uri)
                }
                node.keys().forEach { collectLinks(node.opt(it)) }
            }
            is JSONArray -> (0 until node.length()).forEach { collectLinks(node.opt(it)) }
            is String -> Regex("""agent://(?:files/)?[^\s"'\\()\[\]{}<>]+""").findAll(node).forEach {
                val uri = ArtifactKeys.trimTail(it.value)
                if (uri !in files) files[uri] = Artifact(uri.substringAfterLast('/'), uri)
            }
        }
    }

    private fun artifactOf(o: JSONObject, uri: String) = Artifact(
        name = sequenceOf(o.optString("title"), o.optString("name"), uri.substringAfterLast('/'))
            .firstOrNull { it.isNotBlank() } ?: "产物文件",
        uri = uri,
        mimeType = o.optString("mimeType").ifBlank { o.optString("mime_type") }.takeIf { it.isNotBlank() },
        downloadUrl = o.optString("url").ifBlank { o.optString("download_url") }.takeIf { it.isNotBlank() }
    )

    private fun textOf(node: Any?): String = when (node) {
        is String -> node
        is JSONObject -> if (node.optString("type") in setOf("image", "resource_link", "resource")) ""
            else if (node.has("text")) node.optString("text") else textOf(node.opt("content"))
        is JSONArray -> (0 until node.length()).joinToString("") { textOf(node.opt(it)) }
        else -> ""
    }
}
