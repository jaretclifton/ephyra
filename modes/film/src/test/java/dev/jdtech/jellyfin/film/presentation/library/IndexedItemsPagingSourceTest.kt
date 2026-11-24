package dev.jdtech.jellyfin.film.presentation.library

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class IndexedItemsPagingSourceTest {
    private class Request(val start: Int, val limit: Int, val includeTotal: Boolean)

    private val requests = mutableListOf<Request>()
    private var total: Int? = null

    private fun item(): Item =
        fakeProxy(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
            throw NotImplementedError(method.kotlinName)
        }
            as Item

    private var failure: Exception? = null

    /** A library of [size] items; the server counts only when asked. */
    private fun source(size: Int) =
        IndexedItemsPagingSource(
            load = { start, limit, includeTotal ->
                requests += Request(start, limit, includeTotal)
                failure?.let { throw it }
                val end = (start + limit).coerceAtMost(size)
                ItemsPage(
                    items = List((end - start).coerceAtLeast(0)) { item() },
                    totalRecordCount = if (includeTotal) size else null,
                )
            },
            knownTotal = { total },
            onTotal = { total = it },
        )

    private fun refresh(key: Int?, loadSize: Int = 80) =
        LoadParams.Refresh(key, loadSize, placeholdersEnabled = true)

    @Test
    fun `first page takes the total from its own response`() = runBlocking {
        val result = source(500).load(refresh(null)) as LoadResult.Page
        assertEquals(1, requests.size)
        assertTrue(requests[0].includeTotal)
        assertEquals(500, total)
        assertEquals(80, result.data.size)
        assertEquals(420, result.itemsAfter)
        assertEquals(80, result.nextKey)
    }

    @Test
    fun `later pages and sources do not ask for the total`() = runBlocking {
        source(500).load(refresh(null))
        requests.clear()
        val append = source(500).load(LoadParams.Append(80, 40, true)) as LoadResult.Page
        assertEquals(1, requests.size)
        assertEquals(false, requests[0].includeTotal)
        assertEquals(40, append.data.size)
        assertEquals(380, append.itemsAfter)
    }

    @Test
    fun `a jump after the first load clamps to the known total`() = runBlocking {
        source(500).load(refresh(null))
        requests.clear()
        val result = source(500).load(refresh(499)) as LoadResult.Page
        assertEquals(420, result.itemsBefore)
        assertEquals(80, result.data.size)
        assertNull(result.nextKey)
    }

    @Test
    fun `a refresh past a shrunken library reloads inside it`() = runBlocking {
        // Unknown total and a key beyond the end: the first request comes back empty.
        val result = source(100).load(refresh(900)) as LoadResult.Page
        assertEquals(100, total)
        assertEquals(2, requests.size)
        assertEquals(20, result.itemsBefore)
        assertEquals(80, result.data.size)
    }

    @Test
    fun `an empty library has no items and no next key`() = runBlocking {
        val result = source(0).load(refresh(null)) as LoadResult.Page
        assertEquals(0, total)
        assertTrue(result.data.isEmpty())
        assertNull(result.nextKey)
        assertEquals(0, result.itemsAfter)
    }

    @Test
    fun `an append starts at its key and asks for no total`() = runBlocking {
        source(100).load(refresh(null))
        requests.clear()
        val append = source(100).load(LoadParams.Append(80, 40, true)) as LoadResult.Page
        assertEquals(80, requests.single().start)
        assertEquals(20, requests.single().limit)
        assertEquals(20, append.data.size)
        assertEquals(80, append.prevKey)
        assertNull(append.nextKey)
        assertEquals(0, append.itemsAfter)
    }

    @Test
    fun `a prepend ends at its key`() = runBlocking {
        source(500).load(refresh(null))
        requests.clear()
        val prepend = source(500).load(LoadParams.Prepend(200, 40, true)) as LoadResult.Page
        assertEquals(160, requests.single().start)
        assertEquals(40, requests.single().limit)
        assertEquals(160, prepend.itemsBefore)
        assertEquals(200, prepend.nextKey)
    }

    @Test
    fun `a refresh is centred on its key`() = runBlocking {
        source(500).load(refresh(null))
        requests.clear()
        val result = source(500).load(refresh(250)) as LoadResult.Page
        assertEquals(210, requests.single().start)
        assertEquals(210, result.itemsBefore)
        assertEquals(290, result.nextKey)
    }

    @Test
    fun `the refresh key is the anchor position, so a jump lands on its item`() {
        val state =
            PagingState<Int, Item>(
                pages = emptyList(),
                anchorPosition = 321,
                config = PagingConfig(pageSize = LIBRARY_PAGE_SIZE, enablePlaceholders = true),
                leadingPlaceholderCount = 0,
            )
        assertEquals(321, source(500).getRefreshKey(state))
    }

    @Test
    fun `a failed request is an error result`() = runBlocking {
        failure = ServerException.Unreachable(RuntimeException("down"))
        val result = source(500).load(refresh(null))
        assertTrue(result is LoadResult.Error, "$result")
        assertEquals<Throwable?>(failure, (result as LoadResult.Error).throwable)
        assertNull(total)
    }
}
