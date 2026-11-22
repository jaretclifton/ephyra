package dev.jdtech.jellyfin.setup.data

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.server.CurrentServerType
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.models.ExceptionUiText
import dev.jdtech.jellyfin.models.ExceptionUiTexts
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.AddressRejected
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #761: the repository keeps the bookkeeping in Room and talks to the server through the connector.
 * The messages for a refused address are the ones the SDK's issues gave, calls go to the address
 * the client uses, and the client is switched where it was before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupRepositoryConnectorTest {
    private val context: Context by lazy { RuntimeEnvironment.getApplication() }

    private val alice = User(UUID.randomUUID(), "alice", serverId = "a", accessToken = "token-a")
    private val inRoom = ServerAddress(UUID.randomUUID(), "a", "https://a.example")
    private val other = ServerAddress(UUID.randomUUID(), "a", "http://a.local:8096")
    private var server = Server("a", "Server a", inRoom.id, alice.id)
    private val users = mutableMapOf(alice.id to alice)
    private val addresses = mutableListOf(inRoom, other)

    /** The Room calls in order, with their first argument. */
    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val database =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServer" -> server.takeIf { it.id == args[0] }
                "getUser" -> users[args[0] as UserId]
                "getAddress" -> addresses.single { it.id == args[0] }
                "getServerWithAddresses" -> ServerWithAddresses(server, addresses.toList(), null)
                "getServerWithAddressAndUser" ->
                    ServerWithAddressAndUser(
                        server,
                        addresses.single { it.id == server.currentServerAddressId },
                        server.currentUserId?.let { users[it] },
                    )
                "insertUser" -> {
                    val user = args[0] as User
                    users[user.id] = user
                    writes += "insertUser ${user.name}"
                    Unit
                }
                "updateServerCurrentUser" -> {
                    server = server.copy(currentUserId = args[1] as UserId)
                    writes += "updateServerCurrentUser ${args[0]}"
                    Unit
                }
                "clearServerCurrentUser" -> {
                    server = server.copy(currentUserId = null)
                    writes += "clearServerCurrentUser ${args[0]}"
                    Unit
                }
                "updateServer" -> {
                    server = args[0] as Server
                    writes += "updateServer"
                    Unit
                }
                "deleteUser" -> {
                    users.remove(args[0] as UserId)
                    writes += "deleteUser"
                    Unit
                }
                "insertServerAddress" -> {
                    addresses += args[0] as ServerAddress
                    writes += "insertServerAddress ${(args[0] as ServerAddress).address}"
                    Unit
                }
                "toString" -> "FakeServerDatabaseDao"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ServerDatabaseDao

    private val connector = FakeServerConnector()

    /** Points at another address than Room's current one, as after re-adding the server. */
    private val session =
        RecordingServerSession(baseUrl = other.address, accessToken = "token-a", userId = alice.id)

    private val preferences by lazy {
        AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)).also {
            it.setValue(it.currentServer, "a")
        }
    }

    private val currentType = CurrentServerType()

    private val repository by lazy {
        SetupRepositoryImpl(
            ServerConnectors(mapOf(ServerType.JELLYFIN to connector)),
            session,
            database,
            preferences,
            currentType,
        )
    }

    /** A message as its string resource and arguments; one thrown as ExceptionUiText is single. */
    private data class Message(val resId: Int, val args: List<Any>, val single: Boolean = false)

    private fun UiText.message(single: Boolean = false) =
        (this as UiText.StringResource).let { Message(it.resId, it.args.toList(), single) }

    private fun messagesOf(error: Throwable): List<Message> =
        when (error) {
            is ExceptionUiText -> listOf(error.uiText.message(single = true))
            is ExceptionUiTexts -> error.uiTexts.map { it.message() }
            else -> throw error
        }

    private fun refusal(vararg issues: ServerIssue): List<Message> {
        connector.onResolve = { throw ServerConnectException(issues.toList()) }
        val error = runCatching { runBlocking { repository.addServer("jf.example") } }
        return messagesOf(error.exceptionOrNull()!!)
    }

    @Test
    fun `a refused address says what the issues said`() {
        assertEquals(
            listOf(Message(SetupR.string.add_server_error_not_found, emptyList(), single = true)),
            refusal(),
        )
        assertEquals(
            listOf(Message(SetupR.string.add_server_error_no_id, emptyList(), single = true)),
            refusal(ServerIssue(ServerIssue.Kind.NO_ID)),
        )
        assertEquals(
            listOf(
                Message(SetupR.string.add_server_error_outdated, listOf("10.7.0")),
                Message(SetupR.string.add_server_error_version, listOf("10.7.0")),
                Message(SetupR.string.add_server_error_not_jellyfin, listOf("Emby Server")),
                Message(SetupR.string.add_server_error_not_jellyfin, listOf("")),
                Message(SetupR.string.add_server_error_slow, listOf("4200")),
                Message(CoreR.string.unknown_error, emptyList()),
            ),
            refusal(
                ServerIssue(ServerIssue.Kind.OUTDATED_VERSION, "10.7.0"),
                ServerIssue(ServerIssue.Kind.UNSUPPORTED_VERSION, "10.7.0"),
                ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, "Emby Server"),
                ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, ""),
                ServerIssue(ServerIssue.Kind.SLOW_RESPONSE, "4200"),
                ServerIssue(ServerIssue.Kind.UNKNOWN),
            ),
        )
    }

    @Test
    fun `a blank address is refused before the connector is asked`() {
        val error = runCatching { runBlocking { repository.addServer("  ") } }
        assertEquals(
            listOf(Message(SetupR.string.add_server_error_empty_address, emptyList(), true)),
            messagesOf(error.exceptionOrNull()!!),
        )
    }

    @Test
    fun `a login goes to the client's address, then saves the user, then signs the client in`() =
        runBlocking {
            val bob = UUID.randomUUID()
            connector.onLogin = { username, _ ->
                writes += "login $username"
                AuthenticatedUser(bob, "bob", "a", "token-b", "tag")
            }

            repository.login("bob", "secret")

            assertEquals(listOf("login" to other.address), connector.calls.toList())
            assertEquals(
                listOf("login bob", "insertUser bob", "updateServerCurrentUser a"),
                writes.toList(),
            )
            assertEquals(User(bob, "bob", "a", "token-b", "tag"), users[bob])
            assertEquals(
                listOf(RecordingServerSession.Use(other.address, "token-b", bob)),
                session.uses.toList(),
            )
        }

    @Test
    fun `the public users come from the client's address and belong to the server asked about`() =
        runBlocking {
            val id = UUID.randomUUID()
            connector.onPublicUsers = { listOf(PublicUser(id, "carol", null)) }

            val publicUsers = repository.getPublicUsers("b")

            assertEquals(listOf("publicUsers" to other.address), connector.calls.toList())
            assertEquals(listOf(User(id, "carol", serverId = "b")), publicUsers)
        }

    @Test
    fun `removing the signed-in user signs it out and clears the client's token`() = runBlocking {
        connector.onEndSession = { token ->
            writes += "endSession $token"
            true
        }

        repository.deleteUser(alice.id)

        assertEquals(listOf("endSession" to inRoom.address), connector.calls.toList())
        assertEquals(
            listOf("endSession token-a", "clearServerCurrentUser a", "deleteUser"),
            writes.toList(),
        )
        assertEquals(
            listOf(RecordingServerSession.Use(other.address, null, null)),
            session.uses.toList(),
        )
    }

    @Test
    fun `switching user keeps the address and takes the user's token`() = runBlocking {
        val bob = User(UUID.randomUUID(), "bob", serverId = "a", accessToken = "token-b")
        users[bob.id] = bob

        repository.setCurrentUser(bob.id)

        assertEquals(bob.id, server.currentUserId)
        assertEquals(
            listOf(RecordingServerSession.Use(other.address, "token-b", bob.id)),
            session.uses.toList(),
        )
    }

    @Test
    fun `switching address keeps the user and the token`() = runBlocking {
        session.use(inRoom.address, "token-a", alice.id)
        session.uses.clear()

        repository.setCurrentAddress(other.id)

        assertEquals(other.id, server.currentServerAddressId)
        assertEquals(
            listOf(RecordingServerSession.Use(other.address, "token-a", alice.id)),
            session.uses.toList(),
        )
    }

    @Test
    fun `switching server signs in as its current user at its current address`() = runBlocking {
        repository.setCurrentServer("a")

        assertEquals(
            listOf(RecordingServerSession.Use(inRoom.address, "token-a", alice.id)),
            session.uses.toList(),
        )
    }

    @Test
    fun `switching server puts its type on the client's`() = runBlocking {
        // CE (#770): any stored type, also one this build has no adapter for.
        server = server.copy(type = "emby")
        currentType.stored = "jellyfin"

        repository.setCurrentServer("a")

        assertEquals("emby", currentType.stored)
    }

    @Test
    fun `the login screens get nothing for a type without an adapter`() = runBlocking {
        server = server.copy(type = "emby")
        currentType.stored = "emby"

        assertEquals(ServerCapabilities.NONE, repository.capabilities)
        assertEquals(
            "emby",
            assertFailsWith<UnsupportedServerTypeException> { repository.loadDisclaimer() }
                .storedType,
        )
        // Removing its login still works, without signing it out on the server.
        repository.deleteUser(alice.id)
        assertEquals(listOf("clearServerCurrentUser a", "deleteUser"), writes.toList())
    }

    @Test
    fun `adding an address checks it unwatched and leaves the client alone`() = runBlocking {
        connector.onFindAddress = { address, serverId ->
            "http://a.lan:8096".takeIf { address == "a.lan" && serverId == "a" }
        }

        val added = repository.addAddress("a", "a.lan")

        assertEquals("http://a.lan:8096", added.address)
        assertEquals(listOf("insertServerAddress http://a.lan:8096"), writes.toList())
        assertEquals(
            AddressRejected.Reason.ALREADY_SAVED,
            assertFailsWith<AddressRejected> { repository.addAddress("a", "a.lan") }.reason,
        )
        assertEquals(
            AddressRejected.Reason.NOT_FOUND,
            assertFailsWith<AddressRejected> { repository.addAddress("a", "b.lan") }.reason,
        )
        assertEquals(listOf("a.lan", "a.lan", "b.lan"), connector.findAddressCalls.toList())
        assertTrue(session.uses.isEmpty())
    }
}
