package com.wear.wearbuddy.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal class AcpHttpException(val status: Int, operation: String) :
    IllegalStateException("ACP $operation 失败：HTTP $status")

/** Retry only endpoint acquisition and initialize/load. A prompt must never run inside this loop. */
internal fun <Session, Link : AutoCloseable> openLoadedAcpLink(
    sessionForAttempt: (Int) -> Session,
    open: (Session) -> Link,
    load: (Link, Session) -> Unit,
    pause: (Long) -> Unit = Thread::sleep
): Link {
    for (attempt in 0..2) {
        if (attempt != 0) pause(500L * attempt)
        try {
            val session = sessionForAttempt(attempt)
            val link = open(session)
            try {
                load(link, session)
                return link
            } catch (failure: Throwable) {
                link.close()
                throw failure
            }
        } catch (failure: Exception) {
            val recoverable = when (failure) {
                is AcpHttpException -> failure.status in setOf(401, 403, 404, 408, 409, 410, 425, 429) ||
                    failure.status in 500..599
                is SSLHandshakeException, is SSLPeerUnverifiedException, is MalformedURLException -> false
                is SocketTimeoutException -> true
                is InterruptedIOException -> false
                is IOException -> true
                else -> false
            }
            if (!recoverable || attempt == 2) throw failure
        }
    }
    error("Unreachable")
}
