package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.data.FakeServerConnector
import dev.jdtech.jellyfin.setup.data.SetupRepositoryImpl
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel
import dev.jdtech.jellyfin.testing.asServerAddressId
import dev.jdtech.jellyfin.testing.asServerId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#984): a tap on the Addresses screen makes that address the server's current one, as it did
 * before upstream's Compose screen left the tap empty. For the server in use the app moves there as
 * well; for another server only Room changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerAddressSelectTest {
    private val a = "a".toServerId()
    private val b = "b".toServerId()
    private val aLan = ServerAddress(ServerAddressId.random(), a, "http://10.0.2.2:8096")
    private val aHttps = ServerAddress(ServerAddressId.random(), a, "https://a.example")
    private val bLan = ServerAddress(ServerAddressId.random(), b, "http://192.168.1.2:8096")
    private val bHttps = ServerAddress(ServerAddressId.random(), b, "https://b.example")
    private val addresses = listOf(aLan, aHttps, bLan, bHttps)
    private val servers =
        ConcurrentHashMap(
            mapOf(
                a to Server(a, "Server a", aLan.id, null),
                b to Server(b, "Server b", bLan.id, null),
            )
        )

    private val database =
        fakeProxy(ServerStore::class.java.classLoader, arrayOf(ServerStore::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "server" -> servers[args[0].asServerId()]
                "address" -> addresses.single { it.id == args[0].asServerAddressId() }
                "currentAddress" -> {
                    val server = servers.getValue(args[0].asServerId())
                    addresses.single { it.id == server.currentServerAddressId }
                }
                "updateServer" -> {
                    val server = args[0] as Server
                    servers[server.id] = server
                    Unit
                }
                "serverWithAddresses" -> {
                    val server = servers.getValue(args[0].asServerId())
                    ServerWithAddresses(server, addresses.filter { it.serverId == server.id }, null)
                }
                "toString" -> "FakeServerStore"
                else -> throw UnsupportedOperationException(method.kotlinName)
            }
        }
            as ServerStore

    /** The app talks to server a at its LAN address. */
    private val active =
        ActiveServerHolder(
            ActiveServer(
                type = "jellyfin",
                serverId = a,
                baseUrl = aLan.address,
                accessToken = "token-a",
                userId = "alice".toUserId(),
            )
        )

    private lateinit var viewModel: ServerAddressesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        val preferences =
            AppPreferences(application.getSharedPreferences("test", Context.MODE_PRIVATE))
        preferences.setCurrentServerId(a)
        val repository =
            SetupRepositoryImpl(
                ServerConnectors(mapOf(ServerType.JELLYFIN to FakeServerConnector())),
                database,
                preferences,
                active,
            )
        viewModel = ServerAddressesViewModel(repository, FakeLocalNetworkAccess())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen marks the address the server uses`() = runBlocking {
        viewModel.loadAddresses(a)

        val state = withTimeout(5_000) { viewModel.state.first { it.addresses.isNotEmpty() } }
        assertEquals(aLan.id, state.currentAddressId)
    }

    @Test
    fun `a tap on an address of the server in use moves the app there and saves it`() =
        runBlocking {
            viewModel.loadAddresses(a)

            viewModel.onAction(ServerAddressesAction.OnServerClick(aHttps.id))

            withTimeout(5_000) { viewModel.state.first { it.currentAddressId == aHttps.id } }
            assertEquals(aHttps.id, servers.getValue(a).currentServerAddressId)
            assertEquals(aHttps.address, active.value.baseUrl)
            assertEquals("token-a", active.value.accessToken)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `a tap on an address of another server saves it and leaves the app where it is`() =
        runBlocking {
            val before = active.value
            viewModel.loadAddresses(b)

            viewModel.onAction(ServerAddressesAction.OnServerClick(bHttps.id))

            withTimeout(5_000) { viewModel.state.first { it.currentAddressId == bHttps.id } }
            assertEquals(bHttps.id, servers.getValue(b).currentServerAddressId)
            assertEquals(aLan.id, servers.getValue(a).currentServerAddressId)
            assertEquals(before, active.value)
        }
}
