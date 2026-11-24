package dev.jdtech.jellyfin.player.local.engine.mpv

/**
 * CE (#960): when to load a file again over mpv's libcurl backend because FFmpeg's TLS could not
 * finish the handshake.
 *
 * FFmpeg's mbedTLS glue (`libavformat/tls_mbedtls.c`, every version up to FFmpeg 9.1) hands mbedTLS
 * an own certificate and key even when none was given, so both are empty. A server that asks for a
 * client certificate, also one that only asks and does not require it (Caddy's `client_auth` mode
 * `verify_if_given`, nginx's `ssl_verify_client optional`), then makes mbedTLS try to sign with the
 * empty key, and the handshake fails before the server sees anything. On TLS 1.3 FFmpeg logs "TLS
 * handshake failed.", on TLS 1.2 the mbedTLS code: -0x3e80 (PK_BAD_INPUT_DATA, mbedTLS 3.6.7 and
 * 3.6.8) or -0x6600 (SSL_ILLEGAL_PARAMETER, mbedTLS 3.6.6). ExoPlayer and libcurl send an empty
 * certificate list instead, which such a server accepts.
 *
 * So after such a failure the player switches to libcurl, once, and loads the item again. It stays
 * on libcurl for the rest of the player's life: every file comes from the same server. libcurl
 * gives up [MpvStreamBreak]'s and [MpvErrorRecovery]'s recognition of a lost connection, as it logs
 * its errors under "curl" (#654), which is why it is not the default: only a server FFmpeg cannot
 * play from at all gets it.
 *
 * Not thread-safe: [MPVPlayer] calls it on the main thread.
 */
internal class MpvTlsFallback {

    /** The player switched to libcurl. */
    var onCurl = false
        private set

    /**
     * A file failed to open with [errors] (mpv's error lines for it, oldest first). Returns true
     * when it should be loaded again over libcurl: the first time FFmpeg's TLS fails the handshake
     * in the way a request for a client certificate makes it fail.
     */
    fun retryOverCurl(errors: List<String>): Boolean {
        if (onCurl || errors.none(::isClientCertificateFailure)) return false
        onCurl = true
        return true
    }

    companion object {
        private val CLIENT_CERTIFICATE_FAILURES =
            setOf(
                "ffmpeg: tls: TLS handshake failed.",
                "ffmpeg: tls: mbedtls_ssl_handshake returned -0x3e80",
                "ffmpeg: tls: mbedtls_ssl_handshake returned -0x6600",
            )

        /**
         * FFmpeg's TLS lines for the handshake that a request for a client certificate breaks. A
         * connection that drops during the handshake logs another line ("TLS handshake was aborted
         * by peer", a read error), so a network problem does not switch the player to libcurl.
         */
        fun isClientCertificateFailure(error: String): Boolean =
            error in CLIENT_CERTIFICATE_FAILURES
    }
}
