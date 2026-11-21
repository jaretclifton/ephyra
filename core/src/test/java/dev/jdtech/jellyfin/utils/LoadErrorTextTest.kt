package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UiText
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.junit.jupiter.api.Test

/** CE (#377): a failed load is explained in plain words, and Share never sends a token. */
class LoadErrorTextTest {
    private fun UiText.resource() = this as UiText.StringResource

    @Test
    fun `a server error names its status`() {
        val text = LoadErrorText.reason(ServerException.HttpStatus(503)).resource()

        assertEquals(CoreR.string.ce_error_server_status, text.resId)
        assertEquals(listOf<Any>(503), text.args.toList())
        assertFalse(LoadErrorText.isConnectionError(ServerException.HttpStatus(503)))
    }

    @Test
    fun `a 401 is a server error too`() {
        val text = LoadErrorText.reason(ServerException.HttpStatus(401)).resource()

        assertEquals(CoreR.string.ce_error_server_status, text.resId)
        assertEquals(listOf<Any>(401), text.args.toList())
    }

    @Test
    fun `a status wins over a timeout that wraps it`() {
        // The SDK's own types, as the Jellyfin adapter still throws them (#745).
        val error = TimeoutException("Connection failed", InvalidStatusException(502, null))

        val text = LoadErrorText.reason(error).resource()

        assertEquals(CoreR.string.ce_error_server_status, text.resId)
        assertEquals(listOf<Any>(502), text.args.toList())
        assertFalse(LoadErrorText.isConnectionError(error))
    }

    @Test
    fun `an unreachable server says it can't be reached`() {
        val error = ServerException.Unreachable()

        assertEquals(
            CoreR.string.download_error_server_unreachable,
            LoadErrorText.reason(error).resource().resId,
        )
        assertTrue(LoadErrorText.isConnectionError(ServerException.Tls()))
    }

    @Test
    fun `a refused connection says the server can't be reached`() {
        // The SDK's own timeout, as the Jellyfin adapter still throws it (#745).
        val error =
            TimeoutException(
                "Connection failed",
                ConnectException("Failed to connect to demo.jellyfin.org/203.0.113.7:443"),
            )

        val text = LoadErrorText.reason(error).resource()

        assertEquals(CoreR.string.download_error_server_unreachable, text.resId)
        assertTrue(LoadErrorText.isConnectionError(error))
    }

    @Test
    fun `an unknown host says the server can't be reached`() {
        val error = UnknownHostException("Unable to resolve host \"jellyfin.example\"")

        assertEquals(
            CoreR.string.download_error_server_unreachable,
            LoadErrorText.reason(error).resource().resId,
        )
    }

    @Test
    fun `anything else gets the generic text, never its own message`() {
        val text = LoadErrorText.reason(IllegalStateException("Expected BEGIN_OBJECT")).resource()

        assertEquals(CoreR.string.unknown_error, text.resId)
        assertTrue(text.args.isEmpty())
    }

    @Test
    fun `shared text keeps the trace and blanks tokens`() {
        val error =
            IllegalArgumentException(
                "GET https://example.org/Items?api_key=abc123&userId=1 " +
                    "Authorization: MediaBrowser Client=\"Ephyra\", Token=\"def456\" " +
                    "X-Emby-Token: ghi789 ApiKey=jkl012"
            )

        val shared = LoadErrorText.shareText(error)

        assertTrue(shared.contains("IllegalArgumentException"))
        assertTrue(shared.contains("\tat "))
        listOf("abc123", "def456", "ghi789", "jkl012").forEach {
            assertFalse(shared.contains(it), message = "$it leaked")
        }
        assertTrue(shared.contains("userId=1"))
        assertTrue(shared.contains("Client=\"Ephyra\""))
    }
}
