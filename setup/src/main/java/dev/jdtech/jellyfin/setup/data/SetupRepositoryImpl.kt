package dev.jdtech.jellyfin.setup.data

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.server.ServerConnectors
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.AuthenticatedUser
import dev.jdtech.jellyfin.domain.ports.ResolvedServer
import dev.jdtech.jellyfin.domain.ports.ServerConnectException
import dev.jdtech.jellyfin.domain.ports.ServerConnector
import dev.jdtech.jellyfin.domain.ports.ServerIssue
import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.ExceptionUiText
import dev.jdtech.jellyfin.models.ExceptionUiTexts
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerAddressId
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.domain.AddressRejected
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The saved servers, addresses and users in Room. Talking to a server goes through [connectors]
 * (#761), the one of the server's type (#770), and [active] is the server and user the app talks
 * to.
 *
 * #899: putting another server on (adding one, switching to one) writes its type, address, token
 * and user to [active] as one value. A login, a user or an address switch keeps the server, so its
 * type, and changes the value in one write too ([ActiveServerHolder.onSameServer]).
 */
class SetupRepositoryImpl(
    private val connectors: ServerConnectors,
    private val serverStore: ServerStore,
    private val appPreferences: AppPreferences,
    private val active: ActiveServerHolder,
) : SetupRepository {
    /** The login screens' server; none for a type this build has no adapter for. */
    override val capabilities: ServerCapabilities
        get() =
            connectors.ofStoredTypeOrNull(active.value.type)?.capabilities
                ?: ServerCapabilities.NONE

    override fun discoverServers(): Flow<DiscoveredServer> {
        return connectors.discover()
    }

    /**
     * #770: the connector of the server on the client, which the login screens are about. Throws
     * [dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException] for a type this build has
     * no adapter for, which the screens show as their error.
     */
    private fun clientConnector(): ServerConnector = connectors.ofStoredType(active.value.type)

    override suspend fun getServers(): List<ServerWithAddresses> {
        return serverStore.serversWithAddresses()
    }

    override suspend fun getCurrentServer(): Server? {
        return appPreferences.currentServerId()?.let { id -> serverStore.server(id) }
    }

    override suspend fun deleteServer(serverId: ServerId) {
        // CE (#334): the saved logins go with the server, so sign them out there too.
        coroutineScope {
            serverStore
                .users(serverId)
                .map { user -> async { endSessionOnServer(user) } }
                .awaitAll()
        }
        serverStore.deleteServer(serverId)
    }

    /**
     * CE (#334): ends [user]'s session on their server and revokes the token. Removing a login used
     * to leave both behind: the token stayed valid and the server kept listing the session.
     */
    private suspend fun endSessionOnServer(user: User) {
        val token = user.accessToken ?: return
        val saved = serverStore.serverWithAddressAndUser(user.serverId) ?: return
        val address = saved.address ?: return
        // CE (#770): a server of a type this build cannot talk to is still removed, without this.
        val connector = connectors.ofStoredTypeOrNull(saved.server.type) ?: return
        connector.endSession(baseUrl = address.address, accessToken = token)
    }

    /**
     * #761: the address the client uses, which the login screens are about. CE (#984): it is the
     * server's current address in Room as well: re-adding a saved server by another address makes
     * that one current in both.
     */
    private fun clientAddress(): String = checkNotNull(active.value.baseUrl) { "No server is set" }

    override suspend fun getIsQuickConnectEnabled(): Boolean =
        withContext(Dispatchers.IO) { clientConnector().quickConnectEnabled(clientAddress()) }

    override suspend fun initiateQuickConnect(): QuickConnectState =
        withContext(Dispatchers.IO) { clientConnector().startQuickConnect(clientAddress()) }

    override suspend fun getQuickConnectState(secret: String): QuickConnectState =
        withContext(Dispatchers.IO) { clientConnector().quickConnectState(clientAddress(), secret) }

    override suspend fun setCurrentServer(serverId: ServerId) {
        val serverWithAddressAndUser = serverStore.serverWithAddressAndUser(serverId) ?: return
        val serverAddress = serverWithAddressAndUser.address ?: return
        val user = serverWithAddressAndUser.user

        active.switchTo(
            ActiveServer(
                type = serverWithAddressAndUser.server.type,
                serverId = serverWithAddressAndUser.server.id,
                baseUrl = serverAddress.address,
                accessToken = user?.accessToken,
                userId = user?.id,
            )
        )
    }

    override suspend fun addServer(address: String): Server {
        // Check if address is not blank
        if (address.isBlank()) {
            throw ExceptionUiText(
                UiText.StringResource(SetupR.string.add_server_error_empty_address)
            )
        }

        val (type, resolved) =
            try {
                connectors.resolve(address)
            } catch (e: ServerConnectException) {
                throw e.toUiTextException()
            }
        return saveServerInDatabase(type, resolved)
    }

    private fun ServerConnectException.toUiTextException(): Exception =
        when {
            issues.isEmpty() ->
                ExceptionUiText(UiText.StringResource(SetupR.string.add_server_error_not_found))
            issues.singleOrNull()?.kind == ServerIssue.Kind.NO_ID ->
                ExceptionUiText(UiText.StringResource(SetupR.string.add_server_error_no_id))
            else -> ExceptionUiTexts(createIssuesString(issues))
        }

    private suspend fun saveServerInDatabase(type: ServerType, resolved: ResolvedServer): Server {
        val serverId = resolved.info.id
        val serverName = resolved.info.name

        Timber.d("Connecting to server: $serverName")

        val serverInDatabase = serverStore.server(serverId)

        // Check if server is already in the database
        // If so only add a new address to that server if it's different
        val server =
            if (serverInDatabase != null) {
                val addresses = serverStore.serverWithAddresses(serverInDatabase.id).addresses
                // If address is not in database, add it
                val serverAddress =
                    addresses.firstOrNull { it.address == resolved.address }
                        ?: ServerAddress(
                                id = ServerAddressId.random(),
                                serverId = serverInDatabase.id,
                                address = resolved.address,
                            )
                            .also { serverStore.insertAddress(it) }
                // CE (#984): the client goes to this address below, so it becomes the server's
                // current address in Room too. Left as it was, the copy of the session in
                // preferences kept the old address, and a restart went back to it.
                if (serverInDatabase.currentServerAddressId == serverAddress.id) {
                    serverInDatabase
                } else {
                    serverInDatabase.copy(currentServerAddressId = serverAddress.id).also {
                        serverStore.updateServer(it)
                    }
                }
            } else {
                val serverAddress =
                    ServerAddress(
                        id = ServerAddressId.random(),
                        serverId = serverId,
                        address = resolved.address,
                    )

                val server =
                    Server(
                        id = serverId,
                        name = serverName,
                        currentServerAddressId = serverAddress.id,
                        currentUserId = null,
                        type = type.value,
                    )

                serverStore.insertServer(server)
                serverStore.insertAddress(serverAddress)
                server
            }

        // CE (#425): a server that was saved before keeps its signed-in user, so the client keeps
        // that user's token. Clearing it signed the app out until a restart.
        val currentUser = serverInDatabase?.let { serverStore.currentUser(it.id) }
        // CE (#770): a server saved before keeps the type it was saved with.
        active.switchTo(
            ActiveServer(
                type = server.type,
                serverId = server.id,
                baseUrl = resolved.address,
                accessToken = currentUser?.accessToken,
                userId = currentUser?.id,
            )
        )

        return server
    }

    /**
     * Create a presentable string of issues with a server
     *
     * @param issues The issues of the server
     * @return A presentable string of issues separated with \n
     */
    private fun createIssuesString(issues: List<ServerIssue>): Collection<UiText> {
        return issues.map {
            when (it.kind) {
                ServerIssue.Kind.OUTDATED_VERSION -> {
                    UiText.StringResource(SetupR.string.add_server_error_outdated, it.detail ?: "")
                }
                ServerIssue.Kind.NOT_THIS_SERVER_TYPE -> {
                    UiText.StringResource(
                        SetupR.string.add_server_error_not_jellyfin,
                        it.detail ?: "",
                    )
                }
                ServerIssue.Kind.UNSUPPORTED_VERSION -> {
                    UiText.StringResource(SetupR.string.add_server_error_version, it.detail ?: "")
                }
                ServerIssue.Kind.SLOW_RESPONSE -> {
                    UiText.StringResource(SetupR.string.add_server_error_slow, it.detail ?: "")
                }
                ServerIssue.Kind.NO_ID,
                ServerIssue.Kind.UNKNOWN -> {
                    UiText.StringResource(CoreR.string.unknown_error)
                }
            }
        }
    }

    override suspend fun loadDisclaimer(): String? =
        withContext(Dispatchers.IO) { clientConnector().loginDisclaimer(clientAddress()) }

    override suspend fun login(username: String, password: String) {
        withContext(Dispatchers.IO) {
            val user = clientConnector().login(clientAddress(), username, password)

            saveAuthenticationResult(user)
        }
    }

    override suspend fun loginWithSecret(secret: String) {
        withContext(Dispatchers.IO) {
            val user = clientConnector().loginWithQuickConnect(clientAddress(), secret)

            saveAuthenticationResult(user)
        }
    }

    private suspend fun saveAuthenticationResult(authenticated: AuthenticatedUser) {
        val user =
            User(
                id = authenticated.id,
                name = authenticated.name,
                serverId = authenticated.serverId,
                accessToken = authenticated.accessToken,
                primaryImageTag = authenticated.primaryImageTag,
            )

        serverStore.insertUser(user)
        serverStore.setCurrentUser(authenticated.serverId, user.id)

        active.onSameServer {
            it.copy(accessToken = authenticated.accessToken, userId = authenticated.id)
        }
    }

    override suspend fun getUsers(serverId: ServerId): List<User> {
        return serverStore.users(serverId)
    }

    override suspend fun getPublicUsers(serverId: ServerId): List<User> =
        withContext(Dispatchers.IO) {
            clientConnector().publicUsers(clientAddress()).map {
                User(
                    id = it.id,
                    name = it.name,
                    serverId = serverId,
                    primaryImageTag = it.primaryImageTag,
                )
            }
        }

    override suspend fun updateUserImageTag(userId: UserId, tag: String?) {
        serverStore.setUserImageTag(userId, tag)
    }

    override suspend fun getCurrentUser(): User? {
        val currentServer = getCurrentServer() ?: return null
        return serverStore.currentUser(currentServer.id)
    }

    override suspend fun deleteUser(userId: UserId) {
        // Let the user delete the current active user for now
        /*val currentUser = getCurrentUser() ?: return
        if (userId == currentUser.id) {
            Timber.e("You cannot delete the current user")
            return
        }*/
        val user = serverStore.user(userId) ?: return
        endSessionOnServer(user)
        // CE (#334): its token no longer works, so it cannot stay the current user.
        val server = serverStore.server(user.serverId)
        if (server?.currentUserId == userId) {
            serverStore.clearCurrentUser(server.id)
            active.onSameServer {
                if (it.userId == userId) it.copy(accessToken = null, userId = null) else it
            }
        }
        serverStore.deleteUser(userId)
    }

    override suspend fun setCurrentUser(userId: UserId) {
        val server = getCurrentServer() ?: return
        val user = serverStore.user(userId) ?: return
        serverStore.updateServer(server.copy(currentUserId = user.id))

        active.onSameServer { it.copy(accessToken = user.accessToken, userId = user.id) }
    }

    override suspend fun clearCurrentUser() {
        val server = getCurrentServer() ?: return
        serverStore.clearCurrentUser(server.id)
    }

    /**
     * Makes [addressId] the current address of its server. CE (#984): of any saved server, as the
     * Addresses screen opens for each; the client moves there only when that server is the one in
     * use.
     */
    override suspend fun setCurrentAddress(addressId: ServerAddressId) =
        // Two selections at once (two quick taps) must not end with Room on one address and the
        // client on the other: each writes both before the next starts.
        addressSelection.withLock {
            val address = serverStore.address(addressId)
            val server = serverStore.server(address.serverId) ?: return@withLock
            if (server.currentServerAddressId != address.id) {
                serverStore.updateServer(server.copy(currentServerAddressId = address.id))
            }
            if (server.id == appPreferences.currentServerId()) {
                active.onSameServer { it.copy(baseUrl = address.address) }
            }
        }

    private val addressSelection = Mutex()

    override suspend fun addAddress(serverId: ServerId, address: String): ServerAddress {
        // The connector normalises the input the same way addServer does, so a bare host or a
        // missing scheme resolves to a usable address. It does not touch the client's base URL,
        // and it runs unwatched: this server is not the one in use, so its answers (or silence)
        // must not reach the reachability banner or the return detector.
        // Only accept an address that actually points at the server being edited.
        // CE (#770): the connector of the server's type.
        val connector = connectors.ofStoredType(serverStore.server(serverId)?.type)
        val match =
            connector.findAddress(address, serverId)
                ?: throw AddressRejected(AddressRejected.Reason.NOT_FOUND)

        val existing = serverStore.serverWithAddresses(serverId).addresses
        if (existing.any { it.address == match }) {
            throw AddressRejected(AddressRejected.Reason.ALREADY_SAVED)
        }

        val serverAddress = ServerAddress(ServerAddressId.random(), serverId, match)
        serverStore.insertAddress(serverAddress)
        return serverAddress
    }

    override suspend fun getAddresses(serverId: ServerId): List<ServerAddress> {
        return serverStore.serverWithAddresses(serverId).addresses
    }

    override suspend fun getCurrentAddress(serverId: ServerId): ServerAddress? {
        return serverStore.currentAddress(serverId)
    }

    override suspend fun deleteAddress(addressId: ServerAddressId) {
        serverStore.deleteAddress(addressId)
    }
}
