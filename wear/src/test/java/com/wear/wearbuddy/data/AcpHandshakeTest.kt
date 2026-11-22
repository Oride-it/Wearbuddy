package com.wear.wearbuddy.data

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import javax.net.ssl.SSLPeerUnverifiedException

class AcpHandshakeTest {
    private class Link(val token: Int) : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun expiredEndpointAndInterruptedReplayRecoverBeforeReturningTheLoadedLink() {
        val opened = mutableListOf<Link>()
        val waits = mutableListOf<Long>()
        val loaded = openLoadedAcpLink(
            sessionForAttempt = { it },
            open = { token ->
                if (token == 0) throw AcpHttpException(403, "连接")
                Link(token).also { opened.add(it) }
            },
            load = { _, token ->
                if (token == 1) throw SocketTimeoutException("connection lost during session/load")
            },
            pause = { waits.add(it) }
        )
        assertEquals(listOf(500L, 1_000L), waits)
        assertTrue(opened.first().closed)
        assertFalse(loaded.closed)
        assertEquals(2, loaded.token)
    }

    @Test fun permanentFailuresCloseTheTransportWithoutStartingAnotherSession() {
        for (failure in listOf(AcpHttpException(400, "initialize"), IllegalStateException("invalid protocol"),
                SSLPeerUnverifiedException("certificate mismatch"), SessionExpiredException("signed out"))) {
            val link = Link(0)
            val thrown = assertThrows(Exception::class.java) {
                openLoadedAcpLink({ it }, { link }, { _, _ -> throw failure }, { fail("unexpected retry") })
            }
            assertSame(failure, thrown)
            assertTrue(link.closed)
        }
    }

    @Test fun exhaustedReplayRetriesCloseEveryStreamAndReturnTheLastFailure() {
        val opened = mutableListOf<Link>()
        val failure = assertThrows(SocketTimeoutException::class.java) {
            openLoadedAcpLink({ it }, { Link(it).also(opened::add) },
                { _, token -> throw SocketTimeoutException("attempt $token") }, {})
        }
        assertEquals("attempt 2", failure.message)
        assertEquals(3, opened.size)
        assertTrue(opened.all { it.closed })
    }
}
