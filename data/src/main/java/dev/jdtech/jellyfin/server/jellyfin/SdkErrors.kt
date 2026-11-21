package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ServerException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException

/** How many causes are looked at, so a cause chain with a loop ends. */
private const val MAX_CAUSES = 16

/**
 * The [ServerException] this throwable is or wraps: a [ServerException] anywhere in the cause chain
 * (up to 16 causes), or the SDK's InvalidStatusException, TimeoutException or
 * SecureConnectionException translated. Null for anything else.
 *
 * A status anywhere in the chain wins over the other failures: the server answered, also when a
 * timeout or a TLS failure wraps its answer. Among the others the first in the chain wins.
 *
 * This only reads the chain. A caller that catches `Exception` to branch on the result rethrows a
 * `CancellationException` first, as it would without this.
 */
fun Throwable.asServerException(): ServerException? {
    val found =
        generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSES)
            .mapNotNull { it.translated() }
            .toList()
    return found.firstOrNull { it is ServerException.HttpStatus } ?: found.firstOrNull()
}

private fun Throwable.translated(): ServerException? =
    when (this) {
        is ServerException -> this
        is InvalidStatusException -> ServerException.HttpStatus(status, this)
        is TimeoutException -> ServerException.Timeout(this)
        is SecureConnectionException -> ServerException.Tls(this)
        else -> null
    }
