package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId

/**
 * #967: the user data a download stores with its item, and what of it is still to be sent to the
 * server (#281).
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadUserData {
    /**
     * Stores [item]'s user data, as the server sent it, for [userId], unless the user has progress
     * for it that is still to be sent to the server: that is newer, and stays.
     */
    suspend fun storeUserDataOfDownload(userId: UserId, item: Item)

    /** Whether [userId] has user data for [itemId] still to be sent to the server. */
    suspend fun hasUserDataToBeSynced(userId: UserId, itemId: ItemId): Boolean

    /**
     * Deletes the user data of [itemId] that has reached the server, of every user. What is still
     * to be sent outlives the download, and the sync removes it (#281).
     */
    suspend fun deleteSyncedUserData(itemId: ItemId)
}
