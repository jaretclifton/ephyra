package dev.jdtech.jellyfin.film.presentation.media

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Library
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
 * #810: the Media tab stays on its stack across a user switch. Opened again less than a minute
 * after its last load, it showed the previous user's libraries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewModelUserSwitchTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = UUID.randomUUID().toString().toUserId()
    private val bob = UUID.randomUUID().toString().toUserId()
    private val aliceLibraries = listOf(library("Alice's movies"))
    private val bobLibraries = listOf(library("Bob's shows"), library("Bob's music"))

    /** The user the client acts for. */
    private var sessionUser: UserId = alice

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the tab shows the new user's libraries after a switch`() =
        runTest(dispatcher) {
            val viewModel = MediaViewModel(repository())
            viewModel.loadData()
            advanceUntilIdle()
            assertEquals(aliceLibraries, viewModel.state.value.libraries)
            assertFalse(viewModel.shouldReload())

            // Home's profile sheet, a login or the Users screen: the client acts for Bob now.
            sessionUser = bob

            // The tab comes back into view (MediaScreen's ON_RESUME).
            assertTrue(viewModel.shouldReload())
            viewModel.loadData(silent = viewModel.state.value.libraries.isNotEmpty())
            // Before Bob's libraries arrive: none of Alice's, and a loading list.
            assertEquals(emptyList(), viewModel.state.value.libraries)
            assertTrue(viewModel.state.value.isLoading)
            advanceUntilIdle()

            assertEquals(bobLibraries, viewModel.state.value.libraries)
            assertEquals(bob, viewModel.state.value.userId)
            assertFalse(viewModel.state.value.isLoading)
            assertFalse(viewModel.shouldReload())
        }

    @Test
    fun `the screen shows nothing of another user's libraries`() {
        val aliceState = MediaState(libraries = aliceLibraries, userId = alice)

        val forBob = aliceState.shownTo(bob)

        assertEquals(emptyList(), forBob.libraries)
        assertTrue(forBob.isLoading)
        assertEquals(aliceState, aliceState.shownTo(alice))
        // No session at hand (previews), or a list from before the user was known.
        assertEquals(aliceState, aliceState.shownTo(null))
        val unknown = MediaState(libraries = aliceLibraries)
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
                "getLibraries" ->
                    ItemsResult(if (sessionUser == bob) bobLibraries else aliceLibraries)
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun library(name: String) =
        Library(
            id = UUID.randomUUID().toString().toItemId(),
            name = name,
            type = CollectionType.Movies,
            images = ItemImages(),
        )
}
