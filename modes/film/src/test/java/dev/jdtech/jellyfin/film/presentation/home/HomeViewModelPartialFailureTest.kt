package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.asItemId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

    private val viewModel by lazy {
        HomeViewModel(
            repository = repository(),
            appPreferences = AppPreferences(sharedPreferences()),
            database = unusedDao(),
        )
    }

    @BeforeEach
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a failing row leaves the other rows loaded without an error`() {
        failing += "getNextUp"

        val state = load { it.resumeSection != null && it.views.size == 2 }

        assertNull(state.nextUpSection)
        assertNotNull(state.suggestionsSection)
        assertNull(state.error)
    }

    @Test
    fun `a failing library leaves the other libraries loaded without an error`() {
        failingLibraries += libraryA.id

        val state = load { it.views.size == 1 }

        assertEquals(libraryB.id, state.views.single().id)
        assertNotNull(state.resumeSection)
        assertNull(state.error)
    }

    @Test
    fun `a row that fails on a silent reload keeps its content`() {
        val first = load { it.nextUpSection != null && it.views.size == 2 }

        failing += "getNextUp"
        failingLibraries += libraryA.id
        viewModel.loadData(silent = true)
        // The libraries update together, so a new library B means library A has been handled.
        val state = settle { it.views.first { v -> v.id == libraryB.id } == first.views.last() }

        assertSame(first.nextUpSection, state.nextUpSection)
        assertSame(
            first.views.first { it.id == libraryA.id },
            state.views.first { it.id == libraryA.id },
        )
        assertNull(state.error)
    }

    @Test
    fun `no libraries left removes the Latest rows that were on screen`() {
        // #428: offline with nothing downloaded there are no libraries, and the Latest rows
        // remembered from an online start stayed. Rows from an earlier load stand in for them.
        load { it.views.size == 2 }

        libraries = emptyList()
        runBlocking { withTimeout(5_000) { viewModel.loadData(silent = true).join() } }

        assertEquals(0, viewModel.state.value.views.size)
        assertNotNull(viewModel.state.value.resumeSection)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `every row failing shows the error`() {
        failing += listOf("getSuggestions", "getResumeItems", "getNextUp", "getUserViews")

        assertNotNull(load { it.error != null }.error)
    }

    @Test
    fun `every library failing counts as the row failing`() {
        failing += listOf("getSuggestions", "getResumeItems", "getNextUp")
        failingLibraries += listOf(libraryA.id, libraryB.id)

        assertNotNull(load { it.error != null }.error)
    }

    @Test
    fun `rows turned off don't hide that every other row failed`() {
        turnedOff += listOf("home_suggestions", "home_continue_watching", "home_latest")
        failing += "getNextUp"

        assertNotNull(load { it.error != null }.error)
    }

    private fun load(done: (HomeState) -> Boolean): HomeState {
        viewModel.loadData()
        return settle { !done(it) }
    }

    /** loadData() runs on Dispatchers.Default; wait until it has finished. */
    private fun settle(waiting: (HomeState) -> Boolean): HomeState = runBlocking {
        withTimeout(5_000) {
            while (with(viewModel.state.value) { isLoading || waiting(this) }) delay(10)
            viewModel.state.value
        }
    }

    private fun library() =
        FindroidCollection(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Movies",
            type = CollectionType.Movies,
            images = FindroidImages(),
        )

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
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
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun item(): FindroidItem =
        fakeProxy(FindroidItem::class.java.classLoader, arrayOf(FindroidItem::class.java)) {
            proxy,
            method,
            args ->
            // StateFlow compares each new state with the last one.
            when (method.kotlinName) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> throw NotImplementedError("FindroidItem.${method.kotlinName}")
            }
        }
            as FindroidItem

    private fun unusedDao(): ServerDatabaseDao =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw NotImplementedError(
                "ServerDatabaseDao.${method.kotlinName} not expected in this test"
            )
        }
            as ServerDatabaseDao

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
