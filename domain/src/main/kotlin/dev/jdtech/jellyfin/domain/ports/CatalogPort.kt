package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ServerInfo
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

    suspend fun libraries(): List<FindroidCollection>

    suspend fun userViews(): List<FindroidCollection>

    suspend fun item(id: ItemId): FindroidItem?

    suspend fun movie(id: ItemId): FindroidMovie

    suspend fun show(id: ItemId): FindroidShow

    suspend fun season(id: ItemId): FindroidSeason

    suspend fun episode(id: ItemId): FindroidEpisode

    /** [ItemsPage.totalRecordCount] is null unless [includeTotal]. */
    suspend fun items(query: ItemQuery, includeTotal: Boolean = false): ItemsPage

    /** How many items [query] has; its sort, start index and limit do not count. */
    suspend fun itemCount(query: ItemQuery, nameLessThan: String? = null): Int

    suspend fun randomItemsWithBackdrop(kinds: List<ItemKind>, limit: Int): List<FindroidItem>

    suspend fun person(id: ItemId): FindroidPerson

    suspend fun personItems(
        personIds: List<ItemId>,
        kinds: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<FindroidItem>

    suspend fun favorites(): List<FindroidItem>

    /**
     * Up to [SEARCH_LIMIT] movies and shows when [kinds] is null, else up to
     * [SEARCH_LIMIT_PER_KIND] of each kind.
     */
    suspend fun search(query: String, kinds: List<ItemKind>?): List<FindroidItem>

    suspend fun suggestions(): List<FindroidItem>

    suspend fun resumeItems(): List<FindroidItem>

    suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<FindroidItem>

    suspend fun seasons(seriesId: ItemId): List<FindroidSeason>

    /** [dateCutoff] comes from a preference; the repository computes it. */
    suspend fun nextUp(seriesId: ItemId?, dateCutoff: LocalDateTime?): List<FindroidEpisode>

    suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
    ): List<FindroidEpisode>

    /** Without [itemId] itself. */
    suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem>

    suspend fun extras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<FindroidMovie>

    suspend fun collectionsOf(itemId: ItemId): List<FindroidItem>

    /** Null when the item has no theme song. */
    suspend fun themeSongUrl(itemId: ItemId): String?

    companion object {
        /** How many results [search] gives without kinds; the search of downloads keeps to it. */
        const val SEARCH_LIMIT = 50

        /** How many results of each kind [search] gives. */
        const val SEARCH_LIMIT_PER_KIND = 20
    }
}
