package com.wear.wearbuddy.data

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

class SandboxFilesTest {
    @Test fun `session accepts official endpoint aliases without treating null as a URL`() {
        assertEquals("https://files.example", SandboxFiles.dataEndpoint(JSONObject()
            .put("e2bDataEndpoint", JSONObject.NULL).put("sandboxDataLink", "http://files.example/")))
        assertEquals("https://primary.example", SandboxFiles.dataEndpoint(JSONObject()
            .put("e2bDataEndpoint", "https://primary.example/").put("sandboxDataLink", "https://other.example")))
    }

    @Test fun `session derives file port from both official sandbox host formats`() {
        val id = "a".repeat(32)
        val suffix = ".e2b.region.sandbox.cloudstudio.club"
        assertEquals("https://49983-$id$suffix", SandboxFiles.dataEndpoint(JSONObject()
            .put("e2bEndpoint", "https://8080-$id$suffix/acp")))
        assertEquals("https://49983-$id$suffix", SandboxFiles.dataEndpoint(JSONObject()
            .put("sandboxLink", "https://$id$suffix")))
        assertEquals("https://49983-box123.ap-shanghai.agentos-run.net", SandboxFiles.dataEndpoint(JSONObject()
            .put("link", "https://9000-box123.ap-shanghai.agentos-run.net/acp")))
        assertNull(SandboxFiles.dataEndpoint(JSONObject().put("link", "https://www.workbuddy.cn/acp")))
    }

    @Test fun `file gateway receives token and exact path without an injected username`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val path = "/root/output/中文 a+b.pdf"
        val bytes = byteArrayOf(0, 1, 2, -1)
        var query: String? = null
        var token: String? = null
        server.createContext("/files") { exchange ->
            query = exchange.requestURI.rawQuery
            token = exchange.requestHeaders.getFirst("X-Access-Token")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            assertArrayEquals(bytes, SandboxFiles.read("http://127.0.0.1:${server.address.port}", "session-token", path))
            assertEquals("path=$path", URLDecoder.decode(query, "UTF-8"))
            assertEquals("session-token", token)
            assertFalse(query.orEmpty().contains("username"))
        } finally { server.stop(0) }
    }

    @Test fun `gateway 404 remains distinguishable for session refresh`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/files") { it.sendResponseHeaders(404, -1); it.close() }
        server.start()
        try {
            try {
                SandboxFiles.read("http://127.0.0.1:${server.address.port}", "token", "/workspace/gone.pdf")
                fail("Expected a missing file error")
            } catch (e: SandboxFiles.ReadFailure) { assertEquals(404, e.status) }
        } finally { server.stop(0) }
    }

    @Test fun `listDir posts unary json with token and parses bare entries`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var contentType: String? = null
        var token: String? = null
        var requestPath: String? = null
        val directory = "/workspace/.codebuddy"
        val response = JSONObject().put("entries", org.json.JSONArray()
            .put(JSONObject().put("name", "配置").put("path", "$directory/配置").put("type", 2))
            .put(JSONObject().put("name", "report.md").put("path", "report.md")
                .put("type", "FILE_TYPE_FILE").put("size", 12)))
        server.createContext("/filesystem.Filesystem/ListDir") { exchange ->
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            token = exchange.requestHeaders.getFirst("X-Access-Token")
            requestPath = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = response.toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val entries = SandboxFiles.listDir("http://127.0.0.1:${server.address.port}", "dir-token", directory)
            assertEquals("application/json", contentType)
            assertEquals("dir-token", token)
            assertEquals(directory, JSONObject(requestPath).optString("path"))
            assertEquals(1, JSONObject(requestPath).getInt("depth"))
            assertEquals(2, entries.size)
            assertTrue(entries[0].isDir)
            assertEquals("配置", entries[0].name)
            assertFalse(entries[1].isDir)
            assertEquals("report.md", entries[1].name)
            assertEquals("$directory/report.md", entries[1].path)
            assertEquals(12L, entries[1].size)
        } finally { server.stop(0) }
    }

    @Test fun `an empty directory does not fall back to an unsupported format`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val count = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/filesystem.Filesystem/ListDir") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            if (count.incrementAndGet() == 1) {
                val json = "{}".toByteArray()
                exchange.sendResponseHeaders(200, json.size.toLong())
                exchange.responseBody.use { it.write(json) }
            } else {
                exchange.sendResponseHeaders(415, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val entries = SandboxFiles.listDir("http://127.0.0.1:${server.address.port}", "token", "/workspace/.codebuddy")
            assertTrue(entries.isEmpty())
            assertEquals(1, count.get())
        } finally { server.stop(0) }
    }

    @Test fun `listDir retries a 415 with explicit UTF-8 and unframed JSON`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val response = JSONObject().put("result", JSONObject().put("entries", org.json.JSONArray()
            .put(JSONObject().put("name", "a.txt").put("type", "FILE_TYPE_FILE"))))
        server.createContext("/filesystem.Filesystem/ListDir") { exchange ->
            val type = exchange.requestHeaders.getFirst("Content-Type")
            val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            calls.add(type to request)
            if (type != "application/json; charset=utf-8") {
                exchange.sendResponseHeaders(415, -1)
                exchange.close()
                return@createContext
            }
            val body = response.toString().toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val entries = SandboxFiles.listDir("http://127.0.0.1:${server.address.port}", "token", "/workspace")
            assertEquals(listOf("application/json", "application/json; charset=utf-8"), calls.map { it.first })
            assertEquals(calls[0].second, calls[1].second)
            assertEquals(1, entries.size)
            assertEquals("a.txt", entries.single().name)
            assertEquals("/workspace/a.txt", entries.single().path)
            assertFalse(entries.single().isDir)
        } finally { server.stop(0) }
    }
}
