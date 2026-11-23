package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaybackReporter] (#765): the start waits for the load, progress reports keep
 * their order and only the latest goes out, a pause and a resume go out at once, and the stop waits
 * a bounded time for the start and outlives the player's owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    private val itemId = "00000000-0000-0000-0000-000000000001".toItemId()
    private val nextId = "00000000-0000-0000-0000-000000000002".toItemId()

    private fun item(
        id: ItemId = itemId,
        session: String? = "session-1",
        transcoded: Boolean = false,
        position: Long = 0L,
    ) =
        PlayerItem(
            name = "item",
            itemId = id,
            mediaSourceId = "source".toMediaSourceId(),
            playbackPosition = position,
            isTranscoded = transcoded,
            playSessionId = session,
        )

    /** Records the reports with the virtual time they reached it, and can delay or fail them. */
    private class FakeRepository(private val scope: TestScope) {
        val calls = mutableListOf<String>()

        /** The positions the start reports carried, in ticks (#868). */
        val startPositions = mutableListOf<Long?>()
        var startDelayMs = 0L
        var progressDelayMs = 0L
        var stopDelayMs = 0L
        var failStart = false

        private fun record(call: String) {
            calls += "${scope.testScheduler.currentTime}: $call"
        }

        private val unused =
            fakeProxy(
                MediaRepository::class.java.classLoader,
                arrayOf(MediaRepository::class.java),
            ) { _, method, _ ->
                error("Unexpected call to ${method.kotlinName}")
            }
                as MediaRepository

        val repository: MediaRepository =
            object : MediaRepository by unused {
                override fun setPlaySession(
                    itemId: ItemId,
                    playSessionId: String?,
                    transcodes: Boolean,
                    mediaSourceId: MediaSourceId?,
                ) {
                    record("session $itemId $playSessionId $transcodes")
                }

                override suspend fun postPlaybackStart(
                    itemId: ItemId,
                    positionTicks: Long?,
                    transcoded: Boolean,
                ) {
                    delay(startDelayMs)
                    if (failStart) throw IOException("no server")
                    record("start $itemId $transcoded")
                    startPositions += positionTicks
                }

                override suspend fun postPlaybackProgress(
                    itemId: ItemId,
                    positionTicks: Long,
                    isPaused: Boolean,
                    transcoded: Boolean,
                ) {
                    delay(progressDelayMs)
                    record("progress $itemId $positionTicks paused=$isPaused $transcoded")
                }

                override suspend fun postPlaybackStop(
                    itemId: ItemId,
                    positionTicks: Long,
                    playedPercentage: Int?,
                ) {
                    delay(stopDelayMs)
                    record("stop $itemId $positionTicks $playedPercentage")
                }
            }
    }

    /** A player the reporter reads while a progress report waits. */
    private class Player(var mediaId: String?) : PlaybackReporter.ReportedPlayer {
        var playing = true
        var positionMs = 0L
        var transcoded = false

        override fun currentMediaId() = mediaId

        override fun isTranscoded(itemId: ItemId) = transcoded

        override fun isPlaying() = playing

        override fun currentPositionMs() = positionMs
    }

    private class Setup(val scope: TestScope) {
        val fake = FakeRepository(scope)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)

        /** The scope of the player's owner (the ViewModel), which closing the player cancels. */
        val owner = CoroutineScope(Job() + dispatcher)

        /** The application's scope, as Hilt gives it: never cancelled. */
        val reportScope = CoroutineScope(SupervisorJob() + dispatcher)
        val dataChanges = DataChangeEvents()
        val events = PlaybackEvents(dataChanges)
        val reporter = PlaybackReporter(fake.repository, events, reportScope, dispatcher)

        /** The calls the repository got, without the play sessions. */
        val reports
            get() = fake.calls.filterNot { it.contains(": session ") }

        /** The player the start reports read (#868). */
        val player = Player(null)

        /**
         * Whether the player has loaded the item when a transition is handled, read then (after the
         * previous item's stop), as `PlayerViewModel` reads it.
         */
        var playerReady = false

        /**
         * The player moved to [item] and handled the transition; [ready] if already loaded, else
         * [playerReady] when the transition is handled.
         */
        fun transition(item: PlayerItem, ready: Boolean? = null): Job? {
            reporter.onStreamQueued(item)
            val transition = reporter.onItemTransition(item.itemId)
            owner.launch {
                transition.stopJob?.join()
                reporter.onTransitionHandled(item, ready ?: playerReady, player, transition)
            }
            scope.testScheduler.runCurrent()
            return transition.stopJob
        }

        /**
         * CE (#870): the playlist was replaced with [item], a new stream of the playing item, and
         * the player reported no transition for it (mpv), as `PlayerViewModel.swapInQueue` does.
         */
        fun streamReplaced(item: PlayerItem) {
            reporter.onStreamQueued(item)
            reporter.onStreamReplaced(item)
            scope.testScheduler.runCurrent()
        }

        /** The player is ready; the start it owes is on its way (it may still be waiting). */
        fun loaded() {
            reporter.onItemReady()?.let { owner.launch { reporter.sendPlaybackStart(it, player) } }
            scope.testScheduler.runCurrent()
        }

        fun progress(player: Player) {
            val seq = reporter.newProgressReport()
            owner.launch { reporter.reportProgress(seq, onlyIfReportedPaused = false, player) }
        }

        fun playbackStarted(player: Player) {
            val seq = reporter.latestProgressReport
            owner.launch { reporter.reportProgress(seq, onlyIfReportedPaused = true, player) }
        }
    }

    private fun test(body: suspend TestScope.(Setup) -> Unit) = runTest { body(Setup(this)) }

    @Test
    fun `the start waits for the load and is sent once`() = test { s ->
        s.transition(item())
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)

        s.loaded()
        advanceUntilIdle()
        s.loaded()
        advanceUntilIdle()
        assertEquals(listOf("0: start $itemId false"), s.reports)
    }

    @Test
    fun `the start carries the player's position when it is sent`() = test { s ->
        // #868: a resume at 275 s. The player is there once it has loaded the item.
        s.transition(item(position = 275_000L))
        s.player.positionMs = 275_000L
        s.loaded()
        advanceUntilIdle()
        assertEquals(listOf("0: start $itemId false"), s.reports)
        assertEquals(listOf<Long?>(2_750_000_000L), s.fake.startPositions)
    }

    @Test
    fun `a fresh start carries position 0`() = test { s ->
        s.transition(item())
        s.loaded()
        advanceUntilIdle()
        assertEquals(listOf<Long?>(0L), s.fake.startPositions)
    }

    @Test
    fun `the start of a quality switch carries where playback is, not where it began`() =
        test { s ->
            s.transition(item(position = 275_000L))
            s.player.positionMs = 275_000L
            s.loaded()
            advanceUntilIdle()
            s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)

            // The new stream is loaded at the position playback was at.
            s.player.positionMs = 300_000L
            s.transition(item(session = "session-2", transcoded = true, position = 275_000L))
            advanceUntilIdle()
            s.loaded()
            advanceUntilIdle()

            assertEquals(listOf<Long?>(2_750_000_000L, 3_000_000_000L), s.fake.startPositions)
        }

    @Test
    fun `an item already loaded at its transition sends its start with the position`() = test { s ->
        // An auto-advance: the next item is ready when the transition is handled.
        s.player.positionMs = 12_000L
        s.transition(item(position = 12_000L), ready = true)
        advanceUntilIdle()
        assertEquals(listOf("0: start $itemId false"), s.reports)
        assertEquals(listOf<Long?>(120_000_000L), s.fake.startPositions)
    }

    @Test
    fun `an item that never loads sends nothing`() = test { s ->
        s.transition(item())
        s.progress(Player(itemId.asString()))
        advanceUntilIdle()
        s.reporter.onPlayerReleased()?.let { s.reporter.reportStopped(it) }
        s.loaded()
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)
    }

    @Test
    fun `a quality switch names the new session and sends the start of its transition`() =
        test { s ->
            // Corrected from the issue's list: a transition to the same item owes a start, as
            // PlaybackStartGate says, so the switch sends a second start and no stop.
            s.transition(item())
            s.loaded()
            advanceUntilIdle()
            s.reporter.updatePosition(positionMs = 60_000L, durationMs = 600_000L)

            s.transition(item(session = "session-2", transcoded = true))
            advanceUntilIdle()
            s.loaded()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "0: session $itemId session-1 false",
                    "0: start $itemId false",
                    "0: session $itemId session-2 true",
                    "0: start $itemId true",
                ),
                s.fake.calls,
            )
            // The snapshot keeps the position: the stop is for the item, not the stream.
            assertEquals(60_000L, s.reporter.playing?.positionMs)
        }

    @Test
    fun `mpv's quality switch, without a transition, sends the start of the new stream (#870)`() =
        test { s ->
            // mpv reports no transition when the playlist is replaced with the same item: the
            // ViewModel hands the new stream to the reporter itself, after setMediaItems().
            s.transition(item())
            s.loaded()
            advanceUntilIdle()
            s.reporter.updatePosition(positionMs = 60_000L, durationMs = 600_000L)

            // The new stream is loaded at the position playback was at.
            s.player.positionMs = 60_000L
            s.streamReplaced(item(session = "session-2", transcoded = true))
            advanceUntilIdle()
            // Not before the new stream has loaded, as after ExoPlayer's transition.
            assertEquals(listOf("0: start $itemId false"), s.reports)
            s.loaded()
            advanceUntilIdle()
            s.loaded()
            advanceUntilIdle()

            // The same reports as ExoPlayer's switch above: one start for the new stream, no stop.
            assertEquals(
                listOf(
                    "0: session $itemId session-1 false",
                    "0: start $itemId false",
                    "0: session $itemId session-2 true",
                    "0: start $itemId true",
                ),
                s.fake.calls,
            )
            // CE (#868): the new stream's start carries where playback is, as ExoPlayer's does.
            assertEquals(listOf<Long?>(0L, 600_000_000L), s.fake.startPositions)
            assertEquals(60_000L, s.reporter.playing?.positionMs)
        }

    @Test
    fun `a quality switch while the previous item's stop is sent owes one start, after it (#890)`() =
        test { s ->
            // ExoPlayer: Next, then a quality switch of the new item while the server still
            // takes the previous item's stop. The switch is a second transition to the new item,
            // whose new stream loads before that stop is through.
            s.transition(item())
            s.loaded()
            advanceUntilIdle()
            s.reporter.updatePosition(positionMs = 60_000L, durationMs = 600_000L)
            s.fake.stopDelayMs = 10_000L

            s.transition(item(id = nextId, session = "session-2", transcoded = true))
            s.transition(item(id = nextId, session = "session-3", transcoded = false))
            s.loaded()
            s.playerReady = true
            advanceUntilIdle()

            // One start, for the stream that plays, and not before the previous item's stop.
            assertEquals(
                listOf(
                    "0: start $itemId false",
                    "10000: stop $itemId 600000000 10",
                    "10000: start $nextId false",
                ),
                s.reports,
            )
        }

    @Test
    fun `mpv's quality switch while the previous item's stop is sent owes one start (#890)`() =
        test { s ->
            // mpv reports no transition for the switch: the new stream is handed over while the
            // transition to the new item still waits for the previous item's stop.
            s.transition(item())
            s.loaded()
            advanceUntilIdle()
            s.reporter.updatePosition(positionMs = 60_000L, durationMs = 600_000L)
            s.fake.stopDelayMs = 10_000L

            s.transition(item(id = nextId, session = "session-2", transcoded = true))
            s.streamReplaced(item(id = nextId, session = "session-3", transcoded = false))
            s.loaded()
            s.playerReady = true
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "0: start $itemId false",
                    "10000: stop $itemId 600000000 10",
                    "10000: start $nextId false",
                ),
                s.reports,
            )
        }

    @Test
    fun `a replaced stream that never loads sends no start (#870)`() = test { s ->
        s.transition(item())
        s.loaded()
        advanceUntilIdle()

        s.streamReplaced(item(session = "session-2", transcoded = true))
        advanceUntilIdle()
        s.reporter.onPlayerReleased()
        s.loaded()
        advanceUntilIdle()

        assertEquals(listOf("0: start $itemId false"), s.reports)
    }

    @Test
    fun `a stream replaced for an item the player has left owes nothing (#870)`() = test { s ->
        s.transition(item())
        s.loaded()
        advanceUntilIdle()
        s.transition(item(id = nextId))
        advanceUntilIdle()

        s.streamReplaced(item(session = "session-2", transcoded = true))
        s.loaded()
        advanceUntilIdle()

        // The start held for the item the player is on stays.
        assertEquals(
            listOf("0: start $itemId false", "0: stop $itemId 0 null", "0: start $nextId false"),
            s.reports,
        )
    }

    @Test
    fun `no progress before the start is sent`() = test { s ->
        s.transition(item())
        advanceUntilIdle()
        s.progress(Player(itemId.asString()))
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)
    }

    @Test
    fun `of two reports waiting for a slow start only the later one is sent`() = test { s ->
        s.fake.startDelayMs = 1_000L
        val player = Player(itemId.asString())
        s.transition(item())
        s.loaded()
        // The start is on its way: both reports wait for it.
        s.reporter.updatePosition(0L, 600_000L)
        player.positionMs = 1_000L
        s.progress(player)
        // The first one is waiting for the start, as the latest report, when the second is asked.
        testScheduler.runCurrent()
        player.positionMs = 2_000L
        s.progress(player)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "1000: start $itemId false",
                "1000: progress $itemId 20000000 paused=false false",
            ),
            s.reports,
        )
    }

    @Test
    fun `a report asked for while one is sent waits for it and replaces the ones between`() =
        test { s ->
            val player = Player(itemId.asString())
            s.transition(item())
            s.loaded()
            advanceUntilIdle()
            s.fake.progressDelayMs = 500L
            player.positionMs = 1_000L
            s.progress(player)
            testScheduler.advanceTimeBy(100L)
            testScheduler.runCurrent()
            player.positionMs = 2_000L
            s.progress(player)
            player.positionMs = 3_000L
            s.progress(player)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "0: start $itemId false",
                    "500: progress $itemId 10000000 paused=false false",
                    "1000: progress $itemId 30000000 paused=false false",
                ),
                s.reports,
            )
        }

    @Test
    fun `a pause is reported at once and a resume after it too`() = test { s ->
        val player = Player(itemId.asString())
        s.transition(item())
        s.loaded()
        advanceUntilIdle()

        player.playing = false
        s.progress(player)
        advanceUntilIdle()
        // Playback started again (a resume that buffered first was reported as paused).
        player.playing = true
        s.playbackStarted(player)
        advanceUntilIdle()
        // Already told playing: a stall and its recovery send nothing more.
        s.playbackStarted(player)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "0: start $itemId false",
                "0: progress $itemId 0 paused=true false",
                "0: progress $itemId 0 paused=false false",
            ),
            s.reports,
        )
    }

    @Test
    fun `a report for an item the player has left is not sent`() = test { s ->
        val player = Player(itemId.asString())
        s.transition(item())
        s.loaded()
        advanceUntilIdle()
        s.fake.progressDelayMs = 500L
        s.progress(player)
        testScheduler.advanceTimeBy(100L)
        testScheduler.runCurrent()
        // The next report waits for the lock while the player moves on.
        s.progress(player)
        testScheduler.runCurrent()
        player.mediaId = nextId.asString()
        advanceUntilIdle()
        assertEquals(
            listOf("0: start $itemId false", "500: progress $itemId 0 paused=false false"),
            s.reports,
        )
    }

    @Test
    fun `the stop waits for a slow start`() = test { s ->
        s.fake.startDelayMs = 2_000L
        s.transition(item())
        s.loaded()
        testScheduler.runCurrent()
        s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)
        s.transition(item(id = nextId))
        advanceUntilIdle()
        assertEquals(
            listOf("2000: start $itemId false", "2000: stop $itemId 3000000000 50"),
            s.reports,
        )
    }

    @Test
    fun `the stop goes out after the bound when the start hangs`() = test { s ->
        s.fake.startDelayMs = 60_000L
        s.transition(item())
        s.loaded()
        testScheduler.runCurrent()
        s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)
        s.reporter.onPlayerReleased()?.let { s.reporter.reportStopped(it) }
        advanceUntilIdle()
        assertEquals(
            listOf("5000: stop $itemId 3000000000 50", "60000: start $itemId false"),
            s.reports,
        )
    }

    @Test
    fun `a failed start still lets the stop out`() = test { s ->
        s.fake.failStart = true
        s.transition(item())
        s.loaded()
        advanceUntilIdle()
        s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)
        s.reporter.onPlayerReleased()?.let { s.reporter.reportStopped(it) }
        advanceUntilIdle()
        assertEquals(listOf("0: stop $itemId 3000000000 50"), s.reports)
    }

    @Test
    fun `the start and the stop are sent after the owner is closed`() = test { s ->
        s.fake.startDelayMs = 1_000L
        s.transition(item())
        s.loaded()
        testScheduler.runCurrent()
        s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)
        // The player closes and its ViewModel is cleared.
        val stopped = s.reporter.onPlayerReleased()
        s.owner.cancel()
        val stop = stopped?.let { s.reporter.reportStopped(it) }
        advanceUntilIdle()
        assertTrue(stop != null && stop.isCompleted)
        assertEquals(
            listOf("1000: start $itemId false", "1000: stop $itemId 3000000000 50"),
            s.reports,
        )
    }

    @Test
    fun `an item left before its duration was known stops at its start position`() = test { s ->
        s.transition(item(position = 120_000L))
        s.loaded()
        advanceUntilIdle()
        assertEquals(C.TIME_UNSET, s.reporter.playing?.durationMs)
        s.transition(item(id = nextId))
        advanceUntilIdle()
        assertEquals(listOf("0: start $itemId false", "0: stop $itemId 1200000000 null"), s.reports)
    }

    @Test
    fun `an item left before it loaded sends no stop`() = test { s ->
        s.transition(item())
        advanceUntilIdle()
        assertNull(s.transition(item(id = nextId)))
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)
    }

    @Test
    fun `an item finished from its outro stops at its end`() = test { s ->
        s.transition(item())
        s.loaded()
        advanceUntilIdle()
        s.reporter.updatePosition(positionMs = 500_000L, durationMs = 600_000L)
        s.reporter.markFinished()
        s.reporter.takePlaying()?.let { s.reporter.reportStopped(it) }
        // Taken: the transition that follows reports nothing more.
        s.transition(item(id = nextId))
        advanceUntilIdle()
        assertEquals(listOf("0: start $itemId false", "0: stop $itemId 6000000000 100"), s.reports)
    }

    /** What the player's ViewModel does on an item change: the reporter first, then the events. */
    private fun Setup.openItem(item: PlayerItem) {
        transition(item)
        events.onPlaying(item.itemId)
    }

    /** What the player's ViewModel does when it is closed (releasePlayer). */
    private fun Setup.close(): Job? {
        val stopped = reporter.onPlayerReleased()
        events.onPlaying(null)
        return stopped?.let { reporter.reportStopped(it) }
    }

    @Test
    fun `closing the player before the item loaded leaves no stop pending (#846)`() = test { s ->
        s.openItem(item())
        advanceUntilIdle()
        assertTrue(s.dataChanges.playbackPending)
        assertNull(s.close())
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)
        assertFalse(s.dataChanges.playbackPending)
    }

    @Test
    fun `a screen reloads again after the player closed before loading (#846)`() = test { s ->
        val policy = ReloadPolicy(s.dataChanges)
        policy.complete(policy.begin())
        s.openItem(item())
        s.close()
        advanceUntilIdle()
        // An episode marked played on another screen afterwards.
        s.dataChanges.onChanged()
        assertTrue(policy.shouldReload(stopEventFollows = true))
    }

    @Test
    fun `an item left before it loaded keeps the next item's stop pending (#846)`() = test { s ->
        s.openItem(item())
        s.openItem(item(id = nextId))
        advanceUntilIdle()
        assertTrue(s.dataChanges.playbackPending)
    }

    @Test
    fun `a stop that is sent stays pending until it is reported (#846)`() = test { s ->
        s.openItem(item())
        s.loaded()
        advanceUntilIdle()
        s.reporter.updatePosition(positionMs = 300_000L, durationMs = 600_000L)
        val stop = s.close()
        assertTrue(stop != null && !stop.isCompleted)
        assertTrue(s.dataChanges.playbackPending)
        advanceUntilIdle()
        assertFalse(s.dataChanges.playbackPending)
    }
}
