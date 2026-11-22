package dev.jdtech.jellyfin.models

import java.io.IOException

/** How many causes are looked at, so a cause chain with a loop ends. */
private const val MAX_CAUSES = 16

private fun Throwable.causes(): Sequence<Throwable> =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

/**
 * #758: the [ServerException] this throwable is or wraps, up to 16 causes deep; null when there is
 * none. The adapters throw their failures as [ServerException], so code outside them reads this
 * instead of a server library's exceptions.
 *
 * A status anywhere in the chain wins over the other failures: the server answered, also when a
 * timeout wraps its answer. Among the others the first in the chain wins.
 *
 * This only reads the chain. A caller that catches `Exception` to branch on the result rethrows a
 * `CancellationException` first, as it would without this.
 */
fun Throwable.serverExceptionOrNull(): ServerException? {
    val found = causes().filterIsInstance<ServerException>().toList()
    return found.firstOrNull { it is ServerException.HttpStatus } ?: found.firstOrNull()
}

/**
 * CE (#775, #758): whether [this] means the request got no answer, as the reachability counts it.
 * An adapter throws a request that got none as [ServerException.Timeout] (an unknown host, a
 * refused connection, any timeout), [ServerException.Tls] or [ServerException.Unreachable] (no
 * address to ask), and leaves any other I/O failure (a reset connection) as it is, with the
 * [IOException] in its causes. An error status ([ServerException.HttpStatus]) came from a server
 * that answered, and a body that could not be read or a mapping failure is not about the
 * connection: those are answered.
 */
fun Throwable.isUnanswered(): Boolean {
    val causes = causes().toList()
    if (causes.any { it is ServerException.HttpStatus }) return false
    return causes.any {
        it is ServerException.Timeout ||
            it is ServerException.Tls ||
            it is ServerException.Unreachable ||
            it is IOException
    }
}
