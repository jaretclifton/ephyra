package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.Show

/**
 * #967: the item rows a download writes and deletes: the movie, or the show, season and episode,
 * and what is left of a show or season when its last episode goes.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadedItemRows {
    /** Stores [movie] for [serverId], unless a movie with its id is stored already. */
    suspend fun insertMovie(movie: Movie, serverId: ServerId?)

    /**
     * Stores [show], [season] and [episode], in that order, for [serverId] (the season row has no
     * server); each one unless a row with its id is stored already.
     */
    suspend fun insertEpisode(episode: Episode, show: Show, season: Season, serverId: ServerId?)

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
}
