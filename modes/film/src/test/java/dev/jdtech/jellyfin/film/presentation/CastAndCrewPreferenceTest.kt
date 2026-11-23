package dev.jdtech.jellyfin.film.presentation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.ItemPersonImage
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.CoreDownloadTexts
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Drives the real detail ViewModels with the "Display cast and crew" setting on and off. The phone
 * and TV screens both hide a section when its state is empty, so the state is what decides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastAndCrewPreferenceTest {
    private val itemId = "11111111-1111-1111-1111-111111111111".toItemId()

    private val actor = person("Actor", PersonKind.ACTOR)
    private val director = person("Director", PersonKind.DIRECTOR)
    private val writer = person("Writer", PersonKind.WRITER)
    private val people = listOf(actor, director, writer)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `movie shows the cast by default, and the director and writers once asked for`() {
        val byDefault = loadMovie(appPreferences(stored = null))

        assertEquals(listOf(actor), byDefault.actors)
        // #510: the credits under the overview are off until turned on.
        assertNull(byDefault.director)
        assertTrue(byDefault.writers.isEmpty())

        val withCredits = loadMovie(appPreferences(stored = null, credits = true))

        assertEquals(listOf(actor), withCredits.actors)
        assertEquals(director, withCredits.director)
        assertEquals(listOf(writer), withCredits.writers)
    }

    @Test
    fun `movie credits stay hidden while cast and crew is off`() {
        val state = loadMovie(appPreferences(stored = false, credits = true))

        assertTrue(state.actors.isEmpty())
        assertNull(state.director)
        assertTrue(state.writers.isEmpty())
    }

    @Test
    fun `movie hides cast and crew when turned off`() {
        val state = loadMovie(appPreferences(stored = false))

        assertTrue(state.actors.isEmpty())
        assertNull(state.director)
        assertTrue(state.writers.isEmpty())
    }

    @Test
    fun `show shows the cast by default, and the director and writers once asked for`() {
        val byDefault = loadShow(appPreferences(stored = null))

        assertEquals(listOf(actor), byDefault.actors)
        assertNull(byDefault.director)
        assertTrue(byDefault.writers.isEmpty())

        val withCredits = loadShow(appPreferences(stored = null, credits = true))

        assertEquals(listOf(actor), withCredits.actors)
        assertEquals(director, withCredits.director)
        assertEquals(listOf(writer), withCredits.writers)
    }

    @Test
    fun `show hides cast and crew when turned off`() {
        val state = loadShow(appPreferences(stored = false))

        assertTrue(state.actors.isEmpty())
        assertNull(state.director)
        assertTrue(state.writers.isEmpty())
    }

    @Test
    fun `episode shows actors by default`() {
        assertEquals(listOf(actor), loadEpisode(appPreferences(stored = null)).actors)
    }

    @Test
    fun `episode hides actors when turned off`() {
        assertTrue(loadEpisode(appPreferences(stored = false)).actors.isEmpty())
    }

    private fun loadMovie(preferences: AppPreferences) = runBlocking {
        val viewModel = MovieViewModel(repository(), preferences, VideoMetadataParser)
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
    }

    private fun loadShow(preferences: AppPreferences) = runBlocking {
        val repository = repository()
        val viewModel =
            ShowViewModel(
                repository = repository,
                appPreferences = preferences,
                downloader = unused(),
                downloadQueue =
                    DownloadQueue(
                        downloader = unused(),
                        appPreferences = preferences,
                        repositoryProvider = { repository },
                        downloadStore = unused<DownloadStore>(),
                        context = ContextWrapper(null) as Context,
                        texts = CoreDownloadTexts,
                    ),
                downloadStore = noDownloads(),
            )
        viewModel.loadShow(itemId)
        withTimeout(5_000) { viewModel.state.first { it.show != null } }
    }

    private fun loadEpisode(preferences: AppPreferences) = runBlocking {
        val viewModel = EpisodeViewModel(repository(), preferences, VideoMetadataParser)
        viewModel.loadEpisode(itemId)
        withTimeout(5_000) { viewModel.state.first { it.episode != null } }
    }

    private fun person(name: String, type: PersonKind) =
        ItemPerson(
            id = UUID.nameUUIDFromBytes(name.toByteArray()).toString().toItemId(),
            name = name,
            type = type,
            role = "",
            image = ItemPersonImage(uri = null, blurHash = null),
        )

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            // #769: Jellyfin can do everything.
            if (method.kotlinName == "getCapabilities") return@fakeProxy ServerCapabilities.ALL
            when (method.kotlinName) {
                "getMovie" -> movie()
                "getShow" -> show()
                "getEpisode" -> episode()
                "getNextUp",
                "getSeasons",
                "getEpisodes" -> emptyList<Any>()
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun movie() =
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
            people = people,
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
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
            canDownload = false,
            unplayedItemCount = null,
            genres = emptyList(),
            people = people,
            runtimeTicks = 0L,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = ItemImages(),
        )

    private fun episode() =
        Episode(
            id = itemId,
            name = "Episode",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = itemId,
            seriesName = "Show",
            seasonId = itemId,
            seasonName = null,
            communityRating = null,
            people = people,
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    /** Preferences where only the cast and crew switch may be stored; null means never touched. */
    private fun appPreferences(stored: Boolean?, credits: Boolean? = null): AppPreferences {
        val preferences =
            fakeProxy(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.kotlinName) {
                    "getBoolean" ->
                        when {
                            args!![0] == "pref_display_cast_and_crew" && stored != null -> stored
                            args[0] == "pref_detail_credits" && credits != null -> credits
                            else -> args[1]
                        }
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

    /** A download store with nothing downloaded, for the show page's download counts. */
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

    private inline fun <reified T : Any> unused(): T =
        fakeProxy(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("${T::class.java.simpleName}.${method.kotlinName}")
        }
            as T
}
