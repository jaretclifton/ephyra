package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder

/**
 * #967: the lists a screen shows: libraries, the items of a library, favourites, search, the rows
 * of Home and the downloads. One of the roles the repository was split into (one item by its id is
 * [ItemCatalog]).
 */
interface ItemLists {
    /** CE (#779): with where the list came from: Room answers it while the server is away. */
    suspend fun getLibraries(): ItemsResult<Library>

    /** The libraries the current user sees, in the server's order, of the supported types only. */
    suspend fun getUserViews(): List<Library>

    suspend fun getItems(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
        startIndex: Int? = null,
        limit: Int? = null,
    ): List<Item>

    /**
     * CE: like [getItems], but with the number of items that match in total (ignoring [startIndex]
     * and [limit]) when [includeTotal] is set, so a pager gets its length from its first page
     * instead of a separate [getItemCount]. The server counts only when asked, so leave
     * [includeTotal] off for later pages.
     *
     * The plain page read (#968): the library grid's pager in `modes/film` is built over it (and
     * over [getItems] for the grid without placeholders), with [getItemCount] for the letter index.
     * `application` knows no paging library.
     */
    suspend fun getItemsPage(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
        startIndex: Int? = null,
        limit: Int? = null,
        includeTotal: Boolean = false,
    ): ItemsPage

    /**
     * CE: the number of items that [getItems] would return, without fetching them. With
     * [nameLessThan], only those whose sort name comes before it.
     */
    suspend fun getItemCount(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        filters: Set<ItemFilter> = emptySet(),
        nameLessThan: String? = null,
    ): Int

    /**
     * CE (#140): up to [limit] items of [includeTypes] from all libraries, in random order, that
     * have a backdrop. Online the server picks them; the default (offline) shuffles local items.
     */
    suspend fun getRandomItemsWithBackdrop(includeTypes: List<ItemKind>, limit: Int): List<Item> =
        getItems(includeTypes = includeTypes, recursive = true)
            .filter { it.images.backdrop != null }
            .shuffled()
            .take(limit)

    /** CE (#779): with where the list came from: Room answers it while the server is away. */
    suspend fun getFavoriteItems(): ItemsResult<Item>

    /**
     * Searches movies and shows. With [itemKinds], it searches those kinds instead, with a separate
     * limit per kind so one kind (episodes) can't push the others out (CE, TV search). CE (#779):
     * with where the list came from, as [getFavoriteItems].
     */
    suspend fun getSearchItems(query: String, itemKinds: List<ItemKind>? = null): ItemsResult<Item>

    suspend fun getSuggestions(): List<Item>

    suspend fun getResumeItems(): List<Item>

    /** [includeItemTypes] null leaves the choice to the server. */
    suspend fun getLatestMedia(
        parentId: ItemId,
        includeItemTypes: List<ItemKind>? = null,
    ): List<Item>

    /** The downloaded items of the current server and user. */
    suspend fun getDownloads(): List<Item>
}
