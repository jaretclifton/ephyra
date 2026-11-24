package dev.jdtech.jellyfin.film.presentation.library

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#371): the library screen's first load is decided by the ViewModel, not by a flag saved in
 * the screen. After process death the saved flag came back as "loaded" while the ViewModel was new,
 * and the grid spun forever.
 *
 * #968: the ViewModel builds its pager itself, and a pager asks the repository nothing until the
 * screen collects it. A load is therefore counted as a new pager in the state, for the library the
 * ViewModel was set up with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLoadOnceTest {
    private val movies = "11111111-1111-1111-1111-111111111111".toItemId()
    private val shows = "22222222-2222-2222-2222-222222222222".toItemId()
    private val loads = mutableListOf<ItemId?>()
    private var currentLibrary: ItemId? = null
    private val watchers = CoroutineScope(UnconfinedTestDispatcher())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        watchers.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `a new ViewModel loads, as after process death`() {
        val viewModel = viewModel().watched()
        viewModel.setUpFor(movies, CollectionType.Movies)
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<ItemId?>(movies), loads)
    }

    @Test
    fun `coming back to the screen does not load again`() {
        val viewModel = viewModel().watched()
        repeat(3) {
            viewModel.setUpFor(movies, CollectionType.Movies)
            viewModel.loadItemsIfNeeded()
        }

        assertEquals(listOf<ItemId?>(movies), loads)
    }

    @Test
    fun `another library on the same ViewModel loads`() {
        val viewModel = viewModel().watched()
        viewModel.setUpFor(movies, CollectionType.Movies)
        viewModel.loadItemsIfNeeded()
        viewModel.setUpFor(shows, CollectionType.TvShows)
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<ItemId?>(movies, shows), loads)
    }

    @Test
    fun `a sort change still loads again`() {
        val viewModel = viewModel().watched()
        viewModel.setUpFor(movies, CollectionType.Movies)
        viewModel.loadItemsIfNeeded()
        viewModel.onAction(LibraryAction.ChangeSorting(SortBy.DATE_ADDED, SortOrder.DESCENDING))
        viewModel.loadItemsIfNeeded()

        assertEquals(listOf<ItemId?>(movies, movies), loads)
    }

    private fun viewModel() = LibraryViewModel(repository(), appPreferences())

    /** The ViewModel set up for [library]: every new pager in its state is a load of [library]. */
    private fun LibraryViewModel.setUpFor(library: ItemId, type: CollectionType) {
        currentLibrary = library
        setup(parentId = library, libraryType = type)
    }

    private fun LibraryViewModel.watched(): LibraryViewModel {
        var seen = state.value.items
        watchers.launch {
            state.collect {
                if (it.items !== seen) {
                    seen = it.items
                    loads.add(currentLibrary)
                }
            }
        }
        return this
    }

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
            // Nothing collects the pagers, so nothing is asked.
            throw NotImplementedError("MediaRepository.${method.kotlinName}")
        }
            as MediaRepository

    private fun appPreferences(): AppPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor =
            fakeProxy(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, _ ->
                when (method.kotlinName) {
                    "apply" -> Unit
                    "commit" -> true
                    else -> editor
                }
            }
                as SharedPreferences.Editor
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean",
                    "getString",
                    "getInt",
                    "getLong",
                    "getFloat",
                    "getStringSet" -> args!![1]
                    "edit" -> editor
                    else ->
                        throw UnsupportedOperationException(
                            "SharedPreferences.${method.kotlinName}"
                        )
                }
            }
                as SharedPreferences
        return AppPreferences(preferences)
    }
}
