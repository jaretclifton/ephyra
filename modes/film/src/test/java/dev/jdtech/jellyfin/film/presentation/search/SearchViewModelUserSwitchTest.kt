package dev.jdtech.jellyfin.film.presentation.search

import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.ItemsResult
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #866: the search lives in the Media tab, which stays on its stack across a user switch. Opened
 * again after a switch, it showed the previous user's results until the new search answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelUserSwitchTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = UUID.randomUUID().toString().toUserId()
    private val bob = UUID.randomUUID().toString().toUserId()
    private val aliceResults =
        listOf(dummyMovie.copy(id = UUID.randomUUID().toString().toItemId(), name = "Alice's"))
    private val bobResults =
        listOf(dummyMovie.copy(id = UUID.randomUUID().toString().toItemId(), name = "Bob's"))

    /** The user the client acts for. */
    private var sessionUser: UserId = alice

    /** Whether the server fails the search. */
    private var searchFails = false

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the search shows the new user's results after a switch, never the previous user's`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onAction(SearchAction.Search("movie"))
            advanceUntilIdle()
            assertEquals(aliceResults, viewModel.state.value.items)

            // Home's profile sheet, a login or the Users screen: the client acts for Bob now.
            sessionUser = bob

            // The Media tab comes back, and its search bar searches its query again.
            viewModel.onAction(SearchAction.Search("movie"))
            runCurrent()
            // During the debounce and the request: none of Alice's results.
            assertEquals(emptyList(), viewModel.state.value.items)
            assertEquals(bob, viewModel.state.value.userId)
            advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS - 1)
            runCurrent()
            assertEquals(emptyList(), viewModel.state.value.items)
            advanceUntilIdle()

            assertEquals(bobResults, viewModel.state.value.items)
            assertEquals("movie", viewModel.state.value.query)
            assertEquals(bob, viewModel.state.value.userId)
        }

    @Test
    fun `a failed first search is the previous user's, so a switch hides its error`() =
        runTest(dispatcher) {
            searchFails = true
            val viewModel = SearchViewModel(repository())
            viewModel.onAction(SearchAction.Search("movie"))
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.error)
            assertEquals(alice, viewModel.state.value.userId)

            // The screen, after the client switched to Bob and before the search runs again.
            val forBob = viewModel.state.value.shownTo(bob)

            assertNull(forBob.error)
            assertTrue(forBob.loading)
        }

    @Test
    fun `results asked for after a switch are not labelled as the previous user's`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onAction(SearchAction.Search("movie"))
            // The switch comes during the debounce: the request goes out as Bob.
            advanceTimeBy(SearchViewModel.SEARCH_DEBOUNCE_MS - 1)
            sessionUser = bob
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(
                state.items.isEmpty() || state.userId == bob,
                "Bob's results under the label ${state.userId}",
            )
        }

    @Test
    fun `the same user's results stay while a new search runs`() =
        runTest(dispatcher) {
            val viewModel = SearchViewModel(repository())
            viewModel.onAction(SearchAction.Search("movie"))
            advanceUntilIdle()

            viewModel.onAction(SearchAction.Search("movies"))
            runCurrent()

            assertEquals(aliceResults, viewModel.state.value.items)
        }

    @Test
    fun `the search shows nothing of another user's results`() {
        val aliceState = SearchState(query = "movie", items = aliceResults, userId = alice)

        val forBob = aliceState.shownTo(bob)

        assertEquals(emptyList(), forBob.items)
        assertEquals("", forBob.query)
        assertTrue(forBob.loading)
        assertEquals(aliceState, aliceState.shownTo(alice))
        // No session at hand (previews), or results from before the user was known.
        assertEquals(aliceState, aliceState.shownTo(null))
        val unknown = aliceState.copy(userId = null)
        assertEquals(unknown, unknown.shownTo(bob))
    }

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "getCapabilities" -> ServerCapabilities.ALL
                "getUserId" -> sessionUser
                "getSearchItems" ->
                    if (searchFails) throw IllegalStateException("No connection")
                    else ItemsResult(if (sessionUser == bob) bobResults else aliceResults)
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository
}
