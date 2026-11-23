package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import android.provider.Settings
import com.sun.net.httpserver.HttpServer
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.asString
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #761: the connector against a local stand-in for a server. A server it finds keeps what it says
 * about itself, nothing answering is an exception without issues, and a refused Quick Connect keeps
 * its status, which tells the screens Quick Connect is off or the code expired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerConnectorRequestTest {
    private val context = RuntimeEnvironment.getApplication()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The Authorization header of every request but the public info. */
    private val authorizations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    // The SDK also tries https on the same port, which would block the default single thread.
    private val executor = Executors.newCachedThreadPool()
    /** The version the stand-in gives. */
    @Volatile private var version = "12.4.0"

    private val address
        get() = "http://127.0.0.1:${server.address.port}"

    private lateinit var api: JellyfinApi
    private lateinit var connector: JellyfinServerConnector

    @Before
    fun setUp() {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            if (exchange.requestURI.path != "/System/Info/Public") {
                authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            }
            if (exchange.requestURI.path == "/System/Info/Public") {
                val body =
                    """{"Id":"abc123","ServerName":"Ephyra Test","Version":"$version",""" +
                        """"ProductName":"Jellyfin Server","StartupWizardCompleted":true}"""
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } else {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
        }
        server.executor = executor
        server.start()
        api = JellyfinApi(context)
        connector = JellyfinServerConnector(api)
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `a server that answers is resolved with what it says about itself`() = runBlocking {
        val resolved = connector.resolve(address)

        assertEquals(address, resolved.address)
        assertEquals("abc123", resolved.info.id.asString())
        assertEquals("Ephyra Test", resolved.info.name)
        assertEquals("12.4.0", resolved.info.version)
        assertEquals("Jellyfin Server", resolved.info.product)
    }

    @Test
    fun `a server the SDK scores OK is refused with its issues`() = runBlocking {
        // SDK 1.9.4 scores this version OK, with UnsupportedServerVersion.
        version = "10.11.0"

        val error = assertFailsWith<ServerConnectException> { connector.resolve(address) }

        assertEquals(
            listOf(ServerIssue(ServerIssue.Kind.UNSUPPORTED_VERSION, "10.11.0")),
            error.issues,
        )
    }

    @Test
    fun `an address where nothing answers has no issues`() = runBlocking {
        val error =
            assertFailsWith<ServerConnectException> { connector.resolve("http://127.0.0.1:9") }

        assertTrue(error.issues.isEmpty())
    }

    @Test
    fun `a refused quick connect keeps its status`() = runBlocking {
        api.api.update(baseUrl = address)

        val error =
            assertFailsWith<ServerException.HttpStatus> { connector.startQuickConnect(address) }

        assertEquals(401, error.status)
        assertTrue(paths.any { it.startsWith("/QuickConnect") }, "$paths")
    }

    @Test
    fun `a call for the client's address sends its token`() = runBlocking {
        api.api.update(baseUrl = address, accessToken = "token-in-use")

        assertFailsWith<ServerException.HttpStatus> { connector.quickConnectEnabled(address) }

        assertTrue(authorizations.single().contains("token-in-use"), "$authorizations")
    }

    @Test
    fun `a call for another address leaves the client alone and sends no token`() = runBlocking {
        api.api.update(baseUrl = "http://10.0.2.2:8096", accessToken = "token-in-use")

        assertFailsWith<ServerException.HttpStatus> { connector.quickConnectEnabled(address) }

        assertTrue(paths.any { it.startsWith("/QuickConnect") }, "$paths")
        assertTrue(authorizations.none { it.contains("token-in-use") }, "$authorizations")
        assertEquals("http://10.0.2.2:8096", api.api.baseUrl)
        assertEquals("token-in-use", api.api.accessToken)
    }
}
