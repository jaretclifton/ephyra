package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.serverExceptionOrNull
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * CE (#377): what a screen that failed to load tells the user. Never the exception's own text: it
 * holds class names, hosts and addresses, and the user can't act on it.
 */
object LoadErrorText {
    /** The reason in plain words. */
    fun reason(error: Throwable): UiText {
        // A status anywhere in the chain wins, also over a timeout that wraps it.
        val status = error.serverExceptionOrNull() as? ServerException.HttpStatus
        return when {
            status != null ->
                UiText.StringResource(CoreR.string.ce_error_server_status, status.status)
            isConnectionError(error) ->
                UiText.StringResource(CoreR.string.download_error_server_unreachable)
            else -> UiText.StringResource(CoreR.string.unknown_error)
        }
    }

    /** Whether the server could not be reached at all, so checking the connection may help. */
    fun isConnectionError(error: Throwable): Boolean =
        when (error.serverExceptionOrNull()) {
            // The server answered.
            is ServerException.HttpStatus -> false
            is ServerException.Timeout,
            is ServerException.Tls,
            is ServerException.Unreachable -> true
            null ->
                error.causes().any {
                    it is SocketException ||
                        it is SocketTimeoutException ||
                        it is UnknownHostException ||
                        it is SSLException
                }
        }

    /** The full trace for a bug report, with anything that looks like a token blanked out. */
    fun shareText(error: Throwable): String = redact(error.stackTraceToString())

    private val secret =
        Regex(
            """((?:api_?key|access_?token|token)["']?\s*[=:]\s*["']?)[^\s&"',;]+""",
            RegexOption.IGNORE_CASE,
        )

    fun redact(text: String): String = text.replace(secret) { "${it.groupValues[1]}******" }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16)
}
