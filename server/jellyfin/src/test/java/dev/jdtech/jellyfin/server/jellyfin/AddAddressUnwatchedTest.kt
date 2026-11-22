package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#760): checking an address for a saved server is not a request to the server in use. Its
 * answers and failures must not reach the request watcher (the reachability banner) and the base
 * URL of the client in use stays as it is. #761: the check moved from the setup repository to
 * [JellyfinServerConnector.findAddress], which `SetupRepositoryImpl.addAddress` calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddAddressUnwatchedTest {
    private val serverId = "server"
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
    fun `checking an address does not reach the request watcher or the base url`() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        val jellyfinApi = JellyfinApi(context)
        val watcher = CountingWatcher()
        jellyfinApi.requestWatcher = watcher
        jellyfinApi.api.update(baseUrl = inUse)
        val connector = JellyfinServerConnector(jellyfinApi)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/System/Info/Public") { exchange ->
            val body =
                """{"Id":"$serverId","ServerName":"Ephyra Test","Version":"10.11.0",""" +
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
            // The SDK normalises the input: the trailing slash goes.
            assertEquals(stubAddress, connector.findAddress("$stubAddress/", serverId))
            // Another server's id is not a match.
            assertNull(connector.findAddress(stubAddress, "another server"))
            // A second address that nobody answers: failures must stay unseen as well.
            assertNull(connector.findAddress("http://127.0.0.1:9", serverId))

            assertEquals(0, watcher.calls.get())
            assertEquals(inUse, jellyfinApi.api.baseUrl)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
