package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/**
 * The stored downloads (#899, ADR 0002 decision 2): the items kept for playing without the server,
 * their sources, media streams (external subtitles), segments and trickplay info, the downloads
 * waiting for a slot, the Smart Downloads flags, and the user data a download stores with its item.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it: an item id is
 * stored once whatever server downloaded it, and user data by user.
 *
 * Each method is one or a few of the queries its callers made on the database before, in the same
 * order and without a transaction they did not have.
 */
interface DownloadStore {

    // ---- Sources -------------------------------------------------------------------------------

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

    /** The series that have a finished episode download stored for [serverId]. */
    suspend fun seriesIdsWithCompletedDownloads(serverId: ServerId): List<ItemId>

    // ---- Media streams (external subtitles) ----------------------------------------------------

    suspend fun mediaStreamsOfSource(sourceId: MediaSourceId): List<StoredMediaStream>

    suspend fun mediaStreamByDownloadId(downloadId: Long): StoredMediaStream?

    /** Stores [stream] of source [sourceId] under [id], downloaded to [path]. */
    suspend fun insertMediaStream(
        id: MediaStreamId,
        sourceId: MediaSourceId,
        stream: MediaStream,
        path: String,
    )

    suspend fun setMediaStreamDownloadId(id: MediaStreamId, downloadId: Long)

    suspend fun setMediaStreamPath(id: MediaStreamId, path: String)

    suspend fun deleteMediaStream(id: MediaStreamId)

    suspend fun deleteMediaStreamsOfSource(sourceId: MediaSourceId)

    // ---- Items ---------------------------------------------------------------------------------

    /** Stores [movie] for [serverId], unless a movie with its id is stored already. */
    suspend fun insertMovie(movie: Movie, serverId: ServerId?)

    /**
     * Stores [show], [season] and [episode], in that order, for [serverId] (the season row has no
     * server); each one unless a row with its id is stored already.
     */
    suspend fun insertEpisode(episode: Episode, show: Show, season: Season, serverId: ServerId?)

    /** The server the movie [id] is stored for, or null when it is not stored. */
    suspend fun storedServerOfMovie(id: ItemId): StoredServer?

    /** The server the episode [id] is stored for, or null when it is not stored. */
    suspend fun storedServerOfEpisode(id: ItemId): StoredServer?

    /**
     * The stored movie [id] with [userId]'s user data (a row is made when there is none) and its
     * local sources. Throws when it is not stored.
     */
    suspend fun movie(id: ItemId, userId: UserId): Movie

    /** As [movie], for an episode. Throws when it is not stored. */
    suspend fun episode(id: ItemId, userId: UserId): Episode

    /** As [movie], or null when it is not stored. */
    suspend fun movieOrNull(id: ItemId, userId: UserId): Movie?

    /** As [episode], or null when it is not stored. */
    suspend fun episodeOrNull(id: ItemId, userId: UserId): Episode?

    suspend fun deleteMovie(id: ItemId)

    suspend fun deleteEpisode(id: ItemId)

    /** The stored episodes of season [seasonId], of any server. */
    suspend fun episodeIdsOfSeason(seasonId: ItemId): List<ItemId>

    /** The stored seasons of show [showId], of any server. */
    suspend fun seasonIdsOfShow(showId: ItemId): List<ItemId>

    /** Deletes the season [id] and the user data of every user for it. */
    suspend fun deleteSeasonAndUserData(id: ItemId)

    /** Deletes the show [id] and the user data of every user for it. */
    suspend fun deleteShowAndUserData(id: ItemId)

    /** The ids of every stored movie, show, season and episode, of every server. */
    suspend fun allItemIds(): List<ItemId>

    /** The stored episodes of show [showId] that [serverId] downloaded (#718). */
    suspend fun episodeIdsOfShow(showId: ItemId, serverId: ServerId): List<ItemId>

    /**
     * The stored episodes of show [showId] that [account]'s server downloaded, each with the user's
     * data and its local sources, as [episode] gives them. Cold: the episodes are read on
     * collection, and each one is made as it is emitted, so what the collector does with one (it
     * deletes it) comes before the next one is read.
     */
    fun episodesOfShow(account: AccountKey, showId: ItemId): Flow<Episode>

    /**
     * As [episodesOfShow], only the episodes with a finished local source, their sources read in
     * one batch for all of them.
     */
    suspend fun downloadedEpisodesOfShow(account: AccountKey, showId: ItemId): List<Episode>

    /** Per season of [showId], how many episodes [serverId] has stored and downloaded (#252). */
    suspend fun seasonEpisodeCounts(showId: ItemId, serverId: ServerId): List<SeasonEpisodeCounts>

    // ---- Segments and trickplay ----------------------------------------------------------------

    /** Replaces the stored segments of [itemId] with [segments], in one transaction. */
    suspend fun replaceSegments(itemId: ItemId, segments: List<Segment>)

    suspend fun deleteSegments(itemId: ItemId)

    /** Stores [info], the trickplay layout of source [sourceId]. */
    suspend fun insertTrickplayInfo(sourceId: MediaSourceId, info: ItemTrickplayInfo)

    // ---- User data of downloaded items ---------------------------------------------------------

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

    // ---- Downloads waiting for a slot ----------------------------------------------------------

    /** Stores [pending], replacing a waiting row of its item. */
    suspend fun insertPendingDownload(pending: StoredPendingDownload)

    suspend fun deletePendingDownload(itemId: ItemId)

    /** The waiting downloads, oldest first. */
    suspend fun pendingDownloads(): List<StoredPendingDownload>
}

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
