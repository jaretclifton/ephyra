package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

/**
 * #967: what is downloaded: the stored movies and episodes with a user's data and their local
 * sources, the episodes of a show, the counts per season, and which server stored an item.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadList {
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

    /** The series that have a finished episode download stored for [serverId]. */
    suspend fun seriesIdsWithCompletedDownloads(serverId: ServerId): List<ItemId>
}
