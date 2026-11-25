package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.ServerId

/**
 * #967: one download's files: the source rows of an item (a download of one version, finished or
 * not), their paths and download ids, and the local sources the player plays.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadSources {
    /** Every source row of [itemId], of any server. */
    suspend fun sources(itemId: ItemId): List<StoredSource>

    /** The source rows of [itemId] stored for [serverId] (#718). */
    suspend fun sourcesOfServer(itemId: ItemId, serverId: ServerId): List<StoredSource>

    suspend fun sourceByDownloadId(downloadId: Long): StoredSource?

    /** The local sources still downloading: a `.download` path. */
    suspend fun activeDownloadSources(): List<StoredSource>

    /** The local sources downloaded: a path without `.download`. */
    suspend fun completedDownloadSources(): List<StoredSource>

    /** Stores [source] of [itemId] as a local source at [path], replacing a row with its id. */
    suspend fun insertSource(
        itemId: ItemId,
        source: ItemSource,
        path: String,
        downloadId: Long,
        downloadMaxBitrate: Int?,
        smartQueued: Boolean,
    )

    /** The download of source [id] finished at [path] (its final name) at [downloadedAt]. */
    suspend fun setSourceDownloaded(id: MediaSourceId, path: String, downloadedAt: Long)

    suspend fun deleteSource(id: MediaSourceId)

    /** [source] with its stored media streams, as the player and the downloader use it. */
    suspend fun itemSource(source: StoredSource): ItemSource

    /**
     * The local sources of every item in [itemIds], of any server, keyed by item id, with their
     * media streams, read in batches. Items without one are absent.
     */
    suspend fun localSourcesOfItems(itemIds: Collection<ItemId>): Map<ItemId, List<ItemSource>>

    /**
     * The user asked for the download of [itemId]: neither its waiting row nor its source rows are
     * Smart Downloads' to remove any more.
     */
    suspend fun clearSmartQueued(itemId: ItemId)
}
