package dev.jdtech.jellyfin.database

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.toPlayState
import dev.jdtech.jellyfin.repository.PendingUserData
import dev.jdtech.jellyfin.repository.PendingUserDataStore
import dev.jdtech.jellyfin.repository.SyncServer
import dev.jdtech.jellyfin.repository.SyncTarget
import dev.jdtech.jellyfin.repository.SyncUser

/** #899: [PendingUserDataStore] over the Room tables; each method is the DAO call the sync made. */
class RoomPendingUserDataStore(private val database: ServerDatabaseDao) : PendingUserDataStore {
    override suspend fun servers(): List<SyncServer> =
        database.getServers().map { SyncServer(it.id, it.type) }

    override suspend fun targetOf(server: SyncServer): SyncTarget? {
        val stored = database.getServerWithAddressesAndUsers(server.id) ?: return null
        val address =
            stored.addresses.firstOrNull { it.id == stored.server.currentServerAddressId }
                ?: return null
        return SyncTarget(address.address, stored.users.map { SyncUser(it.id, it.accessToken) })
    }

    override suspend fun pending(account: AccountKey): List<PendingUserData> =
        database.getAllUserDataToBeSynced(account.serverId, account.userId).map {
            PendingUserData(it.itemId, it.toPlayState())
        }

    override suspend fun syncedItemIds(account: AccountKey): List<ItemId> =
        database.getSyncedUserDataItemIds(account.serverId, account.userId)

    override suspend fun hasUserDataToBeSynced(): Boolean = database.hasUserDataToBeSynced()

    override suspend fun hasSyncedUserData(): Boolean = database.hasSyncedUserData()

    override suspend fun refreshFromServer(
        account: AccountKey,
        itemId: ItemId,
        state: PlayState,
    ): Int =
        database.refreshUserDataFromServer(
            userId = account.userId,
            itemId = itemId,
            played = state.played,
            favorite = state.favorite,
            playbackPositionTicks = state.positionTicks,
            lastPlayedDate = state.lastPlayedMillis,
        )

    override suspend fun takeServerProgress(
        account: AccountKey,
        sent: PendingUserData,
        server: PlayState,
    ): Int =
        database.takeServerProgress(
            userId = account.userId,
            itemId = sent.itemId,
            played = server.played,
            playbackPositionTicks = server.positionTicks,
            lastPlayedDate = server.lastPlayedMillis,
            sentPlayed = sent.state.played,
            sentFavorite = sent.state.favorite,
            sentPlaybackPositionTicks = sent.state.positionTicks,
            sentLastPlayedDate = sent.state.lastPlayedMillis,
        )

    override suspend fun clearToBeSynced(account: AccountKey, itemId: ItemId, sent: PlayState) {
        database.clearUserDataToBeSynced(
            userId = account.userId,
            itemId = itemId,
            played = sent.played,
            favorite = sent.favorite,
            playbackPositionTicks = sent.positionTicks,
            lastPlayedDate = sent.lastPlayedMillis,
        )
    }

    override suspend fun dropToBeSynced(account: AccountKey, itemId: ItemId) {
        database.setUserDataToBeSynced(account.userId, itemId, false)
    }

    override suspend fun deleteIfOrphaned(account: AccountKey, itemId: ItemId) {
        database.deleteUserDataIfOrphaned(account.userId, itemId)
    }
}
