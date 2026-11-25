package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.repository.StoredUsers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** One Home row or library failing must leave the rest of [HomeViewModel] on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPartialFailureTest {
    private val libraryA = library()
    private val libraryB = library()
    private val failing = mutableSetOf<String>()
    private val failingLibraries = mutableSetOf<ItemId>()
    private val turnedOff = mutableSetOf<String>()
    private var libraries = listOf(libraryA, libraryB)

    /** #955: Main and the loads on one test scheduler, so no load outlives its test. */
    private val dispatcher = StandardTestDispatcher()

    private val viewModel by lazy {
        HomeViewModel(
            lists = repository.role(),
            catalog = repository.role(),
            account = repository.role(),
            appPreferences = AppPreferences(sharedPreferences()),
            storedServers = unusedDao.role(),
            storedUsers = unusedDao.role(),
            loadDispatcher = dispatcher,
        )
    }

    @BeforeEach
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a failing row leaves the other rows loaded without an error`() =
        runTest(dispatcher) {
            failing += "getNextUp"

            val state = load()

            assertNotNull(state.resumeSection)
            assertEquals(2, state.views.size)
            assertNull(state.nextUpSection)
            assertNotNull(state.suggestionsSection)
            assertNull(state.error)
        }

    @Test
    fun `a failing library leaves the other libraries loaded without an error`() =
        runTest(dispatcher) {
            failingLibraries += libraryA.id

            val state = load()

            assertEquals(libraryB.id, state.views.single().id)
            assertNotNull(state.resumeSection)
            assertNull(state.error)
        }

    @Test
    fun `a row that fails on a silent reload keeps its content`() =
        runTest(dispatcher) {
            val first = load()
            assertNotNull(first.nextUpSection)
            assertEquals(2, first.views.size)

            failing += "getNextUp"
            failingLibraries += libraryA.id
            val state = settle(viewModel.loadData(silent = true))

            // The libraries update together, so a new library B means library A has been handled.
            assertTrue(state.views.first { v -> v.id == libraryB.id } != first.views.last())
            assertSame(first.nextUpSection, state.nextUpSection)
            assertSame(
                first.views.first { it.id == libraryA.id },
                state.views.first { it.id == libraryA.id },
            )
            assertNull(state.error)
        }

    @Test
    fun `no libraries left removes the Latest rows that were on screen`() =
        runTest(dispatcher) {
            // #428: offline with nothing downloaded there are no libraries, and the Latest rows
            // remembered from an online start stayed. Rows from an earlier load stand in for them.
            assertEquals(2, load().views.size)

            libraries = emptyList()
            settle(viewModel.loadData(silent = true))

            assertEquals(0, viewModel.state.value.views.size)
            assertNotNull(viewModel.state.value.resumeSection)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `every row failing shows the error`() =
        runTest(dispatcher) {
            failing += listOf("getSuggestions", "getResumeItems", "getNextUp", "getUserViews")

            assertNotNull(load().error)
        }

    @Test
    fun `every library failing counts as the row failing`() =
        runTest(dispatcher) {
            failing += listOf("getSuggestions", "getResumeItems", "getNextUp")
            failingLibraries += listOf(libraryA.id, libraryB.id)

            assertNotNull(load().error)
        }

    @Test
    fun `rows turned off don't hide that every other row failed`() =
        runTest(dispatcher) {
            turnedOff += listOf("home_suggestions", "home_continue_watching", "home_latest")
            failing += "getNextUp"

            assertNotNull(load().error)
        }

    private fun TestScope.load(): HomeState = settle(viewModel.loadData())

    /** Runs [load] to its end. */
    private fun TestScope.settle(load: Job): HomeState {
        advanceUntilIdle()
        assertTrue(load.isCompleted)
        assertFalse(viewModel.state.value.isLoading)
        return viewModel.state.value
    }

    private fun library() =
        Library(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Movies",
            type = CollectionType.Movies,
            images = ItemImages(),
        )

    private val repository: FakeRoles =
        FakeRoles(ItemLists::class, ItemCatalog::class, CurrentAccount::class) { _, method, args ->
            if (method.kotlinName in failing) throw IllegalStateException(method.kotlinName)
            when (method.kotlinName) {
                "getSuggestions",
                "getResumeItems",
                "getNextUp" -> listOf(item())
                "getUserViews" -> libraries
                "getLatestMedia" -> {
                    if (args!![0].asItemId() in failingLibraries) throw IOException("library")
                    listOf(item())
                }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun item(): Item =
        fakeProxy(Item::class.java.classLoader, arrayOf(Item::class.java)) { proxy, method, args ->
            // StateFlow compares each new state with the last one.
            when (method.kotlinName) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> throw NotImplementedError("Item.${method.kotlinName}")
            }
        }
            as Item

    private val unusedDao: FakeRoles =
        FakeRoles(StoredServers::class, StoredUsers::class) { _, method, _ ->
            throw NotImplementedError(
                "${method.declaringClass.simpleName}.${method.kotlinName} not expected in this test"
            )
        }

    private fun sharedPreferences(): SharedPreferences =
        fakeProxy(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getBoolean" -> if (args!![0] in turnedOff) false else args[1]
                "getString" -> args!![1]
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences
}
