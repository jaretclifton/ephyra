package dev.jdtech.jellyfin.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.MissingBaseUrlException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import timber.log.Timber

/**
 * CE (#281): how long a request waits for the server when Room already has a copy to show instead.
 * Short, so an unreachable server (VPN off, away from home) costs a few seconds and never the full
 * connect timeout before the downloaded copy shows.
 */
const val STORED_FALLBACK_TIMEOUT_MS = 3_000L

/**
 * CE (#281): the server's answer, or the copy stored in Room when the server fails or takes longer
 * than [timeoutMs]. Downloaded items and the shows and seasons they belong to are in Room, so they
 * open and play while the server is unreachable.
 *
 * [stored] is null when Room has nothing: then this is just [server], with its usual timeout and
 * its errors. The stored copy is only built when it is needed.
 */
suspend fun <T> serverOrStored(
    stored: (suspend () -> T)?,
    timeoutMs: Long = STORED_FALLBACK_TIMEOUT_MS,
    server: suspend () -> T,
): T {
    if (stored == null) return server()
    val fresh =
        try {
            // Boxed, so a server answer of null is told apart from the timeout.
            withTimeoutOrNull(timeoutMs) { Fresh(server()) }
                ?: null.also {
                    Timber.w("Server took over %d ms, using the stored copy", timeoutMs)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Server unavailable, using the stored copy")
            null
        }
    return if (fresh != null) fresh.value else stored()
}

/**
 * CE (#281): [serverOrStored] for a list, where an empty [stored] list means Room has nothing (the
 * series or season has no downloads), so the server's errors still surface.
 */
suspend fun <T> serverOrStoredList(
    stored: List<T>,
    timeoutMs: Long = STORED_FALLBACK_TIMEOUT_MS,
    server: suspend () -> List<T>,
): List<T> =
    serverOrStored(stored.takeIf { it.isNotEmpty() }?.let { suspend { it } }, timeoutMs, server)

/**
 * CE (#775): [server], or [stored] when the server could not be reached. For a list whose stored
 * version is shorter than the server's (the downloaded favourites, not all of them), so unlike
 * [serverOrStored] there is no timeout of its own: a slow server is waited for, and an error the
 * server answered with (an HTTP status) surfaces. Only a request that got no answer falls back.
 * That failure also tells the server reachability, which probes and then shows the banner.
 *
 * [stored] is null when Room has nothing: then this is just [server].
 */
suspend fun <T> serverOrStoredWhenUnanswered(
    stored: (suspend () -> T)?,
    server: suspend () -> T,
): T {
    if (stored == null) return server()
    return try {
        server()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!e.isUnanswered()) throw e
        Timber.w(e, "Server unavailable, using the stored list")
        stored()
    }
}

/**
 * CE (#775): whether [this] means the request got no answer, as `JellyfinApi.watchResponses` tells
 * the reachability. The SDK's client turns an unknown host, a refused connection and any timeout
 * into its [TimeoutException], a TLS failure into [SecureConnectionException], and any other I/O
 * failure (a reset connection) into a plain `ApiClientException` around the [IOException]. An error
 * status ([InvalidStatusException]) or a body it could not read (`InvalidContentException`) came
 * from a server that answered, and a mapping failure is a bug: those surface. No address
 * ([MissingBaseUrlException]) has no server to ask either.
 */
internal fun Throwable.isUnanswered(): Boolean {
    val causes = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16)
    if (causes.any { it is InvalidStatusException || it is InvalidContentException }) return false
    return causes.any {
        it is TimeoutException ||
            it is SecureConnectionException ||
            it is MissingBaseUrlException ||
            it is IOException
    }
}

private class Fresh<T>(val value: T)
