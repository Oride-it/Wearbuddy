package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcpEventsTest {
    @Test fun `questions preserve option values and use per-question answer types`() {
        val ask = parseAsk(JSONObject("""
            {"toolCallId":"tool-1","schema":{"questions":[
                {"id":"format","question":"Format?","options":[{"label":"Markdown","value":"md"}]},
                {"question":"Extras?","multiSelect":true,"options":["Summary","Details"]},
                {"question":"Anything else?"}
            ]}}
        """), rpcId = "rpc-1")!!
        val picks = mapOf(
            "format" to AskPick(listOf(ask.items.first().options.single())),
            "q-1" to AskPick(ask.items[1].options, custom = "Appendix")
        )
        assertEquals("tool-1", ask.requestId)
        assertEquals("rpc-1", ask.rpcId)
        val outcome = ask.rpcOutcome(picks).getJSONObject("outcome")
        assertEquals("submitted", outcome.getString("outcome"))
        val answers = outcome.getJSONObject("data").getJSONObject("answers")
        assertEquals("md", answers.getString("format"))
        assertEquals("""["Summary","Details","Appendix"]""", answers.getJSONArray("q-1").toString())
        assertFalse(answers.has("q-2"))
        assertEquals("cancelled", ask.rpcOutcome(null).getJSONObject("outcome").getString("outcome"))
    }

    @Test fun `permission questions can have options without question text or tool id`() {
        val ask = parseAsk(JSONObject("""
            {"options":[{"name":"Allow","kind":"allow_once","optionId":"allow-7"}]}
        """), rpcId = 27, permission = true)!!
        assertNull(ask.requestId)
        assertEquals(27, ask.rpcId)
        val picks = mapOf("q-0" to AskPick(ask.items.single().options))
        val outcome = ask.rpcOutcome(picks).getJSONObject("outcome")
        assertEquals("selected", outcome.getString("outcome"))
        assertEquals("allow-7", outcome.getString("optionId"))
    }

    @Test fun `replayed tool questions do not open a card and live duplicates cannot reopen an answered card`() {
        val event = JSONObject("""
            {"method":"session/update","params":{"update":{
                "sessionUpdate":"tool_call","toolCallId":"tool-1","toolName":"AskUserQuestion",
                "status":"pending","rawInput":{"questions":[{"question":"Format?","options":["Markdown"]}]}
            }}}
        """)
        val replay = AcpEvents("task")
        replay.accept(event, replaying = true)
        assertNull(replay.snapshot().ask)

        val live = AcpEvents("task")
        live.beginTurn("Write a report")
        assertTrue(live.accept(event, replaying = false))
        assertEquals(TaskStatus.BLOCKED, live.snapshot().status)
        val ask = live.snapshot().ask!!
        live.accept(status("running"), replaying = false)
        assertEquals(TaskStatus.BLOCKED, live.snapshot().status)
        live.resolveAsk(ask, "Markdown")
        assertEquals(ChatMessage(ChatRole.USER, "Markdown"), live.snapshot().messages.last())
        assertFalse(live.accept(event, replaying = false))
        assertNull(live.snapshot().ask)
        assertEquals(TaskStatus.WORKING, live.snapshot().status)
    }

    private fun chunk(kind: String, text: String, id: String = "task") = JSONObject()
        .put("method", "session/update")
        .put("params", JSONObject().put("sessionId", id).put("update", JSONObject()
            .put("sessionUpdate", kind).put("content", JSONObject().put("type", "text").put("text", text))))

    private fun status(state: String, nested: Boolean = false): JSONObject {
        val meta = if (nested) JSONObject().put("codebuddy.ai", JSONObject().put("status", state))
            else JSONObject().put("codebuddy.ai/status", state)
        return JSONObject().put("method", "session/update").put("params", JSONObject()
            .put("sessionId", "task").put("update", JSONObject()
                .put("sessionUpdate", "session_info_update").put("_meta", meta)))
    }

    @Test fun `thought and final output retain whitespace and more than 4000 characters`() {
        val events = AcpEvents("task")
        events.beginTurn("write a report")
        events.accept(chunk("agent_thought_chunk", "Checking sources"), false)
        events.accept(chunk("agent_message_chunk", "hello"), false)
        events.accept(chunk("agent_message_chunk", " "), false)
        events.accept(chunk("agent_message_chunk", "world\n" + "文".repeat(5000)), false)
        val messages = events.snapshot().messages
        assertEquals(ChatRole.THOUGHT, messages[1].role)
        assertEquals("hello world\n" + "文".repeat(5000), messages[2].text)
    }

    @Test fun `optimistic user message is not duplicated by split server echo`() {
        val events = AcpEvents("task")
        events.beginTurn("hello world")
        events.accept(chunk("user_message_chunk", "hello "), false)
        events.accept(chunk("user_message_chunk", "world"), false)
        events.accept(chunk("agent_message_chunk", "Hi"), false)
        assertEquals(listOf(ChatMessage(ChatRole.USER, "hello world"), ChatMessage(ChatRole.ASSISTANT, "Hi")), events.snapshot().messages)
    }

    @Test fun `reply language context is hidden from live and replayed user messages`() {
        val text = "用 Kotlin 写个例子"
        val blocks = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "text").put("text", "Reply in Chinese")
                .put("_meta", JSONObject().put("codebuddy.ai/hiddenPromptContext", "reply-language")))
        val message = chunk("user_message_chunk", text).apply {
            getJSONObject("params").getJSONObject("update").put("content", blocks)
        }

        val replay = AcpEvents("task")
        replay.accept(message, true)
        val live = AcpEvents("task")
        live.beginTurn(text)
        live.accept(message, false)

        val expected = listOf(ChatMessage(ChatRole.USER, text))
        assertEquals(expected, replay.snapshot().messages)
        assertEquals(expected, live.snapshot().messages)
    }

    @Test fun `repeated prompts on separate turns stay separate`() {
        val events = AcpEvents("task")
        repeat(2) {
            events.beginTurn("again")
            events.accept(chunk("user_message_chunk", "again"), false)
            events.accept(chunk("agent_message_chunk", "OK"), false)
            events.finishTurn(JSONObject().put("stopReason", "end_turn"))
        }
        assertEquals(4, events.snapshot().messages.size)
    }

    @Test fun `history completion does not finish live turn`() {
        val events = AcpEvents("task")
        events.beginTurn("continue")
        events.accept(status("completed"), true)
        assertEquals(TaskStatus.WORKING, events.snapshot().status)
        events.accept(status("completed", nested = true), false)
        assertEquals(TaskStatus.DONE, events.snapshot().status)
    }

    @Test fun `prompt result completes without REST status and RPC errors fail visibly`() {
        val events = AcpEvents("task")
        events.beginTurn("question")
        events.finishTurn(JSONObject().put("stopReason", "end_turn"))
        assertEquals(TaskStatus.DONE, events.snapshot().status)
        events.beginTurn("follow up")
        events.finishTurn(null, JSONObject().put("message", "quota exceeded"))
        assertEquals(TaskStatus.FAILED, events.snapshot().status)
        assertEquals("quota exceeded", events.snapshot().error)
        events.accept(JSONObject().put("method", "session/endTurn").put("params", JSONObject().put("sessionId", "task")), false)
        assertEquals(TaskStatus.FAILED, events.snapshot().status)
    }

    @Test fun `unrelated session updates are ignored`() {
        val events = AcpEvents("task")
        assertFalse(events.accept(chunk("agent_message_chunk", "other task", "other"), false))
        assertTrue(events.snapshot().messages.isEmpty())
    }

    @Test fun `ACP session ID can differ from console conversation ID`() {
        val events = AcpEvents("acp-session", "conversation")
        assertTrue(events.accept(chunk("agent_message_chunk", "answer", "acp-session"), false))
        assertTrue(events.accept(chunk("agent_message_chunk", "!", "conversation"), false))
        assertFalse(events.accept(chunk("agent_message_chunk", "unrelated", "other"), false))
        assertEquals("answer!", events.snapshot().messages.single().text)
    }

    @Test fun `endTurn and state updates finish tasks without polling`() {
        val events = AcpEvents("task")
        events.beginTurn("first")
        events.accept(JSONObject().put("method", "session/endTurn").put("params", JSONObject().put("sessionId", "task")), false)
        assertEquals(TaskStatus.DONE, events.snapshot().status)
        events.beginTurn("second")
        events.accept(JSONObject().put("method", "session/update").put("params", JSONObject()
            .put("sessionId", "task").put("update", JSONObject().put("sessionUpdate", "state_update")
                .put("currentState", "cancelled"))), false)
        assertEquals(TaskStatus.STOPPED, events.snapshot().status)
    }

    @Test fun `artifact create update and delete notifications retain metadata`() {
        val events = AcpEvents("task")
        fun artifact(event: String, name: String) = JSONObject().put("method", "extNotification")
            .put("params", JSONObject().put("method", "_codebuddy.ai/artifact").put("params", JSONObject()
                .put("sessionId", "task").put("event", event).put("artifact", JSONObject()
                    .put("uri", "agent://files/report.txt").put("title", name).put("mimeType", "text/plain"))))
        events.accept(artifact("created", "Draft"), false)
        events.accept(artifact("updated", "Report"), false)
        assertEquals(listOf(Artifact("Report", "agent://files/report.txt", "text/plain")), events.snapshot().files)
        events.accept(artifact("deleted", "Report"), false)
        assertTrue(events.snapshot().files.isEmpty())
    }

    @Test fun `tool updates revise the same progress entry and discover output links`() {
        val events = AcpEvents("task")
        fun tool(kind: String, state: String) = JSONObject().put("method", "session/update")
            .put("params", JSONObject().put("sessionId", "task").put("update", JSONObject()
                .put("sessionUpdate", kind).put("toolCallId", "tool1").put("title", "Write report")
                .put("status", state).put("content", JSONObject().put("type", "resource_link")
                    .put("name", "report.md").put("uri", "agent://files/report.md"))))
        events.accept(tool("tool_call", "in_progress"), false)
        events.accept(tool("tool_call_update", "completed"), false)
        assertEquals(1, events.snapshot().messages.size)
        assertEquals(ChatRole.TOOL, events.snapshot().messages.single().role)
        assertEquals("Write report\n✓", events.snapshot().messages.single().text)
        assertEquals("report.md", events.snapshot().files.single().name)
    }

    @Test fun `SSE handles comments multiline data and trailing frames`() {
        val frames = mutableListOf<String>()
        val input = ": keepalive\r\n\r\nevent: message\r\ndata: {\r\ndata: \"id\":1}\r\n\r\ndata: {\"id\":2}"
        readSse(input.reader().buffered(), frames::add)
        assertEquals(listOf("{\n\"id\":1}", "{\"id\":2}"), frames)
    }
}
