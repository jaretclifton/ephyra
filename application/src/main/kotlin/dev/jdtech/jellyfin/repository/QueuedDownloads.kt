package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerId

/**
 * #967: the downloads waiting for a slot, kept so a restart queues them again.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface QueuedDownloads {
    /** Stores [pending], replacing a waiting row of its item. */
    suspend fun insertPendingDownload(pending: StoredPendingDownload)

    suspend fun deletePendingDownload(itemId: ItemId)

    /** The waiting downloads, oldest first. */
    suspend fun pendingDownloads(): List<StoredPendingDownload>
}
