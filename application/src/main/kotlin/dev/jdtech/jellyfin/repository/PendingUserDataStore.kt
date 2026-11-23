package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId

/**
 * What the user data sync reads and writes in the local database (#899, part B). The decisions of
 * which state wins and what is sent are in `PlayState` (#898); this port only reads and writes.
 * Each method is one query or transaction of the database behind it, in the order the sync calls
 * them.
 */
interface PendingUserDataStore {
    /** The saved servers, for the sync to visit one by one. */
    suspend fun servers(): List<SyncServer>

    /**
     * The address in use and the users of [server], or null when it is gone or has no address in
     * use.
     */
    suspend fun targetOf(server: SyncServer): SyncTarget?

    /**
     * The rows of [account] that still have to reach the server: those of the downloaded movies and
     * episodes of its server, and those of items whose download was deleted before the sync ran.
     */
    suspend fun pending(account: AccountKey): List<PendingUserData>

    /**
     * The downloaded movies and episodes of [account]'s server whose user data has reached the
     * server, so the server's values may replace it. Rows still to be sent are left out.
     */
    suspend fun syncedItemIds(account: AccountKey): List<ItemId>

    /** Whether any movie or episode row, stored or not, still has to reach the server. */
    suspend fun hasUserDataToBeSynced(): Boolean

    /** Whether any downloaded movie or episode has user data the server may refresh. */
    suspend fun hasSyncedUserData(): Boolean

    /**
     * Writes the server's [state] into the row of a download, unless the row is marked to be sent.
     * Returns how many rows changed (1 or 0).
     */
    suspend fun refreshFromServer(account: AccountKey, itemId: ItemId, state: PlayState): Int

    /**
     * Another device played the item later: replaces the row's progress with [server]'s and counts
     * the row as sent, but only while it still holds [sent]. The row's favourite is kept. Returns 1
     * when the row was taken over.
     */
    suspend fun takeServerProgress(
        account: AccountKey,
        sent: PendingUserData,
        server: PlayState,
    ): Int

    /** Marks the row of [itemId] as sent, but only while it still holds [sent]. */
    suspend fun clearToBeSynced(account: AccountKey, itemId: ItemId, sent: PlayState)

    /** Unmarks the row of [itemId] without sending it: the item is gone from the server. */
    suspend fun dropToBeSynced(account: AccountKey, itemId: ItemId)

    /** Removes a synced row whose download is gone; does nothing while the item is stored. */
    suspend fun deleteIfOrphaned(account: AccountKey, itemId: ItemId)
}

/** A row of the user data still to be sent: the item and the state the row holds. */
data class PendingUserData(val itemId: ItemId, val state: PlayState)

/** A saved server as the sync visits it. */
data class SyncServer(val id: ServerId, val type: String)

/** The address in use of a [SyncServer] and its users. */
data class SyncTarget(val address: String, val users: List<SyncUser>)

/** A user of a saved server, with the token the sync logs in with. */
data class SyncUser(val id: UserId, val accessToken: String?)
