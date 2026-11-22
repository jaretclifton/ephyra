package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.isUnanswered
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.MissingBaseUrlException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.jupiter.api.Test

/** #745: the SDK's exceptions as ours, so code outside the adapter reads no SDK type. */
class SdkErrorsTest {

    @Test
    fun `an error status becomes HttpStatus with its status`() {
        val sdk = InvalidStatusException(503, null)
        val server = assertIs<ServerException.HttpStatus>(sdk.asServerException())
        assertEquals(503, server.status)
        assertSame(sdk, server.cause)
    }

    @Test
    fun `a 401, also wrapped, becomes HttpStatus 401`() {
        // #763: moved from core's SyncWorkerDecisionsTest, which no longer sees the SDK.
        val wrapped = RuntimeException("wrap", InvalidStatusException(401, null))
        assertEquals(401, assertIs<ServerException.HttpStatus>(wrapped.asServerException()).status)
    }

    @Test
    fun `a timeout becomes Timeout`() {
        val sdk = TimeoutException("Connection failed", ConnectException("Failed to connect"))
        assertSame(sdk, assertIs<ServerException.Timeout>(sdk.asServerException()).cause)
    }

    @Test
    fun `a TLS failure becomes Tls`() {
        val sdk = SecureConnectionException("tls", SSLHandshakeException("handshake"))
        assertSame(sdk, assertIs<ServerException.Tls>(sdk.asServerException()).cause)
    }

    @Test
    fun `an SDK exception wrapped in two causes is found`() {
        val error =
            IllegalStateException(
                "outer",
                RuntimeException("middle", InvalidStatusException(404, null)),
            )
        assertEquals(404, assertIs<ServerException.HttpStatus>(error.asServerException()).status)

        val timeout = IOException("outer", RuntimeException("middle", TimeoutException("t", null)))
        assertIs<ServerException.Timeout>(timeout.asServerException())
    }

    @Test
    fun `our own exception is returned as it is, also wrapped`() {
        val ours = ServerException.Unreachable()
        assertSame(ours, ours.asServerException())
        assertSame(ours, RuntimeException("outer", ours).asServerException())
    }

    @Test
    fun `a status wins over a timeout that wraps it`() {
        val error = TimeoutException("t", InvalidStatusException(502, null))
        assertEquals(502, assertIs<ServerException.HttpStatus>(error.asServerException()).status)
    }

    @Test
    fun `the first of the other failures in the chain wins`() {
        val error = SecureConnectionException("tls", TimeoutException("t", null))
        assertIs<ServerException.Tls>(error.asServerException())
    }

    @Test
    fun `no address becomes Unreachable`() {
        val sdk = MissingBaseUrlException()
        assertSame(sdk, assertIs<ServerException.Unreachable>(sdk.asServerException()).cause)
    }

    /**
     * #758: what the SDK's OkHttp client throws, as the adapter's calls hand it on (translated, or
     * else as it is), counts as no answer exactly when it did before the stored lists' fallback
     * stopped reading the SDK's types (`isUnanswered`, #775).
     */
    @Test
    fun `the client's failures without an answer stay unanswered once translated`() {
        for (failure in
            listOf(
                TimeoutException("refused", ConnectException("Connection refused")),
                TimeoutException("unknown host", UnknownHostException("jellyfin.home")),
                TimeoutException("timeout", SocketTimeoutException("timeout")),
                SecureConnectionException("tls", SSLHandshakeException("handshake")),
                ApiClientException("reset", IOException("Connection reset")),
                MissingBaseUrlException(),
            )) {
            assertTrue((failure.asServerException() ?: failure).isUnanswered(), "$failure")
        }
    }

    @Test
    fun `an error status or a body that could not be read stays answered once translated`() {
        for (failure in
            listOf(
                InvalidStatusException(500, null),
                TimeoutException("t", InvalidStatusException(502, null)),
                InvalidContentException("bad body", SerializationException("missing field")),
            )) {
            assertFalse((failure.asServerException() ?: failure).isUnanswered(), "$failure")
        }
    }

    @Test
    fun `anything else is null`() {
        assertNull(IOException("reset").asServerException())
        assertNull(ApiClientException("reset", IOException("Connection reset")).asServerException())
    }

    @Test
    fun `only 16 causes are looked at`() {
        var error: Throwable = InvalidStatusException(500, null)
        repeat(16) { error = RuntimeException("wrap", error) }
        assertNull(error.asServerException())

        var within: Throwable = InvalidStatusException(500, null)
        repeat(15) { within = RuntimeException("wrap", within) }
        assertIs<ServerException.HttpStatus>(within.asServerException())
    }
}
