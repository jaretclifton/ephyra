package dev.jdtech.jellyfin.film.presentation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.di.AppPreferencesDownloadSettings
import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.ExternalIds
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.ratings.ExternalRatings
import dev.jdtech.jellyfin.ratings.RatingsKeyStatus
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.CoreDownloadTexts
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CE (#331): the movie and show pages keep room for the external ratings on their first load, ask
 * for them only once they are shown (loadRelated), once per item, and never while they are off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRatingsLoadingTest {
    private val itemId = "22222222-2222-2222-2222-222222222222".toItemId()
    private val switchedOff = mutableSetOf<String>()

    private val imdb = ExternalRating(RatingSource.IMDB, 7.8f)
    private val tomatoes = ExternalRating(RatingSource.ROTTEN_TOMATOES, 91f)

    private inner class FakeRatings : ExternalRatings {
        var on = true
        /** False while the service may not be asked (a failure, a 429, a rejected key). */
        var allowed = true
        var answer = listOf(imdb, tomatoes)
        /** Runs while a lookup is under way, before it answers. */
        var during: () -> Unit = {}
        val asked: MutableList<RatingsLookup> = Collections.synchronizedList(mutableListOf())

        override fun isOn() = on

        override fun canRequest(lookup: RatingsLookup) = on && allowed

        override fun mightHaveRatings(lookup: RatingsLookup) = on

        override suspend fun ratings(lookup: RatingsLookup): List<ExternalRating> {
            asked += lookup
            during()
            return if (on && allowed) answer else emptyList()
        }

        override suspend fun checkKey(apiKey: String) = RatingsKeyStatus.VALID
    }

    private val ratings = FakeRatings()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun loaded(movie: Movie = movie()): MovieViewModel = runBlocking {
        val viewModel =
            MovieViewModel(
                repository(movie),
                appPreferences(),
                VideoMetadataParser,
                externalRatings = ratings,
            )
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel
    }

    @Test
    fun `switched off while the lookup runs, its answer is dropped`() {
        ratings.during = { ratings.on = false }
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertEquals(1, ratings.asked.size)
    }

    @Test
    fun `a lookup the service could not answer is tried again on a reload`() = runBlocking {
        ratings.allowed = false
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)

        ratings.allowed = true
        viewModel.loadMovie(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb, tomatoes)), state.externalRatings)
        assertEquals(2, ratings.asked.size)
    }

    @Test
    fun `a title the service has nothing for is not asked again on a reload`() = runBlocking {
        ratings.answer = emptyList()
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        viewModel.loadRelated(itemId)
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        assertEquals(1, ratings.asked.size)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
    }

    @Test
    fun `the Rotten Tomatoes switch applies again on a reload`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
        }

        switchedOff += "pref_detail_critic_rating"
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Shown(listOf(imdb)) }
        }

        switchedOff.clear()
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first {
                it.externalRatings == ExternalRatingsState.Shown(listOf(imdb, tomatoes))
            }
        }
        assertEquals(1, ratings.asked.size)
    }

    @Test
    fun `the first load keeps room and asks for nothing`() {
        val viewModel = loaded()
        assertEquals(ExternalRatingsState.Pending, viewModel.state.value.externalRatings)
        assertTrue(ratings.asked.isEmpty())
    }

    @Test
    fun `the ratings come once the page is shown, once per movie`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb, tomatoes)), state.externalRatings)
        assertEquals(
            listOf(RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "603")),
            ratings.asked.toList(),
        )

        viewModel.loadRelated(itemId)
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        assertEquals(1, ratings.asked.size)
        assertEquals(
            ExternalRatingsState.Shown(listOf(imdb, tomatoes)),
            viewModel.state.value.externalRatings,
        )
    }

    @Test
    fun `off, nothing is asked and nothing is kept`() {
        ratings.on = false
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertTrue(ratings.asked.isEmpty())
    }

    @Test
    fun `a movie without an IMDb or TMDB id asks for nothing`() {
        val viewModel = loaded(movie(ExternalIds(tvdb = "1")))
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertTrue(ratings.asked.isEmpty())
    }

    @Test
    fun `no ratings hides the row`() = runBlocking {
        ratings.answer = emptyList()
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Hidden }
        }
        assertEquals(1, ratings.asked.size)
    }

    @Test
    fun `the server's own Rotten Tomatoes score is not shown twice`() = runBlocking {
        val viewModel = loaded(movie().copy(criticRating = 90f))
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb)), state.externalRatings)
    }

    @Test
    fun `Rotten Tomatoes switched off hides the service's score too`() = runBlocking {
        switchedOff += "pref_detail_critic_rating"
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        val state =
            withTimeout(5_000) {
                viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
            }
        assertEquals(ExternalRatingsState.Shown(listOf(imdb)), state.externalRatings)
    }

    @Test
    fun `switched off later, a reload hides the row`() = runBlocking {
        val viewModel = loaded()
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
        }
        ratings.on = false
        viewModel.loadMovie(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings == ExternalRatingsState.Hidden }
        }
        assertEquals(1, ratings.asked.size)
    }

    @Test
    fun `a show is looked up as a show, by its TVDB id when it has no other`() = runBlocking {
        val viewModel = showViewModel()
        viewModel.loadShow(itemId)
        withTimeout(5_000) { viewModel.state.first { it.show != null } }
        assertEquals(ExternalRatingsState.Pending, viewModel.state.value.externalRatings)
        assertTrue(ratings.asked.isEmpty())

        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first { it.externalRatings is ExternalRatingsState.Shown }
        }
        assertEquals(
            listOf(RatingsLookup(ExternalIdType.TVDB, RatedKind.SHOW, "81189")),
            ratings.asked.toList(),
        )
    }

    @Test
    fun `the downloads-only show page asks for nothing`() = runBlocking {
        val viewModel = showViewModel()
        viewModel.loadShow(itemId, downloadsOnly = true)
        withTimeout(5_000) { viewModel.state.first { it.show != null } }
        viewModel.loadRelated(itemId)
        assertEquals(ExternalRatingsState.Hidden, viewModel.state.value.externalRatings)
        assertTrue(ratings.asked.isEmpty())
    }

    private fun repository(movie: Movie): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "getCapabilities" -> ServerCapabilities.ALL
                "getMovie" -> movie
                "getShow" -> show()
                "getNextUp",
                "getSeasons",
                "getEpisodes",
                "getItemCollections",
                "getSimilarItems",
                "getExtras" -> emptyList<Any>()
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun showViewModel(): ShowViewModel {
        val repository = repository(movie())
        val preferences = appPreferences()
        return ShowViewModel(
            repository = repository,
            appPreferences = preferences,
            downloader = unused(),
            downloadQueue =
                DownloadQueue(
                    downloader = unused(),
                    settings = AppPreferencesDownloadSettings(preferences),
                    repositoryProvider = { repository },
                    downloadStore = unused<DownloadStore>(),
                    context = ContextWrapper(null) as Context,
                    texts = CoreDownloadTexts,
                ),
            downloadStore = noDownloads(),
            externalRatings = ratings,
        )
    }

    private fun noDownloads(): DownloadStore =
        fakeProxy(DownloadStore::class.java.classLoader, arrayOf(DownloadStore::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "seasonEpisodeCounts" -> emptyList<Any>()
                else -> throw NotImplementedError("DownloadStore.${method.kotlinName}")
            }
        }
            as DownloadStore

    private fun movie(externalIds: ExternalIds = ExternalIds(tmdb = "603", imdb = "tt0133093")) =
        Movie(
            id = itemId,
            name = "Movie",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            externalIds = externalIds,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun show() =
        Show(
            id = itemId,
            name = "Show",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            seasons = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            unplayedItemCount = null,
            genres = emptyList(),
            people = emptyList(),
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            externalIds = ExternalIds(tvdb = "81189"),
            images = ItemImages(),
        )

    private fun appPreferences(): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean" -> if (args!![0] in switchedOff) false else args[1]
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
