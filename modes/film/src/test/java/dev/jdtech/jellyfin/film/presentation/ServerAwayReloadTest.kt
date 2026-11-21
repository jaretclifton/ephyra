package dev.jdtech.jellyfin.film.presentation

import dev.jdtech.jellyfin.film.presentation.favorites.FavoritesViewModel
import dev.jdtech.jellyfin.film.presentation.media.MediaViewModel
import dev.jdtech.jellyfin.film.presentation.search.SearchAction
import dev.jdtech.jellyfin.film.presentation.search.SearchViewModel
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#775): Favorites, Media and an open search load again when the server goes away (their lists
 * are the downloads then) and when it comes back, once each time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAwayReloadTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private var fail = false

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The rule itself

    @Test
    fun `the first report and a report before any load load nothing`() {
        val reload = ServerAwayReload()

        assertFalse(reload.onServerAway(false))
        assertFalse(reload.onServerAway(true))
    }

    @Test
    fun `a change after a load loads again, the same state again does not`() {
        val reload = ServerAwayReload()
        reload.onServerAway(false)
        reload.begin()

        assertTrue(reload.onServerAway(true))
        reload.begin()
        assertFalse(reload.onServerAway(true))
        assertTrue(reload.onServerAway(false))
    }

    @Test
    fun `a list loaded before the first report loads again at the first change`() {
        val reload = ServerAwayReload()
        reload.begin()

        assertFalse(reload.onServerAway(false))
        assertTrue(reload.onServerAway(true))
    }

    @Test
    fun `a change back before the reload started needs none`() {
        val reload = ServerAwayReload()
        reload.onServerAway(false)
        reload.begin()

        // A probe that failed once and then answered, both before the screen loaded again.
        reload.onServerAway(true)
        assertFalse(reload.onServerAway(false))
    }

    // Media

    @Test
    fun `media loads the libraries again when the server goes and comes back`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.loadData()
            advanceUntilIdle()
            assertEquals(1, calls.size)

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(2, calls.size)

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(2, calls.size)

            viewModel.onServerAwayChanged(false)
            advanceUntilIdle()
            assertEquals(3, calls.size)
        }

    @Test
    fun `media loads once for a change and a resume together`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.loadData()
            advanceUntilIdle()

            viewModel.onServerAwayChanged(true)
            // The resume's own load, or the retry of the card, in the same moment.
            viewModel.loadData()
            advanceUntilIdle()

            assertEquals(2, calls.size)
        }

    // Favorites. Their load hops to Dispatchers.Default, so the tests join the running load
    // (loadItems hands it back) instead of only advancing the test dispatcher.

    @Test
    fun `favorites load again when the server goes and once for the page's own load too`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertEquals(1, calls.size)

            // Back on the tab after the server went away: the change and the screen's load.
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()
            assertEquals(2, calls.size)
        }

    @Test
    fun `favorites are downloads only after a load while away that succeeded`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertFalse(viewModel.state.value.downloadsOnly)

            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()
            assertTrue(viewModel.state.value.downloadsOnly)

            // Back online, the server's list again.
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()
            assertFalse(viewModel.state.value.downloadsOnly)
        }

    @Test
    fun `a failed reload while away keeps the list from the server for what it is`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.loadItems().join()

            // Nothing downloaded: Room has nothing and the server's error comes through.
            fail = true
            viewModel.onServerAwayChanged(true)
            viewModel.loadItems().join()

            assertFalse(viewModel.state.value.downloadsOnly)
            assertTrue(viewModel.state.value.error != null)
        }

    // Search

    @Test
    fun `results of a search made while away are downloads only`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertFalse(viewModel.state.value.downloadsOnly)

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.downloadsOnly)
        }

    @Test
    fun `an open search asks again when the server goes and comes back`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertEquals(listOf("bluey"), calls)

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()
            assertEquals(listOf("bluey", "bluey"), calls)

            // The error page's retry on the server's return and the change: one request.
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            assertEquals(listOf("bluey", "bluey", "bluey"), calls)
        }

    @Test
    fun `a closed or empty search asks nothing`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onServerAwayChanged(false)
            viewModel.onAction(SearchAction.Search("bluey"))
            advanceUntilIdle()
            viewModel.onAction(SearchAction.Search(""))
            advanceUntilIdle()

            viewModel.onServerAwayChanged(true)
            advanceUntilIdle()

            assertEquals(listOf("bluey"), calls)
        }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, args ->
            if (fail) throw java.net.ConnectException("Failed to connect")
            when (method.name) {
                "getLibraries" -> {
                    calls += "libraries"
                    emptyList<Any>()
                }
                "getFavoriteItems" -> {
                    calls += "favorites"
                    emptyList<Any>()
                }
                "getSearchItems" -> {
                    calls += args!![0] as String
                    emptyList<Any>()
                }
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository
}
