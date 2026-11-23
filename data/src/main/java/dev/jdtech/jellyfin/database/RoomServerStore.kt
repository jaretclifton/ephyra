package dev.jdtech.jellyfin.database

import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toServer
import dev.jdtech.jellyfin.models.toServerAddress
import dev.jdtech.jellyfin.models.toServerAddressEntity
import dev.jdtech.jellyfin.models.toServerEntity
import dev.jdtech.jellyfin.models.toServerWithAddressAndUser
import dev.jdtech.jellyfin.models.toServerWithAddresses
import dev.jdtech.jellyfin.models.toUser
import dev.jdtech.jellyfin.models.toUserEntity
import dev.jdtech.jellyfin.repository.ServerStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** #899: [ServerStore] over the Room tables; each method is the DAO call its callers made. */
class RoomServerStore(private val database: ServerDatabaseDao) : ServerStore {
    override suspend fun server(id: ServerId): Server? = database.getServer(id)?.toServer()

    override fun observeServer(id: ServerId): Flow<Server?> =
        database.observeServer(id).map { it?.toServer() }

    override suspend fun serversWithAddresses(): List<ServerWithAddresses> =
        database.getServersWithAddresses().map { it.toServerWithAddresses() }

    override suspend fun serverWithAddresses(id: ServerId): ServerWithAddresses =
        database.getServerWithAddresses(id).toServerWithAddresses()

    override suspend fun serverWithAddressAndUser(id: ServerId): ServerWithAddressAndUser? =
        database.getServerWithAddressAndUser(id)?.toServerWithAddressAndUser()

    override fun observeServerWithAddressAndUser(id: ServerId): Flow<ServerWithAddressAndUser?> =
        database.observeServerWithAddressAndUser(id).map { it?.toServerWithAddressAndUser() }

    override fun observeServersCount(): Flow<Int> = database.observeServersCount()

    override suspend fun insertServer(server: Server) =
        database.insertServer(server.toServerEntity())

    override suspend fun updateServer(server: Server) =
        database.updateServer(server.toServerEntity())

    override suspend fun deleteServer(id: ServerId) = database.deleteServer(id)

    override suspend fun hasStoredMoviesOrShows(serverId: ServerId): Boolean =
        database.countMoviesByServerId(serverId) > 0 || database.countShowsByServerId(serverId) > 0

    override suspend fun insertAddress(address: ServerAddress) =
        database.insertServerAddress(address.toServerAddressEntity())

    override suspend fun address(id: UUID): ServerAddress =
        database.getAddress(id = id).toServerAddress()

    override suspend fun currentAddress(serverId: ServerId): ServerAddress? =
        database.getServerCurrentAddress(serverId)?.toServerAddress()

    override suspend fun deleteAddress(id: UUID) = database.deleteServerAddress(id)

    override suspend fun user(id: UserId): User? = database.getUser(id)?.toUser()

    override suspend fun users(serverId: ServerId): List<User> =
        database.getUsers(serverId).map { it.toUser() }

    override fun observeUsersCount(serverId: ServerId): Flow<Int> =
        database.observeUsersCount(serverId)

    override suspend fun currentUser(serverId: ServerId): User? =
        database.getServerCurrentUser(serverId)?.toUser()

    override fun observeCurrentUser(serverId: ServerId): Flow<User?> =
        database.observeServerCurrentUser(serverId).map { it?.toUser() }

    override fun observeCurrentUserId(serverId: ServerId): Flow<UserId?> =
        database.observeServerCurrentUserId(serverId)

    override suspend fun setCurrentUser(serverId: ServerId, userId: UserId) =
        database.updateServerCurrentUser(serverId, userId)

    override suspend fun clearCurrentUser(serverId: ServerId) =
        database.clearServerCurrentUser(serverId)

    override suspend fun insertUser(user: User) = database.insertUser(user.toUserEntity())

    override suspend fun deleteUser(id: UserId) = database.deleteUser(id)

    override suspend fun setUserImageTag(id: UserId, tag: String?) =
        database.updateUserPrimaryImageTag(id, tag)

    override suspend fun setUserPin(id: UserId, hash: String?, salt: String?) =
        database.updateUserPin(id, hash, salt)

    override suspend fun setUserPattern(id: UserId, hash: String, salt: String) =
        database.updateUserPattern(id, hash, salt)

    override suspend fun setUserPinOnStart(id: UserId, pinOnStart: Boolean) =
        database.updateUserPinOnStart(id, pinOnStart)
}
