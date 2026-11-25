package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Show

/**
 * #967: one item by its id, the seasons and episodes of a show, and people. One of the roles the
 * repository was split into (the lists are [ItemLists]). A downloaded item is answered from Room
 * when the server is away (CE, #281).
 */
interface ItemCatalog {
    suspend fun getItem(itemId: ItemId): Item?

    suspend fun getMovie(itemId: ItemId): Movie

    suspend fun getShow(itemId: ItemId): Show

    suspend fun getSeason(itemId: ItemId): Season

    suspend fun getEpisode(itemId: ItemId): Episode

    suspend fun getSeasons(seriesId: ItemId, offline: Boolean = false): List<Season>

    /** The episodes of one season, or with a null [seasonId] (CE, #252) of the whole series. */
    suspend fun getEpisodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>? = null,
        startItemId: ItemId? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<Episode>

    /** The next episodes to watch: of [seriesId], or with null of every series (Home's row). */
    suspend fun getNextUp(seriesId: ItemId? = null): List<Episode>

    suspend fun getPerson(personId: ItemId): Person

    suspend fun getPersonItems(
        personIds: List<ItemId>,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = true,
        limit: Int? = null,
    ): List<Item>

    /**
     * The backdrop of [seasonId], whether or not the season has one of its own; the server answers
     * 404 when it has none. Null when no server is set.
     */
    fun seasonBackdropUrl(seasonId: ItemId): String?
}
