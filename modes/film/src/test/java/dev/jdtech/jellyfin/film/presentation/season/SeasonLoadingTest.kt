package dev.jdtech.jellyfin.film.presentation.season

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** CE (#505): the season page can tell a season without episodes from one that is still loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonLoadingTest {
    private val seriesId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val seasonId = "22222222-2222-2222-2222-222222222223".toItemId()

    private var episodesFail = false
    private var storedEpisodes = emptyList<Any>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a season without episodes ends loaded with no episodes`() = runBlocking {
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { !it.isLoading } }

        assertNotNull(state.season)
        assertTrue(state.episodes.isEmpty())
        assertEquals(null, state.error)
    }

    @Test
    fun `the season is loading until its episodes have come`() = runBlocking {
        val viewModel = viewModel()
        val seen = mutableListOf<SeasonState>()
        val collector = launch(Dispatchers.Unconfined) { viewModel.state.collect { seen.add(it) } }
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        withTimeout(5_000) { viewModel.state.first { !it.isLoading && it.season != null } }
        collector.cancel()

        // Every state before the last one, the season shown early included, is still loading.
        val loading = seen.drop(1).dropLast(1)
        assertTrue(loading.isNotEmpty(), message = "no state while loading: $seen")
        assertTrue(
            loading.all { it.isLoading },
            message = "not loading before the episodes came: $seen",
        )
        assertFalse(seen.last().isLoading)
    }

    @Test
    fun `a failed load is no longer loading`() = runBlocking {
        episodesFail = true
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { it.error != null } }

        assertFalse(state.isLoading)
    }

    @Test
    fun `episodes from the server are not stored only`() = runBlocking {
        storedEpisodes = listOf(dummyEpisode)
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { !it.isLoading } }

        assertFalse(state.storedOnly, message = "the server's list keeps a removed download")
    }

    @Test
    fun `the stored episodes stand in for a server that fails, and say so`() = runBlocking {
        // #281: without Offline Mode, a removed download leaves such a list at once.
        storedEpisodes = listOf(dummyEpisode)
        episodesFail = true
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { !it.isLoading } }

        assertEquals(listOf(dummyEpisode.id), state.episodes.map { it.id })
        assertTrue(state.storedOnly, message = "the list is the stored episodes")
    }

    @Test
    fun `the downloads-only page is stored only`() = runBlocking {
        storedEpisodes = listOf(dummyEpisode)
        val viewModel = viewModel()
        viewModel.loadSeason(seasonId, downloadsOnly = true, seriesId = seriesId)
        val state = withTimeout(5_000) { viewModel.state.first { !it.isLoading } }

        assertTrue(state.storedOnly)
    }

    private fun viewModel(): SeasonViewModel {
        val repository = repository()
        val preferences = appPreferences()
        return SeasonViewModel(
            repository = repository,
            downloader = unused(),
            downloadQueue =
                DownloadQueue(
                    downloader = unused(),
                    appPreferences = preferences,
                    repositoryProvider = { repository },
                    downloadStore = unused<DownloadStore>(),
                    context = ContextWrapper(null) as Context,
                ),
        )
    }

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
            // #769: Jellyfin can do everything.
            if (method.kotlinName == "getCapabilities") return@fakeProxy ServerCapabilities.ALL
            when (method.kotlinName) {
                "getSeason" -> season()
                "getEpisodes" -> {
                    val offline = args!![args.size - 2] as Boolean
                    if (!offline && episodesFail) throw IllegalStateException("No connection")
                    if (offline) storedEpisodes else emptyList<Any>()
                }
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun season() =
        Season(
            id = seasonId,
            name = "Season 3",
            seriesId = seriesId,
            seriesName = "Test Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            indexNumber = 3,
            episodes = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            unplayedItemCount = null,
            images = ItemImages(),
        )

    private fun appPreferences(): AppPreferences {
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
                    else ->
                        throw UnsupportedOperationException(
                            "SharedPreferences.${method.kotlinName}"
                        )
                }
            }
                as SharedPreferences
        return AppPreferences(preferences)
    }

    private inline fun <reified T : Any> unused(): T =
        fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.kotlinName}")
        }
            as T
}
