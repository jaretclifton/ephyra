package dev.jdtech.jellyfin.models

import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** #758: reading a failure without knowing which server library threw it. */
class ServerFailuresTest {

    @Test
    fun `our exception is found as it is, also wrapped`() {
        val ours = ServerException.Timeout(ConnectException("refused"))
        assertSame(ours, ours.serverExceptionOrNull())
        assertSame(
            ours,
            RuntimeException("outer", IOException("middle", ours)).serverExceptionOrNull(),
        )
    }

    @Test
    fun `a status wins over a failure that wraps it`() {
        val error = ServerException.Timeout(ServerException.HttpStatus(502))
        assertEquals(
            502,
            assertIs<ServerException.HttpStatus>(error.serverExceptionOrNull()).status,
        )
    }

    @Test
    fun `the first of the other failures in the chain wins`() {
        val error = ServerException.Tls(ServerException.Timeout())
        assertIs<ServerException.Tls>(error.serverExceptionOrNull())
    }

    @Test
    fun `anything else is null`() {
        assertNull(IOException("reset").serverExceptionOrNull())
        assertNull(IllegalStateException("x", RuntimeException()).serverExceptionOrNull())
    }

    @Test
    fun `only 16 causes are looked at`() {
        var error: Throwable = ServerException.HttpStatus(500)
        repeat(16) { error = RuntimeException("wrap", error) }
        assertNull(error.serverExceptionOrNull())
        assertFalse(error.isUnanswered())

        var within: Throwable = ServerException.HttpStatus(500)
        repeat(15) { within = RuntimeException("wrap", within) }
        assertIs<ServerException.HttpStatus>(within.serverExceptionOrNull())
    }

    @Test
    fun `no answer is a timeout, a TLS failure, no address or an I O failure`() {
        for (failure in
            listOf(
                ServerException.Timeout(ConnectException("Connection refused")),
                ServerException.Tls(SSLHandshakeException("handshake")),
                ServerException.Unreachable(),
                RuntimeException("reset", IOException("Connection reset")),
                IOException("unreachable"),
            )) {
            assertTrue(failure.isUnanswered(), "$failure")
        }
    }

    @Test
    fun `a status is an answer, also inside a failure that would not be`() {
        assertFalse(ServerException.HttpStatus(500).isUnanswered())
        assertFalse(ServerException.Timeout(ServerException.HttpStatus(502)).isUnanswered())
        assertFalse(IOException("read", ServerException.HttpStatus(401)).isUnanswered())
    }

    @Test
    fun `a failure that is not about the connection is an answer`() {
        assertFalse(IllegalStateException("mapping failed").isUnanswered())
        assertFalse(RuntimeException("bad body", IllegalArgumentException("field")).isUnanswered())
        assertFalse(NullPointerException().isUnanswered())
    }
}
