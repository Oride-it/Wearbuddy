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
}
