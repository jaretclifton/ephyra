package dev.jdtech.jellyfin.film.presentation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.movie.MovieViewModel
import dev.jdtech.jellyfin.film.presentation.show.ShowViewModel
import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.DownloadStore
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
 * CE (#332): the extras, collections and "More like this" rows are requested only when the page
 * asks for them after it is shown, once per item, and a failing request only leaves its row empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelatedItemsLoadingTest {
    private val itemId = "11111111-1111-1111-1111-111111111111".toItemId()
    private val calls = mutableListOf<String>()
    private val extraArgs = mutableListOf<Pair<Boolean, Boolean>>()
    private val failing = mutableSetOf<String>()
    /** Boolean preferences the user turned off; every other preference has its default. */
    private val switchedOff = mutableSetOf<String>()
    /** What getMovie answers from now on, in place of the movie the view model was made with. */
    @Volatile private var answer: Movie? = null
    /** #769: what the server can do; Jellyfin can do everything. */
    @Volatile private var capabilities = ServerCapabilities.ALL

    private val extra = movie(UUID.randomUUID().toString().toItemId(), "Behind the scenes")
    private val similar = movie(UUID.randomUUID().toString().toItemId(), "Similar")
    private val collection =
        BoxSet(id = UUID.randomUUID().toString().toItemId(), name = "Saga", images = ItemImages())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading the movie asks for none of the related rows`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        assertEquals(listOf("getMovie"), calls)
        assertEquals(RelatedItems(), viewModel.state.value.related)
    }

    @Test
    fun `the movie's related rows load once, each from its own request`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) {
                    viewModel.state.first {
                        it.related.extras.isNotEmpty() &&
                            it.related.collections.isNotEmpty() &&
                            it.related.similar.isNotEmpty()
                    }
                }
                .related

        assertEquals(listOf(extra), related.extras)
        assertEquals(listOf(collection), related.collections)
        assertEquals(listOf(similar), related.similar)
        assertEquals(listOf(true to true), extraArgs)

        calls.clear()
        viewModel.loadRelated(itemId)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `no extras request when the movie has none`() = runBlocking {
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 0, specialFeatures = 0))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }

        assertTrue("getExtras" !in calls)
    }

    @Test
    fun `a failing request leaves only its own row empty`() = runBlocking {
        failing += "getSimilarItems"
        failing += "getItemCollections"
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = null))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val state = withTimeout(5_000) { viewModel.state.first { it.related.extras.isNotEmpty() } }

        assertTrue(state.related.similar.isEmpty())
        assertTrue(state.related.collections.isEmpty())
        assertNull(state.error)
        // An unknown special feature count still asks for them.
        assertEquals(listOf(true to true), extraArgs)
    }

    @Test
    fun `nothing is asked before the movie is there`() {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadRelated(itemId)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the show asks for extras and similar items, not collections`() = runBlocking {
        val viewModel = showViewModel()
        viewModel.loadShow(itemId)
        withTimeout(5_000) { viewModel.state.first { it.show != null } }
        calls.clear()

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) {
                    viewModel.state.first {
                        it.related.similar.isNotEmpty() && it.related.extras.isNotEmpty()
                    }
                }
                .related

        assertEquals(listOf(similar), related.similar)
        assertTrue(related.collections.isEmpty())
        assertTrue("getItemCollections" !in calls)
        assertEquals(listOf(false to true), extraArgs)
    }

    @Test
    fun `rows switched off in the settings are never requested`() = runBlocking {
        switchedOff += listOf("pref_detail_extras", "pref_detail_similar")
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.collections.isNotEmpty() } }
                .related

        assertEquals(listOf("getMovie", "getItemCollections"), calls)
        assertTrue(related.extras.isEmpty() && related.similar.isEmpty())
    }

    @Test
    fun `#769 rows the server cannot fill are never requested`() = runBlocking {
        capabilities =
            ServerCapabilities.ALL.copy(
                extras = false,
                itemCollections = false,
                similarItems = false,
            )
        val viewModel =
            movieViewModel(movie(itemId, "Movie", localTrailers = 1, specialFeatures = 2))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }

        viewModel.loadRelated(itemId)

        assertEquals(listOf("getMovie"), synchronized(calls) { calls.toList() })
        assertEquals(RelatedItems(), viewModel.state.value.related)
    }

    @Test
    fun `#769 each row follows its own capability`() = runBlocking {
        capabilities =
            ServerCapabilities.ALL.copy(similarItems = false, cappedDownloadTranscode = false)
        val viewModel = showViewModel()
        viewModel.loadShow(itemId)
        val loaded = withTimeout(5_000) { viewModel.state.first { it.show != null } }
        assertEquals(false, loaded.cappedDownloads)
        calls.clear()

        viewModel.loadRelated(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.extras.isNotEmpty() } }.related

        assertTrue(related.similar.isEmpty())
        assertTrue("getSimilarItems" !in calls)
    }

    @Test
    fun `#769 the quality dialog offers the cap only where the server can transcode`() =
        runBlocking {
            val capped = movieViewModel(movie(itemId, "Movie"))
            capped.loadMovie(itemId)
            assertTrue(
                withTimeout(5_000) { capped.state.first { it.movie != null } }.cappedDownloads
            )

            capabilities = ServerCapabilities.ALL.copy(cappedDownloadTranscode = false)
            val original = movieViewModel(movie(itemId, "Movie"))
            original.loadMovie(itemId)
            val state = withTimeout(5_000) { original.state.first { it.movie != null } }

            assertEquals(false, state.cappedDownloads)
        }

    @Test
    fun `metadata switched off in the settings is cleared before the page gets it`() = runBlocking {
        switchedOff += listOf("pref_detail_tagline", "pref_detail_watch_history")
        val viewModel =
            movieViewModel(
                movie(itemId, "Movie")
                    .copy(tagline = "In space…", criticRating = 98f, playCount = 3)
            )
        viewModel.loadMovie(itemId)
        val movie = withTimeout(5_000) { viewModel.state.first { it.movie != null } }.movie!!

        assertNull(movie.tagline)
        assertEquals(0, movie.playCount)
        assertEquals(98f, movie.criticRating)
    }

    @Test
    fun `a row switched off goes away when the page reloads`() = runBlocking {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) {
            viewModel.state.first {
                it.related.similar.isNotEmpty() && it.related.collections.isNotEmpty()
            }
        }

        switchedOff += "pref_detail_similar"
        calls.clear()
        viewModel.loadMovie(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.similar.isEmpty() } }.related
        // The row goes before the reload asks for the others: wait for that request too, or it
        // reaches Dispatchers.Main after tearDown has reset it and fails a later runTest.
        withTimeout(5_000) {
            while ("getItemCollections" !in synchronized(calls) { calls.toList() }) delay(10)
        }

        assertEquals(listOf(collection), related.collections)
        assertTrue("getSimilarItems" !in calls)
    }

    @Test
    fun `a row switched on is requested when the page reloads`() = runBlocking {
        switchedOff += "pref_detail_similar"
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.collections.isNotEmpty() } }

        switchedOff.clear()
        viewModel.loadMovie(itemId)
        val related =
            withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }.related

        assertEquals(listOf(similar), related.similar)
    }

    @Test
    fun `a reload with the same switches asks for no row again`() = runBlocking {
        val viewModel = movieViewModel(movie(itemId, "Movie"))
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie != null } }
        viewModel.loadRelated(itemId)
        withTimeout(5_000) { viewModel.state.first { it.related.similar.isNotEmpty() } }

        // The reload's answer can be told apart, so the test waits until the reload has shown it,
        // past its hop to Dispatchers.Default and back: a load still on its way back to Main when
        // tearDown resets it fails a later runTest (#809).
        answer = movie(itemId, "Movie, reloaded")
        calls.clear()
        viewModel.loadMovie(itemId)
        withTimeout(5_000) { viewModel.state.first { it.movie?.name == "Movie, reloaded" } }

        assertEquals(listOf("getMovie"), synchronized(calls) { calls.toList() })
    }

    private fun movieViewModel(movie: Movie) =
        MovieViewModel(repository(movie), appPreferences(), VideoMetadataParser)

    private fun showViewModel(): ShowViewModel {
        val repository = repository(movie(itemId, "unused"))
        val preferences = appPreferences()
        return ShowViewModel(
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
                ),
            downloadStore = noDownloads(),
        )
    }

    private fun repository(movie: Movie): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
            // #769: not a request of the server, so not in [calls].
            if (method.kotlinName == "getCapabilities") return@fakeProxy capabilities
            synchronized(calls) { calls.add(method.kotlinName) }
            if (method.kotlinName in failing) throw IOException("${method.kotlinName} failed")
            when (method.kotlinName) {
                "getMovie" -> answer ?: movie
                "getShow" -> show()
                "getNextUp",
                "getSeasons",
                "getEpisodes" -> emptyList<Any>()
                "getExtras" -> {
                    synchronized(calls) {
                        extraArgs.add(args!![1] as Boolean to args[2] as Boolean)
                    }
                    listOf(extra)
                }
                "getItemCollections" -> listOf(collection)
                // The server may return the item itself; the row leaves it out.
                "getSimilarItems" -> listOf(similar, movie(itemId, "Self"))
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

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

    private fun movie(id: ItemId, name: String, localTrailers: Int = 0, specialFeatures: Int? = 0) =
        Movie(
            id = id,
            name = name,
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
            localTrailerCount = localTrailers,
            specialFeatureCount = specialFeatures,
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
            specialFeatureCount = 3,
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
