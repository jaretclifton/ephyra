package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StandInServer
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #763: the requests behind the session's checks, as `ServerSessionKeeper` and `ServerReachability`
 * made them on the client before: `/Users/Me` for the token, where only a 401 says it is gone, and
 * the probe of `/System/Info/Public`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerSessionRequestTest {
    private var server: StatusServer? = null
    private val api = JellyfinApi(StandInServer.context())
    private val session = JellyfinServerSession(api)

    @After fun stop() = server?.close() ?: Unit

    private fun answering(status: Int, body: String = ""): StatusServer =
        StatusServer(status, body).also {
            server?.close()
            server = it
            session.use(it.baseUrl, TOKEN, USER)
        }

    @Test
    fun `a token the server accepts is valid`() = runBlocking {
        val server = answering(200, USER_JSON)

        assertTrue(session.isTokenValid())
        assertEquals(listOf("GET /Users/Me"), server.requests)
    }

    @Test
    fun `a 401 to Users Me says the token is not valid, and the token is reported`() = runBlocking {
        answering(401)
        val refused = async(start = CoroutineStart.UNDISPATCHED) { session.unauthorized.first() }

        assertFalse(session.isTokenValid())
        assertEquals(TOKEN, withTimeout(5_000) { refused.await() })
    }

    @Test
    fun `another error status cannot say, so it throws`() = runBlocking {
        answering(500)

        val error = assertFailsWith<ServerException.HttpStatus> { session.isTokenValid() }
        assertEquals(500, error.status)
    }

    @Test
    fun `a 403 is not a 401`() = runBlocking {
        answering(403)

        val error = assertFailsWith<ServerException.HttpStatus> { session.isTokenValid() }
        assertEquals(403, error.status)
    }

    @Test
    fun `no answer cannot say, so it throws`() = runBlocking {
        // A port nobody listens on any more.
        val closed = answering(200).also { it.close() }
        session.use(closed.baseUrl, TOKEN, USER)

        assertFailsWith<ServerException> { session.isTokenValid() }
        Unit
    }

    @Test
    fun `the probe asks for the public info`() = runBlocking {
        val server = answering(200, "{}")

        assertEquals(true, session.probe())
        assertEquals(listOf("GET /System/Info/Public"), server.requests)
    }

    @Test
    fun `an error status below 500 is an answer, a server error is not`() = runBlocking {
        answering(401)
        assertEquals(true, session.probe())

        answering(502)
        assertEquals(false, session.probe())
    }

    @Test
    fun `no server, no probe`() = runBlocking {
        session.use(null, null, null)

        assertNull(session.probe())
    }

    /** Answers every request with [status] and [body], one connection per request. */
    private class StatusServer(private val status: Int, private val body: String) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        /** Method and path of each request. */
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = runCatching { socket.accept() }.getOrNull() ?: break
                    connection.use {
                        val reader = it.getInputStream().bufferedReader()
                        val line = reader.readLine().orEmpty().split(" ")
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        requests += "${line.getOrElse(0) { "" }} ${line.getOrElse(1) { "" }}"
                        val bytes = body.toByteArray()
                        it.getOutputStream()
                            .write(
                                ("HTTP/1.1 $status Status\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray() + bytes
                            )
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        init {
            // As in StandInServer: the SDK logs through android.util.Log, the tests have no SLF4J.
            System.setProperty("kotlin-logging-to-android-native", "true")
        }

        const val TOKEN = "token"
        val USER: UserId = "00000000-0000-0000-0000-000000000001".toUserId()
        const val USER_JSON =
            "{\"Id\":\"00000000-0000-0000-0000-000000000001\",\"Name\":\"User\"," +
                "\"HasPassword\":false,\"HasConfiguredPassword\":false," +
                "\"HasConfiguredEasyPassword\":false,\"EnableAutoLogin\":false}"
    }
}
