package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** #770: [ServerConnectors] finds the type of the server at an address, and its connector. */
class ServerConnectorsTest {
    private val notJellyfin =
        ServerConnectException(listOf(ServerIssue(ServerIssue.Kind.NOT_THIS_SERVER_TYPE, "Emby")))
    private val outdated =
        ServerConnectException(listOf(ServerIssue(ServerIssue.Kind.OUTDATED_VERSION, "10.7.0")))
    private val nothing = ServerConnectException(emptyList())

    private fun found(name: String) =
        ResolvedServer(ServerInfo(id = name, name = name, version = null, product = null), ADDRESS)

    @Test
    fun `the second connector is tried when the first finds another kind of server`() =
        runBlocking {
            val jellyfin = FakeConnector { throw notJellyfin }
            val emby = FakeConnector { found("emby") }
            val connectors =
                ServerConnectors(mapOf(ServerType.EMBY to emby, ServerType.JELLYFIN to jellyfin))

            assertEquals(ServerType.EMBY to found("emby"), connectors.resolve(ADDRESS))
            assertEquals(listOf(ADDRESS), jellyfin.resolved)
            assertEquals(listOf(ADDRESS), emby.resolved)
        }

    @Test
    fun `the first connector that recognizes the server wins`() = runBlocking {
        val jellyfin = FakeConnector { found("jellyfin") }
        val emby = FakeConnector { found("emby") }
        val connectors =
            ServerConnectors(mapOf(ServerType.EMBY to emby, ServerType.JELLYFIN to jellyfin))

        assertEquals(ServerType.JELLYFIN to found("jellyfin"), connectors.resolve(ADDRESS))
        assertEquals(emptyList(), emby.resolved)
    }

    @Test
    fun `any other issue of the first connector is the answer`() = runBlocking {
        val jellyfin = FakeConnector { throw outdated }
        val emby = FakeConnector { found("emby") }
        val connectors =
            ServerConnectors(mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby))

        assertSame(
            outdated,
            assertFailsWith<ServerConnectException> { connectors.resolve(ADDRESS) },
        )
        assertEquals(emptyList(), emby.resolved)
    }

    @Test
    fun `a failure to reach the server is the answer`() = runBlocking {
        val unreachable = ServerException.Unreachable()
        val jellyfin = FakeConnector { throw unreachable }
        val emby = FakeConnector { found("emby") }
        val connectors =
            ServerConnectors(mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby))

        assertSame(unreachable, assertFailsWith<ServerException> { connectors.resolve(ADDRESS) })
        assertEquals(emptyList(), emby.resolved)
    }

    @Test
    fun `when none takes the server, the refusal of one that found a server is the answer`() =
        runBlocking {
            val jellyfin = FakeConnector { throw nothing }
            val emby = FakeConnector { throw notJellyfin }
            val connectors =
                ServerConnectors(mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby))

            assertSame(
                notJellyfin,
                assertFailsWith<ServerConnectException> { connectors.resolve(ADDRESS) },
            )
        }

    @Test
    fun `with one connector its own refusal is the answer, as before`() = runBlocking {
        for (refusal in listOf(notJellyfin, outdated, nothing)) {
            val connectors =
                ServerConnectors(mapOf(ServerType.JELLYFIN to FakeConnector { throw refusal }))

            assertSame(
                refusal,
                assertFailsWith<ServerConnectException> { connectors.resolve(ADDRESS) },
            )
        }
    }

    @Test
    fun `the connector of a type, of a stored type, and none for a type without an adapter`() {
        val jellyfin = FakeConnector { found("jellyfin") }
        val connectors = ServerConnectors(mapOf(ServerType.JELLYFIN to jellyfin))

        assertSame(jellyfin, connectors.of(ServerType.JELLYFIN))
        assertSame(jellyfin, connectors.ofStoredType("jellyfin"))
        // No server yet: Jellyfin, the type of every server before there were types.
        assertSame(jellyfin, connectors.ofStoredType(null))
        assertNull(connectors.ofStoredTypeOrNull("emby"))
        assertNull(connectors.ofStoredTypeOrNull("plex"))
        assertEquals(
            "emby",
            assertFailsWith<UnsupportedServerTypeException> { connectors.of(ServerType.EMBY) }
                .storedType,
        )
        assertEquals(
            "plex",
            assertFailsWith<UnsupportedServerTypeException> { connectors.ofStoredType("plex") }
                .storedType,
        )
    }

    @Test
    fun `discovery finds the servers of every connector`() = runBlocking {
        val jellyfin =
            FakeConnector(discovered = listOf(DiscoveredServer("1", "Home", ADDRESS))) {
                found("jellyfin")
            }
        val emby =
            FakeConnector(discovered = listOf(DiscoveredServer("2", "Cabin", "http://cabin"))) {
                found("emby")
            }
        val connectors =
            ServerConnectors(mapOf(ServerType.JELLYFIN to jellyfin, ServerType.EMBY to emby))

        assertEquals(setOf("Home", "Cabin"), connectors.discover().toList().map { it.name }.toSet())
    }

    /** Resolves with [onResolve] and records each address; the login calls are not used here. */
    private class FakeConnector(
        private val discovered: List<DiscoveredServer> = emptyList(),
        private val onResolve: (String) -> ResolvedServer,
    ) : ServerConnector {
        val resolved = mutableListOf<String>()

        override val capabilities: ServerCapabilities = ServerCapabilities.ALL

        override fun discover(): Flow<DiscoveredServer> = flowOf(*discovered.toTypedArray())

        override suspend fun resolve(address: String): ResolvedServer {
            resolved += address
            return onResolve(address)
        }

        override suspend fun findAddress(address: String, serverId: ServerId): String? = unused()

        override suspend fun loginDisclaimer(address: String): String? = unused()

        override suspend fun publicUsers(address: String): List<PublicUser> = unused()

        override suspend fun login(
            address: String,
            username: String,
            password: String,
        ): AuthenticatedUser = unused()

        override suspend fun quickConnectEnabled(address: String): Boolean = unused()

        override suspend fun startQuickConnect(address: String): QuickConnectState = unused()

        override suspend fun quickConnectState(address: String, secret: String): QuickConnectState =
            unused()

        override suspend fun loginWithQuickConnect(
            address: String,
            secret: String,
        ): AuthenticatedUser = unused()

        override suspend fun endSession(baseUrl: String, accessToken: String): Boolean = unused()

        private fun unused(): Nothing = throw NotImplementedError()
    }

    private companion object {
        const val ADDRESS = "http://127.0.0.1:8096"
    }
}
