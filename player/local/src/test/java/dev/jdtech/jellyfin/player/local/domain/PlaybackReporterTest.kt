package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
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
    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val nextId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private fun item(
        id: UUID = itemId,
        session: String? = "session-1",
        transcoded: Boolean = false,
        position: Long = 0L,
    ) =
        PlayerItem(
            name = "item",
            itemId = id,
            mediaSourceId = "source",
            playbackPosition = position,
            isTranscoded = transcoded,
            playSessionId = session,
        )

    /** Records the reports with the virtual time they reached it, and can delay or fail them. */
    private class FakeRepository(private val scope: TestScope) {
        val calls = mutableListOf<String>()
        var startDelayMs = 0L
        var progressDelayMs = 0L
        var failStart = false

        private fun record(call: String) {
            calls += "${scope.testScheduler.currentTime}: $call"
        }

        private val unused =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { _, method, _ ->
                error("Unexpected call to ${method.name}")
            } as JellyfinRepository

        val repository: JellyfinRepository =
            object : JellyfinRepository by unused {
                override fun setPlaySession(
                    itemId: UUID,
                    playSessionId: String?,
                    transcodes: Boolean,
                ) {
                    record("session $itemId $playSessionId $transcodes")
                }

                override suspend fun postPlaybackStart(itemId: UUID, transcoded: Boolean) {
                    delay(startDelayMs)
                    if (failStart) throw IOException("no server")
                    record("start $itemId $transcoded")
                }

                override suspend fun postPlaybackProgress(
                    itemId: UUID,
                    positionTicks: Long,
                    isPaused: Boolean,
                    transcoded: Boolean,
                ) {
                    delay(progressDelayMs)
                    record("progress $itemId $positionTicks paused=$isPaused $transcoded")
                }

                override suspend fun postPlaybackStop(
                    itemId: UUID,
                    positionTicks: Long,
                    playedPercentage: Int?,
                ) {
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

        override fun isTranscoded(itemId: UUID) = transcoded

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
        val reporter = PlaybackReporter(fake.repository, PlaybackEvents(), reportScope, dispatcher)

        /** The calls the repository got, without the play sessions. */
        val reports
            get() = fake.calls.filterNot { it.contains(": session ") }

        /** The player moved to [item] and handled the transition; [ready] if already loaded. */
        fun transition(item: PlayerItem, ready: Boolean = false): Job? {
            reporter.onStreamQueued(item)
            val stop = reporter.onItemTransition(item.itemId)
            owner.launch {
                stop?.join()
                reporter.onTransitionHandled(item, ready)
            }
            scope.testScheduler.runCurrent()
            return stop
        }

        /** The player is ready; the start it owes is on its way (it may still be waiting). */
        fun loaded() {
            reporter.onItemReady()?.let { owner.launch { reporter.sendPlaybackStart(it) } }
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
    fun `an item that never loads sends nothing`() = test { s ->
        s.transition(item())
        s.progress(Player(itemId.toString()))
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
    fun `no progress before the start is sent`() = test { s ->
        s.transition(item())
        advanceUntilIdle()
        s.progress(Player(itemId.toString()))
        advanceUntilIdle()
        assertEquals(emptyList(), s.reports)
    }

    @Test
    fun `of two reports waiting for a slow start only the later one is sent`() = test { s ->
        s.fake.startDelayMs = 1_000L
        val player = Player(itemId.toString())
        s.transition(item())
        s.loaded()
        // The start is on its way: both reports wait for it.
        s.reporter.updatePosition(0L, 600_000L)
        player.positionMs = 1_000L
        s.progress(player)
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
            val player = Player(itemId.toString())
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
        val player = Player(itemId.toString())
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
        val player = Player(itemId.toString())
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
        player.mediaId = nextId.toString()
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
}
