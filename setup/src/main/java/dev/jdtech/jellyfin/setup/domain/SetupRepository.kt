package dev.jdtech.jellyfin.setup.domain

import dev.jdtech.jellyfin.models.DiscoveredServer
import dev.jdtech.jellyfin.models.QuickConnectState
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface SetupRepository {
    fun discoverServers(): Flow<DiscoveredServer>

    suspend fun getServers(): List<ServerWithAddresses>

    suspend fun getCurrentServer(): Server?

    suspend fun deleteServer(serverId: String)

    suspend fun getIsQuickConnectEnabled(): Boolean

    suspend fun initiateQuickConnect(): QuickConnectState

    suspend fun getQuickConnectState(secret: String): QuickConnectState

    suspend fun setCurrentServer(serverId: String)

    suspend fun addServer(address: String): Server

    suspend fun loadDisclaimer(): String?

    suspend fun login(username: String, password: String)

    suspend fun loginWithSecret(secret: String)

    suspend fun getUsers(serverId: String): List<User>

    suspend fun getPublicUsers(serverId: String): List<User>

    suspend fun updateUserImageTag(userId: UUID, tag: String?)

    suspend fun getCurrentUser(): User?

    suspend fun deleteUser(userId: UUID)

    suspend fun setCurrentUser(userId: UUID)

    /** CE (#299): no profile is open on the current server; the app goes to the user selection. */
    suspend fun clearCurrentUser()

    suspend fun setCurrentAddress(addressId: UUID)

    /**
     * Adds [address] to the saved server [serverId] after checking that the server answering there
     * is that server. Checking never changes the base URL of the client the app is using.
     *
     * @throws AddressRejected when another server or none answers, or the address is already saved.
     */
    suspend fun addAddress(serverId: String, address: String): ServerAddress

    suspend fun getAddresses(serverId: String): List<ServerAddress>

    /** The address the app uses for [serverId]. */
    suspend fun getCurrentAddress(serverId: String): ServerAddress?

    suspend fun deleteAddress(addressId: UUID)
}
