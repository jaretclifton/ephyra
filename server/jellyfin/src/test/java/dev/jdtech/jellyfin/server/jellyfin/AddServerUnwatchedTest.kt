package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.models.asString
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#819): adding a server is not traffic of the server in use. The discovery requests of
 * [JellyfinServerConnector.resolve], answered or not, must not reach the request watcher (the
 * reachability banner and the return detector), and the base URL and token of the client in use
 * stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddServerUnwatchedTest {
    private val inUse = "http://10.0.2.2:8096"

    private class CountingWatcher : RequestWatcher {
        val calls = AtomicInteger()

        override fun onStarted() {
            calls.incrementAndGet()
        }

        override fun onAnswered() {
            calls.incrementAndGet()
        }

        override fun onUnanswered() {
            calls.incrementAndGet()
        }

        override fun onCancelled() {
            calls.incrementAndGet()
        }
    }

    @Test
    fun `resolving a new server does not reach the request watcher, the base url or the token`() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
            val jellyfinApi = JellyfinApi(context)
            val watcher = CountingWatcher()
            jellyfinApi.requestWatcher = watcher
            jellyfinApi.api.update(baseUrl = inUse, accessToken = "token-in-use")
            val connector = JellyfinServerConnector(jellyfinApi)

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/System/Info/Public") { exchange ->
                val body =
                    """{"Id":"new","ServerName":"Ephyra Test","Version":"12.4.0",""" +
                        """"ProductName":"Jellyfin Server","StartupWizardCompleted":true}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val executor = Executors.newCachedThreadPool()
            server.executor = executor
            server.start()
            try {
                val stubAddress = "http://127.0.0.1:${server.address.port}"
                val resolved = connector.resolve("$stubAddress/")
                assertEquals("new", resolved.info.id.asString())
                assertEquals(stubAddress, resolved.address)
                // An address that nobody answers: failures must stay unseen as well.
                assertFailsWith<ServerConnectException> { connector.resolve("http://127.0.0.1:9") }

                assertEquals(0, watcher.calls.get())
                assertEquals(inUse, jellyfinApi.api.baseUrl)
                assertEquals("token-in-use", jellyfinApi.api.accessToken)
            } finally {
                server.stop(0)
                executor.shutdownNow()
            }
        }
}
