package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.ItemsPage
import java.time.LocalDateTime

/** Which items of the catalog a list or a count is about. */
data class ItemQuery(
    val parentId: ItemId? = null,
    val kinds: List<ItemKind>? = null,
    val recursive: Boolean = false,
    val sortBy: SortBy = SortBy.defaultValue,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val filters: Set<ItemFilter> = emptySet(),
    val startIndex: Int? = null,
    val limit: Int? = null,
)

/**
 * Reads from a media server. Knows nothing about downloads: an item has only its remote sources.
 * Failures are thrown as `ServerException` (#756).
 */
interface CatalogPort {
    suspend fun serverInfo(): ServerInfo

    suspend fun libraries(): List<Library>

    suspend fun userViews(): List<Library>

    suspend fun item(id: ItemId): Item?

    suspend fun movie(id: ItemId): Movie

    suspend fun show(id: ItemId): Show

    suspend fun season(id: ItemId): Season

    suspend fun episode(id: ItemId): Episode

    /** [ItemsPage.totalRecordCount] is null unless [includeTotal]. */
    suspend fun items(query: ItemQuery, includeTotal: Boolean = false): ItemsPage

    /** How many items [query] has; its sort, start index and limit do not count. */
    suspend fun itemCount(query: ItemQuery, nameLessThan: String? = null): Int

    suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<Item>

    suspend fun person(id: ItemId): Person

    suspend fun personItems(
        personIds: List<ItemId>,
        kinds: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<Item>

    suspend fun favorites(): List<Item>

    /**
     * Up to [SEARCH_LIMIT] movies and shows when [kinds] is null, else up to
     * [SEARCH_LIMIT_PER_KIND] of each kind.
     */
    suspend fun search(query: String, kinds: List<ItemKind>?): List<Item>

    suspend fun suggestions(): List<Item>

    suspend fun resumeItems(): List<Item>

    suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<Item>

    suspend fun seasons(seriesId: ItemId): List<Season>

    /** [dateCutoff] comes from a preference; the repository computes it. */
    suspend fun nextUp(seriesId: ItemId?, dateCutoff: LocalDateTime?): List<Episode>

    suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
    ): List<Episode>

    /** Without [itemId] itself. */
    suspend fun similar(itemId: ItemId, limit: Int): List<Item>

    suspend fun extras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie>

    suspend fun collectionsOf(itemId: ItemId): List<Item>

    /** Null when the item has no theme song. */
    suspend fun themeSongUrl(itemId: ItemId): String?

    companion object {
        /** How many results [search] gives without kinds; the search of downloads keeps to it. */
        const val SEARCH_LIMIT = 50

        /** How many results of each kind [search] gives. */
        const val SEARCH_LIMIT_PER_KIND = 20
    }
}
