package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.PublicUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerId
import java.util.Collections
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The [ServerConnector] of [FakeMediaServer] (#771): one server, at [FakeLibrary.address], that
 * lets in [FakeLibrary.users]. It passes the connector contract suite
 * (`FakeMediaServerContractTest`).
 *
 * A Quick Connect request is approved with [approveQuickConnect], as a user would on another
 * device.
 */
class FakeServerConnector(
    private val library: FakeLibrary = ContractLibrary,
    override val capabilities: ServerCapabilities = ServerCapabilities.ALL,
    /** Whether the admin turned Quick Connect on; only asked when [capabilities] have it. */
    private val quickConnectOn: Boolean = true,
    /** Addresses where a server answers every request with HTTP 500. */
    private val failingAddresses: Set<String> = emptySet(),
) : ServerConnector {
    private val tokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Quick Connect requests by secret, with whether they were approved. */
    private val quickConnects: MutableMap<String, QuickConnectState> =
        Collections.synchronizedMap(mutableMapOf())

    private fun at(address: String): Boolean =
        address.trimEnd('/').equals(library.address.trimEnd('/'), ignoreCase = true)

    /** Nothing answers at another address. */
    private fun requireServer(address: String) {
        if (address in failingAddresses) throw ServerException.HttpStatus(500)
        if (!at(address)) throw ServerException.Unreachable()
    }

    override fun discover(): Flow<DiscoveredServer> =
        flowOf(DiscoveredServer(library.server.id, library.server.name, library.address))

    override suspend fun resolve(address: String): ResolvedServer {
        if (!at(address)) throw ServerConnectException(emptyList())
        return ResolvedServer(library.server, library.address)
    }

    override suspend fun findAddress(address: String, serverId: ServerId): String? =
        library.address.takeIf { at(address) && serverId == library.server.id }

    override suspend fun loginDisclaimer(address: String): String? {
        requireServer(address)
        return null
    }

    override suspend fun publicUsers(address: String): List<PublicUser> {
        requireServer(address)
        if (!capabilities.publicUsers) return emptyList()
        return library.users.keys.map { PublicUser(library.userId, it, primaryImageTag = null) }
    }

    override suspend fun login(
        address: String,
        username: String,
        password: String,
    ): AuthenticatedUser {
        requireServer(address)
        if (library.users[username] != password) throw ServerException.HttpStatus(401)
        return user(username)
    }

    private fun user(name: String): AuthenticatedUser {
        val token = Uuid.random().toHexString()
        tokens += token
        return AuthenticatedUser(
            id = library.userId,
            name = name,
            serverId = library.server.id,
            accessToken = token,
            primaryImageTag = null,
        )
    }

    override suspend fun quickConnectEnabled(address: String): Boolean {
        requireServer(address)
        return capabilities.quickConnect && quickConnectOn
    }

    override suspend fun startQuickConnect(address: String): QuickConnectState {
        if (!quickConnectEnabled(address)) throw ServerException.HttpStatus(401)
        val secret = Uuid.random().toHexString().repeat(2)
        val state =
            QuickConnectState(
                code = "%06d".format(quickConnects.size + 1),
                secret = secret,
                authenticated = false,
            )
        quickConnects[secret] = state
        return state
    }

    override suspend fun quickConnectState(address: String, secret: String): QuickConnectState {
        requireServer(address)
        return quickConnects[secret] ?: throw ServerException.HttpStatus(404)
    }

    /** Approves the Quick Connect request with [code], as the user does on another device. */
    fun approveQuickConnect(code: String) {
        synchronized(quickConnects) {
            val (secret, state) = quickConnects.entries.first { it.value.code == code }
            quickConnects[secret] = state.copy(authenticated = true)
        }
    }

    override suspend fun loginWithQuickConnect(address: String, secret: String): AuthenticatedUser {
        requireServer(address)
        val state = quickConnects[secret] ?: throw ServerException.HttpStatus(404)
        if (!state.authenticated) throw ServerException.HttpStatus(401)
        return user(library.users.keys.first())
    }

    override suspend fun endSession(baseUrl: String, accessToken: String): Boolean =
        at(baseUrl) && tokens.remove(accessToken)
}
