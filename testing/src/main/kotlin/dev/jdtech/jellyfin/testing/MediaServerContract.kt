package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.isUnanswered
import dev.jdtech.jellyfin.testing.ContractLibrary.Part
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * What the contract suites of the ports of a [MediaServer] share (#771): how a subclass builds the
 * implementation over [ContractLibrary], and over servers that fail.
 */
abstract class MediaServerContract {
    /** A server that presents [ContractLibrary]. Its `capabilities` decide the gated tests. */
    abstract fun server(): MediaServer

    /** A server that answers every request with HTTP 500. */
    abstract fun failingServer(): MediaServer

    /** A server at an address where nothing answers: the connection is refused. */
    abstract fun unreachableServer(): MediaServer

    /** A server that takes every request and never answers it. */
    abstract fun stallingServer(): MediaServer

    /** The parts of the library the server of [server] does not hold; their tests are skipped. */
    open val notOnServer: Set<Part> = emptySet()

    protected val library = ContractLibrary

    protected fun assumeOnServer(part: Part) =
        assumeTrue("the server has no ${part.name.lowercase()}", part !in notOnServer)

    /** [call] fails with an error status, the server's answer. */
    protected suspend fun assertErrorStatus(call: suspend () -> Any?) {
        val error = assertFailsWith<ServerException> { call() }
        assertIs<ServerException.HttpStatus>(error)
    }

    /**
     * [call] fails as a request that got no answer: a [ServerException] that `isUnanswered` counts
     * as such (see its KDoc: a refused connection is a `Timeout`).
     */
    protected suspend fun assertUnanswered(call: suspend () -> Any?) {
        val error = assertFailsWith<ServerException> { call() }
        assertTrue(error.isUnanswered(), "not unanswered: $error")
    }

    /**
     * [call], cancelled while it waits for the server, ends with a [CancellationException], not
     * with a failure of its own: a screen that is left stops its requests quietly.
     */
    protected suspend fun assertCancellable(call: suspend () -> Any?) {
        val outcome = CompletableDeferred<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            try {
                call()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
                throw e
            }
        }
        try {
            withContext(Dispatchers.Default) {
                // Long enough for the request to reach the server.
                delay(500)
                job.cancel()
                val error = withTimeout(10_000) { outcome.await() }
                assertIs<CancellationException>(error, "the cancelled call ended with $error")
            }
        } finally {
            scope.cancel()
        }
    }
}
