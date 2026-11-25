package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.ServerAddresses
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.data.FakeServerConnector
import dev.jdtech.jellyfin.setup.data.SetupRepositoryImpl
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asServerAddressId
import dev.jdtech.jellyfin.testing.asServerId
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val addresses = Collections.synchronizedList(mutableListOf(aLan, aHttps, bLan, bHttps))
    private val servers =
        ConcurrentHashMap(
            mapOf(
                a to Server(a, "Server a", aLan.id, null),
                b to Server(b, "Server b", bLan.id, null),
            )
        )

    private val database =
        FakeRoles(StoredServers::class, ServerAddresses::class, StoredUsers::class) {
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
                "deleteAddress" -> {
                    addresses.removeAll { it.id == args[0].asServerAddressId() }
                    Unit
                }
                "setCurrentAddress" -> {
                    val id = args[0].asServerId()
                    servers[id] =
                        servers
                            .getValue(id)
                            .copy(currentServerAddressId = args[1].asServerAddressId())
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

    /** Once set, the next server write waits for it after writing: a selection paused there. */
    @Volatile private var pauseAfterWrite: CompletableDeferred<Unit>? = null

    /** Completed when a write has paused on [pauseAfterWrite]. */
    private val pausedAtWrite = CompletableDeferred<Unit>()

    /** Once set, the next read of a current address waits for it after reading. */
    @Volatile private var pauseAfterCurrentRead: CompletableDeferred<Unit>? = null

    /** Completed when a read has paused on [pauseAfterCurrentRead]. */
    private val pausedAtCurrentRead = CompletableDeferred<Unit>()

    private val storedServers: StoredServers =
        object : StoredServers by database.role() {
            override suspend fun setCurrentAddress(serverId: ServerId, addressId: ServerAddressId) {
                database.role<StoredServers>().setCurrentAddress(serverId, addressId)
                pauseAfterWrite?.let {
                    pauseAfterWrite = null
                    pausedAtWrite.complete(Unit)
                    it.await()
                }
            }
        }

    private val serverAddresses: ServerAddresses =
        object : ServerAddresses by database.role() {
            override suspend fun currentAddress(serverId: ServerId): ServerAddress? {
                val current = database.role<ServerAddresses>().currentAddress(serverId)
                pauseAfterCurrentRead?.let {
                    pauseAfterCurrentRead = null
                    pausedAtCurrentRead.complete(Unit)
                    it.await()
                }
                return current
            }
        }

    private lateinit var preferences: AppPreferences
    private lateinit var repository: SetupRepositoryImpl
    private lateinit var viewModel: ServerAddressesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        preferences = AppPreferences(application.getSharedPreferences("test", Context.MODE_PRIVATE))
        preferences.setCurrentServerId(a)
        repository =
            SetupRepositoryImpl(
                ServerConnectors(mapOf(ServerType.JELLYFIN to FakeServerConnector())),
                storedServers,
                serverAddresses,
                database.role(),
                preferences,
                active,
            )
        viewModel = ServerAddressesViewModel(repository, FakeLocalNetworkAccess())
    }

    @Test
    fun `two selections at once leave Room and the app on the same address`() = runBlocking {
        // The first selection has written Room and not yet the app when the second comes in.
        val paused = CompletableDeferred<Unit>()
        pauseAfterWrite = paused
        val first = launch(Dispatchers.Unconfined) { repository.setCurrentAddress(aHttps.id) }
        val second = launch(Dispatchers.Unconfined) { repository.setCurrentAddress(aLan.id) }
        paused.complete(Unit)
        first.join()
        second.join()

        val inRoom = addresses.single { it.id == servers.getValue(a).currentServerAddressId }
        assertEquals(inRoom.address, active.value.baseUrl)
        assertEquals(aLan.address, active.value.baseUrl)
    }

    @Test
    fun `a selection while the app is on another server leaves the app alone`() = runBlocking {
        // The holder is on b already; the preference still says a, as for a moment in a switch.
        val onB =
            ActiveServer(
                type = "jellyfin",
                serverId = b,
                baseUrl = bLan.address,
                accessToken = "token-b",
                userId = "bob".toUserId(),
            )
        active.switchTo(onB)

        repository.setCurrentAddress(aHttps.id)

        assertEquals(aHttps.id, servers.getValue(a).currentServerAddressId)
        assertEquals(onB, active.value)
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
    fun `a selection waits for a removal under way, so the current address is never removed`() =
        runBlocking {
            // The removal has checked that the address is not in use when the tap selects it.
            viewModel.loadAddresses(a)
            withTimeout(5_000) { viewModel.state.first { it.addresses.isNotEmpty() } }
            val removal = CompletableDeferred<Unit>()
            pauseAfterCurrentRead = removal

            viewModel.onAction(ServerAddressesAction.DeleteAddress(aHttps.id))
            withTimeout(5_000) { pausedAtCurrentRead.await() }
            viewModel.onAction(ServerAddressesAction.OnServerClick(aHttps.id))
            removal.complete(Unit)

            // The selection, after the removal, finds the address gone and says so.
            withTimeout(5_000) { viewModel.state.first { it.error != null } }
            val current = servers.getValue(a).currentServerAddressId
            assertEquals(aLan.id, current)
            assertEquals(aLan.address, active.value.baseUrl)
        }

    @Test
    fun `removing an address while it is being selected is refused`() = runBlocking {
        // A removal waits for the selection that is under way, so it sees the address in use.
        viewModel.loadAddresses(a)
        val selection = CompletableDeferred<Unit>()
        pauseAfterWrite = selection

        viewModel.onAction(ServerAddressesAction.OnServerClick(aHttps.id))
        // The selection has written Room and holds the screen's lock when the removal comes in.
        withTimeout(5_000) { pausedAtWrite.await() }
        viewModel.onAction(ServerAddressesAction.DeleteAddress(aHttps.id))
        selection.complete(Unit)

        withTimeout(5_000) { viewModel.state.first { it.error != null } }
        assertEquals(aHttps.id, servers.getValue(a).currentServerAddressId)
        assertEquals(true, addresses.any { it.id == aHttps.id })
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
