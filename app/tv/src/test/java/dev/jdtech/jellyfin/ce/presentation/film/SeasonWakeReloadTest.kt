package dev.jdtech.jellyfin.ce.presentation.film

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.core.presentation.dummy.dummySeason
import dev.jdtech.jellyfin.di.AppPreferencesDownloadSettings
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.episode.EpisodeViewModel
import dev.jdtech.jellyfin.film.presentation.season.SeasonViewModel
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.DownloadSources
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.UserDataUpdates
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.CoreDownloadTexts
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CE (#660): the TV season page left open while the TV sleeps loads again when it wakes, if it is
 * out of date: the season (the cards) first, then the details panel, each once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-television")
class SeasonWakeReloadTest {
    @get:Rule val rule = createComposeRule()

    private val season = dummySeason.copy(id = UUID(6, 1).toString().toItemId())

    // Episode 1 is 4 minutes into its 12; episode 2 not started.
    private var serverEpisodes: List<Episode> =
        (1..2).map {
            dummyEpisode.copy(
                id = UUID(6, 10L + it).toString().toItemId(),
                name = "Episode $it",
                indexNumber = it,
                seasonId = season.id,
                seriesId = season.seriesId,
                runtimeTicks = 12 * Ticks.PER_MINUTE,
                playbackPositionTicks = if (it == 1) 4 * Ticks.PER_MINUTE else 0,
                played = false,
            )
        }

    /** The server's calls, in order: "season" for a season load, "episode" for the panel. */
    private val calls = mutableListOf<String>()

    private val dataChanges = DataChangeEvents()
    private val app = TestLifecycleOwner()

    private fun setPage() {
        val repository = repository()
        val seasonViewModel =
            SeasonViewModel(
                catalog = repository.role(),
                userData = repository.role(),
                capabilities = { ServerCapabilities.ALL },
                downloader = unused(),
                downloadQueue =
                    DownloadQueue(
                        downloader = unused(),
                        settings = AppPreferencesDownloadSettings(appPreferences()),
                        downloadSources = unused<DownloadSources>(),
                        context = ContextWrapper(null) as Context,
                        texts = CoreDownloadTexts,
                    ),
                dataChanges = dataChanges,
            )
        val episodeViewModel =
            EpisodeViewModel(
                catalog = repository.role(),
                userData = repository.role(),
                capabilities = { ServerCapabilities.ALL },
                appPreferences = appPreferences(),
                videoMetadataParser = VideoMetadataParser,
                dataChanges = dataChanges,
            )
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides app) {
                FindroidTheme {
                    SeasonScreen(
                        seasonId = season.id,
                        episodeId = null,
                        navigateToPlayer = { _, _, _ -> },
                        seriesId = season.seriesId,
                        seasonViewModel = seasonViewModel,
                        episodeViewModel = episodeViewModel,
                    )
                }
            }
        }
        // The panel loads once focus has rested on the card.
        rule.waitUntil(timeoutMillis = 5_000) { "episode" in calls }
        rule.waitForIdle()
    }

    /** The TV goes to standby (or the screensaver starts) and comes back. */
    private fun sleepAndWake() {
        rule.runOnIdle {
            for (event in
                listOf(
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP,
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME,
                )) {
                app.registry.handleLifecycleEvent(event)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the page loads once on entry`() {
        setPage()

        assertEquals(listOf("season", "episode"), calls)
        rule.onNodeWithText("Resume")
        rule.onNodeWithText("8 mins left")
    }

    @Test
    fun `a wake with the page up to date loads nothing`() {
        setPage()
        calls.clear()

        sleepAndWake()

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a wake with the page out of date loads the season, then the panel, once each`() {
        setPage()
        calls.clear()

        // Finished on another device while the TV slept. Something else in the app changed too,
        // so the page is out of date (as it is after 60 s).
        serverEpisodes = serverEpisodes.mapIndexed { i, e ->
            if (i == 0) e.copy(playbackPositionTicks = 0, played = true) else e
        }
        dataChanges.onChanged()
        holdSeason = true
        sleepAndWake()

        // The panel waits for the season: loaded alongside it, it would land first with a played
        // mark its card doesn't have yet, and the page would load the season a second time.
        assertEquals(listOf("season"), calls)
        releaseSeason()

        assertEquals(listOf("season", "episode"), calls)
        rule.onNodeWithText("Play")
    }

    /**
     * While true, the season's answers wait for [releaseSeason]: a server slow to list a season,
     * slower than it is to answer for the panel's episode.
     */
    private var holdSeason = false
    private val heldSeason = mutableListOf<() -> Unit>()

    private fun releaseSeason() {
        while (heldSeason.isNotEmpty()) {
            val answers = heldSeason.toList()
            heldSeason.clear()
            rule.runOnIdle { answers.forEach { it() } }
            rule.waitForIdle()
        }
    }

    /** A server that takes a moment, as a real one does: every call suspends. */
    private fun repository(): FakeRoles =
        FakeRoles(ItemCatalog::class, UserDataUpdates::class) { _, method, args ->
            when (method.kotlinName) {
                "getSeason" -> later(args, season, holdSeason).also { calls += "season" }
                "getSeasons" -> later(args, listOf(season), holdSeason)
                "getEpisodes" -> {
                    val offline = args!![args.size - 2] as Boolean
                    if (offline) emptyList<Episode>() else later(args, serverEpisodes, holdSeason)
                }
                // Only the first episode is ever in the panel: it is the one to resume.
                "getEpisode" ->
                    later(args, serverEpisodes.first(), false).also { calls += "episode" }
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    /**
     * Suspends the call (its last argument is the continuation) and answers it on a later turn of
     * the main thread, or, [held], when [releaseSeason] says so.
     */
    private fun later(args: Array<Any?>?, answer: Any?, held: Boolean): Any {
        @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as Continuation<Any?>
        val resume = { continuation.resume(answer) }
        if (held) heldSeason += resume else Handler(Looper.getMainLooper()).post(resume)
        return COROUTINE_SUSPENDED
    }

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

/** A TV app in the foreground, moved by hand. */
private class TestLifecycleOwner : LifecycleOwner {
    // Unsafe: the test moves it from the test thread.
    val registry: LifecycleRegistry =
        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle
        get() = registry
}
