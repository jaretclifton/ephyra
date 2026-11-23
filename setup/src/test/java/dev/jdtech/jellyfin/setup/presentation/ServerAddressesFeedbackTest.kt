package dev.jdtech.jellyfin.setup.presentation

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.data.FakeServerConnector
import dev.jdtech.jellyfin.setup.data.SetupRepositoryImpl
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesAction
import dev.jdtech.jellyfin.setup.presentation.addresses.ServerAddressesViewModel
import dev.jdtech.jellyfin.testing.asServerAddressId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * CE (#390): removing the address in use and adding an address that is already there were dropped
 * without a word, so the dialog closed and nothing happened. The screen now says why. #761: the
 * address check is a fake connector; the connector's own check against a stand-in server is
 * `AddAddressUnwatchedTest` in `data`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerAddressesFeedbackTest {
    private val serverId = "server"
    private val inUse =
        ServerAddress(ServerAddressId.random(), serverId.toServerId(), "http://10.0.2.2:8096")
    private val other =
        ServerAddress(ServerAddressId.random(), serverId.toServerId(), "http://192.168.1.2:8096")
    private val addresses = Collections.synchronizedList(mutableListOf(inUse, other))
    private val deleted = Collections.synchronizedList(mutableListOf<ServerAddressId>())

    /** Thrown by getServerWithAddresses once set. */
    @Volatile private var failure: RuntimeException? = null

    // The repository calls only these; suspend functions that do not suspend can return directly.
    private val database =
        fakeProxy(ServerStore::class.java.classLoader, arrayOf(ServerStore::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "currentAddress" -> inUse
                // CE (#770): for its type, which picks the connector.
                "server" -> Server(serverId.toServerId(), "Ephyra Test", inUse.id, null)
                "deleteAddress" -> {
                    deleted.add(args[0].asServerAddressId())
                    addresses.removeAll { it.id == args[0].asServerAddressId() }
                    Unit
                }
                "serverWithAddresses" -> {
                    failure?.let { throw it }
                    ServerWithAddresses(
                        server = Server(serverId.toServerId(), "Ephyra Test", inUse.id, null),
                        addresses = addresses.toList(),
                        user = null,
                    )
                }
                "toString" -> "FakeServerStore"
                else -> throw UnsupportedOperationException(method.kotlinName)
            }
        }
            as ServerStore

    /** The address the saved server answers at; nothing answers anywhere else. */
    private val stubAddress = "http://127.0.0.1:8096"

    // Like the SDK, the connector drops a trailing slash.
    private val connector =
        FakeServerConnector(
            onFindAddress = { address, id ->
                address.trimEnd('/').takeIf { it == stubAddress && id.asString() == serverId }
            }
        )

    private val localNetworkAccess = FakeLocalNetworkAccess()
    private lateinit var viewModel: ServerAddressesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        val repository =
            SetupRepositoryImpl(
                ServerConnectors(mapOf(ServerType.JELLYFIN to connector)),
                database,
                AppPreferences(application.getSharedPreferences("test", Context.MODE_PRIVATE)),
                ActiveServerHolder(),
            )
        viewModel = ServerAddressesViewModel(repository, localNetworkAccess)
        viewModel.loadAddresses(serverId.toServerId())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `removing the address in use is refused with a message`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.DeleteAddress(inUse.id))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.ce_server_address_in_use, message.resId)
        assertTrue(deleted.isEmpty())
        assertEquals(listOf(inUse, other), viewModel.state.value.addresses)
    }

    @Test
    fun `removing another address still removes it, without a message`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.DeleteAddress(other.id))

        withTimeout(5_000) { viewModel.state.first { it.addresses == listOf(inUse) } }
        assertEquals(listOf(other.id), deleted.toList())
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `adding an address that is already there says so`() = runBlocking {
        addresses.add(ServerAddress(ServerAddressId.random(), serverId.toServerId(), stubAddress))

        viewModel.onAction(ServerAddressesAction.AddAddress("$stubAddress/"))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.ce_server_address_already_added, message.resId)
        assertEquals(3, addresses.size)
        assertEquals(listOf("$stubAddress/"), connector.findAddressCalls.toList())
    }

    @Test
    fun `any other failure is told in plain words, not the exception's text`() = runBlocking {
        // CE (#606): the server answers, then the address list cannot be read.
        failure = IllegalStateException("Cannot access database on the main thread")

        viewModel.onAction(ServerAddressesAction.AddAddress(stubAddress))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(CoreR.string.unknown_error, message.resId)
    }

    // CE (#376): nothing answers at these, so no server is found either way.
    @Test
    fun `a local address that fails while access is denied names the permission`() = runBlocking {
        localNetworkAccess.denied = true

        viewModel.onAction(ServerAddressesAction.AddAddress("http://[fd00::1]:9"))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.ce_add_server_error_local_network, message.resId)
    }

    @Test
    fun `an address that is not local keeps server not found while access is denied`() =
        runBlocking {
            localNetworkAccess.denied = true

            viewModel.onAction(ServerAddressesAction.AddAddress("http://127.0.0.1:9"))

            val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
            val message = error.single() as UiText.StringResource
            assertEquals(SetupR.string.add_server_error_not_found, message.resId)
        }

    @Test
    fun `a local address that fails with access granted keeps server not found`() = runBlocking {
        viewModel.onAction(ServerAddressesAction.AddAddress("http://[fd00::1]:9"))

        val error = withTimeout(5_000) { viewModel.state.first { it.error != null } }.error!!
        val message = error.single() as UiText.StringResource
        assertEquals(SetupR.string.add_server_error_not_found, message.resId)
    }
}
