package dev.jdtech.jellyfin.film.presentation.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * The library grid without placeholders, page by page over [MediaRepository.getItems]. #968: was
 * `MediaRepository.getItemsPaging` in `data`; the pager lives with the screen that shows it.
 */
internal fun MediaRepository.itemsPager(
    parentId: ItemId? = null,
    includeTypes: List<ItemKind>? = null,
    recursive: Boolean = false,
    sortBy: SortBy = SortBy.defaultValue,
    sortOrder: SortOrder = SortOrder.ASCENDING,
    filters: Set<ItemFilter> = emptySet(),
): Flow<PagingData<Item>> =
    Pager(
            config =
                PagingConfig(
                    pageSize = LIBRARY_PAGE_SIZE,
                    prefetchDistance = LIBRARY_PAGE_SIZE,
                    initialLoadSize = LIBRARY_PAGE_SIZE * 2,
                    enablePlaceholders = false,
                ),
            pagingSourceFactory = {
                ItemsPagingSource(
                    this,
                    parentId,
                    includeTypes,
                    recursive,
                    sortBy,
                    sortOrder,
                    filters,
                )
            },
        )
        .flow

internal class ItemsPagingSource(
    private val mediaRepository: MediaRepository,
    private val parentId: ItemId?,
    private val includeTypes: List<ItemKind>?,
    private val recursive: Boolean,
    private val sortBy: SortBy,
    private val sortOrder: SortOrder,
    private val filters: Set<ItemFilter>,
) : PagingSource<Int, Item>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Item> {
        val position = params.key ?: 0

        Timber.d("Retrieving position: $position")

        return try {
            val items =
                mediaRepository.getItems(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    filters = filters,
                    startIndex = position,
                    limit = params.loadSize,
                )
            LoadResult.Page(
                data = items,
                prevKey = if (position == 0) null else position - params.loadSize,
                nextKey = if (items.isEmpty()) null else position + params.loadSize,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Item>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.let { it + state.config.pageSize }
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.let {
                    it - state.config.pageSize
                }
        }
    }
}
