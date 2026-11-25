package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.ServerId

/** A stored source row: a download of one version of an item, finished or not. */
data class StoredSource(
    val id: MediaSourceId,
    val itemId: ItemId,
    val name: String,
    val type: ItemSourceType,
    val path: String,
    val downloadId: Long? = null,
    /** The quality cap (bits/sec) it was asked with, null for the original. */
    val downloadMaxBitrate: Int? = null,
    /** Epoch ms at which the download finished; null while in flight and for older rows. */
    val downloadedAt: Long? = null,
    /** Smart Downloads queued it, which is the only kind it may remove. */
    val smartQueued: Boolean = false,
)

/** A stored media stream (an external subtitle) of a downloaded source. */
data class StoredMediaStream(
    val id: MediaStreamId,
    val sourceId: MediaSourceId,
    val title: String,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val path: String,
    val downloadId: Long? = null,
)

/**
 * The server a stored item belongs to. [serverId] is null for a row from before rows carried a
 * server.
 */
data class StoredServer(val serverId: ServerId?)

/** A download waiting for a slot, kept so a restart queues it again. */
data class StoredPendingDownload(
    val itemId: ItemId,
    /** `MOVIE` or `EPISODE`. */
    val itemKind: String,
    val addedAt: Long,
    /** The quality cap (bits/sec) chosen for it, null for the original. */
    val maxBitrate: Int? = null,
    /** Smart Downloads queued it, so the download stays removable by it. */
    val smartQueued: Boolean = false,
)
