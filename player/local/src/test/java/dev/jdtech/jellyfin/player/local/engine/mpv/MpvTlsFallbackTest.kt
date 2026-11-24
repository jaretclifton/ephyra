package dev.jdtech.jellyfin.player.local.engine.mpv

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MpvTlsFallbackTest {

    private val fallback = MpvTlsFallback()

    // The lines mpv logged on an emulator against Caddy with client_auth verify_if_given (#960).
    private val tls13 = "ffmpeg: tls: TLS handshake failed."
    private val tls12 = "ffmpeg: tls: mbedtls_ssl_handshake returned -0x3e80"
    private val tls12Mbedtls366 = "ffmpeg: tls: mbedtls_ssl_handshake returned -0x6600"
    private val openFailed =
        "stream: Failed to open https://jellyfin.example/Videos/1/stream?static=true."

    @Test
    fun `a handshake broken by a request for a client certificate loads again over curl`() {
        for (line in listOf(tls13, tls12, tls12Mbedtls366)) {
            val fallback = MpvTlsFallback()

            assertTrue(fallback.retryOverCurl(listOf(line, openFailed)), line)
            assertTrue(fallback.onCurl)
        }
    }

    @Test
    fun `only once, so a failure over curl is reported`() {
        assertTrue(fallback.retryOverCurl(listOf(tls13, openFailed)))

        assertFalse(fallback.retryOverCurl(listOf(tls13, openFailed)))
        assertTrue(fallback.onCurl)
    }

    @Test
    fun `a connection that cannot be made or breaks stays on ffmpeg`() {
        val errors =
            listOf(
                "ffmpeg: tcp: Connection to tcp://10.0.2.2:8443 failed: Connection refused",
                "ffmpeg: tls: TLS handshake was aborted by peer.",
                "ffmpeg: tls: mbedtls_ssl_handshake returned -0x4c",
                "ffmpeg: http: HTTP error 404 Not Found",
                openFailed,
            )

        assertFalse(fallback.retryOverCurl(errors))
        assertFalse(fallback.onCurl)
    }

    @Test
    fun `a file that fails without an error line stays on ffmpeg`() {
        assertFalse(fallback.retryOverCurl(emptyList()))
    }
}
