package dev.jdtech.jellyfin.setup.data

import android.app.Application
import android.content.Context
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.ServerAddresses
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asServerAddressId
import dev.jdtech.jellyfin.testing.asServerId
import dev.jdtech.jellyfin.testing.asUserId
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#425): adding a server that is already saved keeps the API client signed in as that server's
 * current user. It used to clear the token, so every request failed with 401 until a restart. #761:
 * the server is found through a fake connector, and the client is a recording session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AddSavedServerSessionTest {
    private val servers = mutableMapOf<String, Server>()
    private val addresses = mutableListOf<ServerAddress>()
    private val users = mutableMapOf<UserId, User>()

    /** #988: runs once, right after the next address is saved: another write to the server row. */
    private var afterAddressInsert: (() -> Unit)? = null

    private val database =
        FakeRoles(StoredServers::class, ServerAddresses::class, StoredUsers::class) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "server" -> servers[args[0] as String]
                "serverWithAddresses" -> {
                    val server = servers.getValue(args[0] as String)
                    ServerWithAddresses(
                        server = server,
                        addresses = addresses.filter { it.serverId == server.id },
                        user = server.currentUserId?.let { users[it] },
                    )
                }
                "currentUser" -> servers[args[0] as String]?.currentUserId?.let { users[it] }
                "insertServer" -> {
                    val server = args[0] as Server
                    servers[server.id.asString()] = server
                    Unit
                }
                "insertAddress" -> {
                    addresses += args[0] as ServerAddress
                    afterAddressInsert?.let {
                        afterAddressInsert = null
                        it()
                    }
                    Unit
                }
                "setCurrentAddress" -> {
                    val id = args[0].asServerId().asString()
                    servers[id] =
                        servers
                            .getValue(id)
                            .copy(currentServerAddressId = args[1].asServerAddressId())
                    Unit
                }
                "insertUser" -> {
                    val user = args[0] as User
                    users[user.id] = user
                    Unit
                }
                "setCurrentUser" -> {
                    val id = args[0] as String
                    servers[id] = servers.getValue(id).copy(currentUserId = args[1].asUserId())
                    Unit
                }
                "toString" -> "FakeServerStore"
                else -> throw UnsupportedOperationException(method.kotlinName)
            }
        }

    private val context: Context by lazy { RuntimeEnvironment.getApplication() }

    /** What the connector finds at the next address. */
    private var found: ResolvedServer? = null

    private val connector = FakeServerConnector(onResolve = { found!! })
    private val session = RecordingServerSession()

    /** CE (#770): the type of the one connector installed; set before the first [add]. */
    private var connectorType = ServerType.JELLYFIN

    private val repository by lazy {
        SetupRepositoryImpl(
            ServerConnectors(mapOf(connectorType to connector)),
            database.role(),
            database.role(),
            database.role(),
            AppPreferences(context.getSharedPreferences("test", Context.MODE_PRIVATE)),
            session.active,
        )
    }

    private fun saveServer(id: String, address: String, user: User?, type: String = "jellyfin") {
        val serverAddress = ServerAddress(ServerAddressId.random(), id.toServerId(), address)
        servers[id] = Server(id.toServerId(), "Server $id", serverAddress.id, user?.id, type)
        addresses += serverAddress
        user?.let { users[it.id] = it }
    }

    private fun signInClient(address: String, user: User) {
        session.use(baseUrl = address, accessToken = user.accessToken, userId = user.id)
    }

    /** Adds [address], which the connector finds at [resolvedAddress]. */
    private suspend fun add(
        id: String,
        address: String,
        resolvedAddress: String = address,
    ): Server {
        found =
            ResolvedServer(
                info =
                    ServerInfo(
                        id = id.toServerId(),
                        name = "Server $id",
                        version = null,
                        product = null,
                    ),
                address = resolvedAddress,
            )
        return repository.addServer(address)
    }

    private val alice =
        User(
            UUID.randomUUID().toString().toUserId(),
            "alice",
            serverId = "a".toServerId(),
            accessToken = "token-a",
        )
    private val bob =
        User(
            UUID.randomUUID().toString().toUserId(),
            "bob",
            serverId = "b".toServerId(),
            accessToken = "token-b",
        )

    @Test
    fun `adding the current server again keeps its user signed in`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        add("a", "https://a.example/")

        assertEquals("https://a.example/", session.baseUrl)
        assertEquals("token-a", session.accessToken)
        assertEquals(alice.id, session.userId)
    }

    @Test
    fun `adding the current server by another address keeps its user signed in`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        // The connector completes the address typed: the client and Room get what it found.
        add("a", "a.local", resolvedAddress = "http://a.local:8096")

        assertEquals("http://a.local:8096", session.baseUrl)
        assertEquals("token-a", session.accessToken)
        assertEquals(alice.id, session.userId)
        assertEquals(
            listOf("https://a.example/", "http://a.local:8096"),
            addresses.filter { it.serverId.asString() == "a" }.map { it.address },
        )
    }

    /** The address Room says [id] uses, which the copy of the session at start follows. */
    private fun currentAddressInRoom(id: String): String? {
        val server = servers.getValue(id)
        return addresses.firstOrNull { it.id == server.currentServerAddressId }?.address
    }

    @Test
    fun `adding the current server by another address makes that address current in Room`() =
        runBlocking {
            // CE (#984): the client went there while Room kept the first address, so a restart
            // went back to the first one.
            saveServer("a", "http://a.local:8096", alice)
            signInClient("http://a.local:8096", alice)

            add("a", "https://a.example/")

            assertEquals("https://a.example/", session.baseUrl)
            assertEquals("https://a.example/", currentAddressInRoom("a"))
            // The server keeps its user: only the address changed.
            assertEquals(alice.id, servers.getValue("a").currentUserId)
        }

    @Test
    fun `signing in as another user through another address keeps that address in Room`() =
        runBlocking {
            // CE (#984): the steps of the issue. Add the saved server by its second address, then
            // sign in there as another user.
            saveServer("a", "http://a.local:8096", alice)
            signInClient("http://a.local:8096", alice)
            val carol = UUID.randomUUID().toString().toUserId()
            connector.onLogin = { name, _ ->
                AuthenticatedUser(carol, name, "a".toServerId(), "token-c", null)
            }

            add("a", "https://a.example/")
            repository.login("carol", "")

            assertEquals(listOf("login" to "https://a.example/"), connector.calls.toList())
            assertEquals("https://a.example/", session.baseUrl)
            assertEquals("token-c", session.accessToken)
            // What a restart reads: the same address and user as the client has now.
            assertEquals("https://a.example/", currentAddressInRoom("a"))
            assertEquals(carol, servers.getValue("a").currentUserId)
        }

    @Test
    fun `adding a server by another address keeps a sign-out that lands in between`() =
        runBlocking {
            // #988: the server row was read before the new address was saved; writing it back
            // with the new address restored the user a sign-out had just cleared.
            saveServer("a", "http://a.local:8096", alice)
            signInClient("http://a.local:8096", alice)
            afterAddressInsert = { servers["a"] = servers.getValue("a").copy(currentUserId = null) }

            add("a", "https://a.example/")

            assertEquals("https://a.example/", currentAddressInRoom("a"))
            assertNull(servers.getValue("a").currentUserId)
        }

    @Test
    fun `adding a server by an address it already has makes that one current`() = runBlocking {
        saveServer("a", "http://a.local:8096", alice)
        addresses += ServerAddress(ServerAddressId.random(), "a".toServerId(), "https://a.example/")
        signInClient("http://a.local:8096", alice)

        add("a", "https://a.example/")

        assertEquals("https://a.example/", session.baseUrl)
        assertEquals("https://a.example/", currentAddressInRoom("a"))
        assertEquals(2, addresses.count { it.serverId.asString() == "a" })
    }

    @Test
    fun `adding another saved server by another address makes that address its current one`() =
        runBlocking {
            saveServer("a", "https://a.example/", alice)
            saveServer("b", "http://b.local:8096", bob)
            signInClient("https://a.example/", alice)

            add("b", "https://b.example/")

            assertEquals("https://b.example/", session.baseUrl)
            assertEquals("https://b.example/", currentAddressInRoom("b"))
            assertEquals("https://a.example/", currentAddressInRoom("a"))
        }

    @Test
    fun `adding another saved server again signs in as that server's user only`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        saveServer("b", "https://b.example/", bob)
        signInClient("https://a.example/", alice)

        add("b", "https://b.example/")

        assertEquals("https://b.example/", session.baseUrl)
        assertEquals("token-b", session.accessToken)
        assertEquals(bob.id, session.userId)
    }

    @Test
    fun `adding a new server leaves the client without a user`() = runBlocking {
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)

        val server = add("c", "https://c.example/")

        assertEquals("https://c.example/", session.baseUrl)
        assertNull(session.accessToken)
        assertNull(session.userId)
        assertNull(servers.getValue("c").currentUserId)
        assertEquals("Server c", server.name)
    }

    @Test
    fun `a new server is saved with the type of the connector that found it`() = runBlocking {
        // CE (#770): Jellyfin today, as every server so far.
        add("c", "https://c.example/")

        assertEquals("jellyfin", servers.getValue("c").type)
        assertEquals("jellyfin", session.active.value.type)
    }

    @Test
    fun `the type is the one the connector found, whichever it is`() = runBlocking {
        connectorType = ServerType.EMBY
        saveServer("a", "https://a.example/", alice)
        signInClient("https://a.example/", alice)
        session.active.switchTo(session.active.value.copy(type = "jellyfin"))

        val server = add("c", "https://c.example/")

        assertEquals("emby", server.type)
        assertEquals("emby", servers.getValue("c").type)
        assertEquals("emby", session.active.value.type)
    }

    @Test
    fun `a saved server added again keeps the type it was saved with`() = runBlocking {
        saveServer("b", "https://b.example/", bob, type = "emby")

        add("b", "https://b.example/")

        assertEquals("emby", servers.getValue("b").type)
        assertEquals("emby", session.active.value.type)
    }
}
