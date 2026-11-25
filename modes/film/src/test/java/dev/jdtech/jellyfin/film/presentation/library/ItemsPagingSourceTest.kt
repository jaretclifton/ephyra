package dev.jdtech.jellyfin.film.presentation.library

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** #968: the grid without placeholders, moved from `data` with its pager. */
class ItemsPagingSourceTest {
    private val library = "11111111-1111-1111-1111-111111111111".toItemId()

    /** What the source asked [MediaRepository.getItems] for, one list of arguments per request. */
    private val requests = mutableListOf<List<Any?>>()
    private var failure: Exception? = null

    private fun item(): Item =
        fakeProxy(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
            throw NotImplementedError(method.kotlinName)
        }
            as Item

    /** A library of [size] items behind [MediaRepository.getItems]. */
    private fun repository(size: Int): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "getItems" -> {
                    val a = args!!
                    requests +=
                        listOf(a[0].asItemIdOrNull(), a[1], a[2], a[3], a[4], a[5], a[6], a[7])
                    failure?.let { throw it }
                    val start = a[6] as Int
                    val end = (start + a[7] as Int).coerceAtMost(size)
                    List((end - start).coerceAtLeast(0)) { item() }
                }
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun source(size: Int) =
        ItemsPagingSource(
            repository(size),
            library,
            listOf(ItemKind.MOVIE),
            true,
            SortBy.NAME,
            SortOrder.DESCENDING,
            setOf(ItemFilter.IS_UNPLAYED),
        )

    @Test
    fun `the first page passes the sort and filter through`() = runBlocking {
        val page = source(500).load(LoadParams.Refresh(null, 80, false)) as LoadResult.Page
        assertEquals(
            listOf<Any?>(
                library,
                listOf(ItemKind.MOVIE),
                true,
                SortBy.NAME,
                SortOrder.DESCENDING,
                setOf(ItemFilter.IS_UNPLAYED),
                0,
                80,
            ),
            requests.single(),
        )
        assertEquals(80, page.data.size)
        assertNull(page.prevKey)
        assertEquals(80, page.nextKey)
    }

    @Test
    fun `an append loads from its key`() = runBlocking {
        val page = source(500).load(LoadParams.Append(80, 40, false)) as LoadResult.Page
        assertEquals(80, requests.single()[6])
        assertEquals(40, requests.single()[7])
        assertEquals(40, page.prevKey)
        assertEquals(120, page.nextKey)
    }

    @Test
    fun `the end of the library has no next key`() = runBlocking {
        val page = source(100).load(LoadParams.Append(120, 40, false)) as LoadResult.Page
        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `the refresh key is the closest page's start`() {
        val config = PagingConfig(pageSize = LIBRARY_PAGE_SIZE, enablePlaceholders = false)
        val pages =
            listOf(
                LoadResult.Page(List(40) { item() }, prevKey = null, nextKey = 40),
                LoadResult.Page(List(40) { item() }, prevKey = 0, nextKey = 80),
            )
        val state = PagingState(pages, anchorPosition = 50, config, leadingPlaceholderCount = 0)
        assertEquals(40, source(500).getRefreshKey(state))
    }

    @Test
    fun `a failed request is an error result`() = runBlocking {
        failure = IllegalStateException("down")
        val result = source(500).load(LoadParams.Refresh(null, 80, false))
        assertTrue(result is LoadResult.Error, "$result")
        assertEquals<Throwable?>(failure, (result as LoadResult.Error).throwable)
    }
}
