package dev.jdtech.jellyfin.film.presentation.favorites

import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.ItemsResult
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * #866: Favorites stays on its stack across a user switch, and its reload on return is quiet. The
 * previous user's favourites stayed on screen until the new user's load ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelUserSwitchTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val aliceFavorites = listOf(movie("Alice's movie"), movie("Alice's other movie"))
    private val bobFavorites = listOf(movie("Bob's movie"))

    /** The user the client acts for. */
    private var sessionUser: UUID? = alice

    /** Whether the server fails the favourites request. */
    private var favoritesFail = false

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen shows the new user's favourites after a switch, never the previous user's`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository(), DataChangeEvents(), dispatcher)
            viewModel.loadItems()
            advanceUntilIdle()
            assertEquals(aliceFavorites, items(viewModel.state.value))
            assertFalse(viewModel.shouldReload())

            // Home's profile sheet, a login or the Users screen: the client acts for Bob now.
            sessionUser = bob

            // FavoritesScreen's ON_RESUME loads on entry.
            assertTrue(viewModel.shouldReload())
            viewModel.loadItems()
            // Before Bob's favourites arrive: none of Alice's, and a loading list.
            assertEquals(emptyList(), items(viewModel.state.value))
            assertTrue(viewModel.state.value.isLoading)
            advanceUntilIdle()

            assertEquals(bobFavorites, items(viewModel.state.value))
            assertEquals(bob, viewModel.state.value.userId)
            assertFalse(viewModel.state.value.isLoading)
            assertFalse(viewModel.shouldReload())
        }

    @Test
    fun `a load while no one is signed in does not hide the next switch`() =
        runTest(dispatcher) {
            val viewModel = FavoritesViewModel(repository(), DataChangeEvents(), dispatcher)
            viewModel.loadItems()
            advanceUntilIdle()

            // The session is cleared (token revoked, current user removed), and a load runs in
            // that gap, such as the store retry. It fails without a user.
            sessionUser = null
            viewModel.loadItems()
            advanceUntilIdle()

            // Bob signs in and opens Favorites; his first load fails.
            sessionUser = bob
            favoritesFail = true
            assertTrue(viewModel.shouldReload())
            viewModel.loadItems()
            advanceUntilIdle()

            // What the screen shows Bob: his error with its Retry, not a spinner that never ends.
            val shown = viewModel.state.value.shownTo(bob)
            assertEquals(emptyList(), items(shown))
            assertNotNull(shown.error)
            assertFalse(shown.isLoading)
        }

    @Test
    fun `the screen shows nothing of another user's favourites`() {
        val aliceState =
            CollectionState(
                sections =
                    listOf(CollectionSection(0, UiText.DynamicString("Movies"), aliceFavorites)),
                isLoading = false,
                userId = alice,
            )

        val forBob = aliceState.shownTo(bob)

        assertEquals(emptyList(), forBob.sections)
        assertTrue(forBob.isLoading)
        assertEquals(aliceState, aliceState.shownTo(alice))
        // No session at hand (previews), or a list from before the user was known.
        assertEquals(aliceState, aliceState.shownTo(null))
        val unknown = aliceState.copy(userId = null)
        assertEquals(unknown, unknown.shownTo(bob))
    }

    private fun items(state: CollectionState): List<FindroidItem> =
        state.sections.flatMap { it.items }

    private fun repository(): JellyfinRepository =
        Proxy.newProxyInstance(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> ServerCapabilities.ALL
                "getUserId" -> sessionUser ?: throw IllegalStateException("No user")
                "getFavoriteItems" ->
                    if (sessionUser == null || favoritesFail)
                        throw IllegalStateException("No answer")
                    else ItemsResult(if (sessionUser == bob) bobFavorites else aliceFavorites)
                else -> throw NotImplementedError("JellyfinRepository.${method.name}")
            }
        } as JellyfinRepository

    private fun movie(name: String) = dummyMovie.copy(id = UUID.randomUUID(), name = name)
}
