package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The item the server was told is playing, with the last known position and duration. Used to
 * report playback stopped for the item being left when the player's `onMediaItemTransition` fires,
 * which covers every item change (auto-advance, next/previous buttons, gestures, media-session and
 * remote controls, the outro "Next episode" button) on both backends.
 *
 * `null` before the first transition, after the player is released, and after the still-watching
 * prompt already reported the finished item (so the transition that follows does not report it
 * again).
 *
 * [startJob] is the item's playback start report, `null` until it is sent. Until the duration is
 * known, [positionMs] is the position playback was asked to start at (#461).
 */
data class PlayingSnapshot(
    val itemId: ItemId,
    val positionMs: Long,
    val durationMs: Long,
    val startJob: Job? = null,
    /** CE (#616): left from its closing outro, so the stop reports it as played to the end. */
    val finished: Boolean = false,
)

/**
 * CE (#765): sends the start, progress and stop reports of the local player, in the right order. It
 * owns what the server was told ([playing]), the start gate (#601), the order of the progress
 * reports and the pause the server last heard of (#674), and the scope the start and stop reports
 * run in. The player's ViewModel tells it what happened and reads the player for it: this class
 * never touches the player.
 *
 * [reportScope] must outlive the player: a stop report is sent after the player is released, from
 * the ViewModel's `onCleared`. Hilt gives the application's scope.
 *
 * Not thread-safe, like the code it came from: called from the player's listener and the main
 * thread only. The repository calls of the start and stop run on [ioDispatcher].
 */
class PlaybackReporter(
    private val repository: JellyfinRepository,
    private val playbackEvents: PlaybackEvents,
    private val reportScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * What the reporter reads of the player while a progress report waits, and when a start is
     * sent. Main thread only.
     */
    interface ReportedPlayer {
        /** The player's current media item's id, or `null` without a media item. */
        fun currentMediaId(): String?

        /** Whether the stream of [itemId] that plays now is the server's transcode (#599). */
        fun isTranscoded(itemId: ItemId): Boolean

        fun isPlaying(): Boolean

        fun currentPositionMs(): Long
    }

    /** The item the server was told is playing; see [PlayingSnapshot]. */
    var playing: PlayingSnapshot? = null
        private set

    /** CE (#601): holds an item's start report until the player has loaded it. */
    private val startGate = PlaybackStartGate<PlayerItem>()

    /**
     * The player's item for this stream. Every stream goes through here on its way into the player,
     * so this is where its play session becomes the one the reports name (#728).
     */
    fun onStreamQueued(item: PlayerItem) {
        repository.setPlaySession(item.itemId, item.playSessionId, item.isTranscoded)
    }

    /** The player's position and duration on [playing]'s item, both known. */
    fun updatePosition(positionMs: Long, durationMs: Long) {
        val current = playing ?: return
        playing = current.copy(positionMs = positionMs, durationMs = durationMs)
    }

    /** CE (#616): [playing] is left from its closing outro, so its stop reports it finished. */
    fun markFinished() {
        val current = playing ?: return
        playing = current.copy(finished = true)
    }

    /**
     * The item [playing] is left without a transition to report it (end of the item without
     * advancing, the still-watching prompt). Returns it, for [reportStopped], and clears it.
     */
    fun takePlaying(): PlayingSnapshot? {
        val current = playing
        playing = null
        return current
    }

    /**
     * The player is released: clear [playing] so a transition fired while releasing cannot report
     * the same item a second time, and drop the start the gate holds. Returns the snapshot to send
     * the stop for once the player is gone.
     */
    fun onPlayerReleased(): PlayingSnapshot? {
        val stopped = playing
        playing = null
        startGate.clear()
        return stopped
    }

    /**
     * The player moved to the item [newId] (`null` when it is not an item id). Every item change
     * goes through here, whatever caused it, so this is where the item being left is reported as
     * stopped. The first load (nothing playing yet) reports nothing, and a reload of the same item
     * (quality switch) keeps its snapshot and reports nothing.
     *
     * @return the stop report of the item left, for the next item's start to wait for.
     */
    fun onItemTransition(newId: ItemId?): Job? {
        val previous = playing
        val stopJob =
            if (previous != null && previous.itemId != newId) reportStopped(previous) else null
        playing =
            when {
                previous != null && previous.itemId == newId -> previous
                newId != null -> PlayingSnapshot(newId, 0L, C.TIME_UNSET)
                else -> null
            }
        return stopJob
    }

    /**
     * The player's transition to [item] is handled, after the previous item's stop. CE (#601): the
     * start waits for the item to load ([PlaybackStartGate]); [ready] is whether it already has.
     * Call it on the main thread: a start sent now reads [player]'s position.
     */
    suspend fun onTransitionHandled(item: PlayerItem, ready: Boolean, player: ReportedPlayer) {
        startGate.onTransition(item, ready = ready, current = playing?.itemId == item.itemId)?.let {
            sendPlaybackStart(it, player)
        }
    }

    /** CE (#601): the player is ready. Returns the item whose start is due now, or `null`. */
    fun onItemReady(): PlayerItem? = startGate.onReady()

    /**
     * Tell the server that [item] started playing, and record the report on [playing] so that
     * leaving the item sends the stop this start is owed (#461). Until the duration is known the
     * snapshot holds the position playback was asked to start at, so that stop keeps a saved resume
     * position.
     *
     * Sent once the player has loaded the item ([startGate], #601): an item that never opens sends
     * no start, no progress and no stop.
     *
     * The report carries [player]'s position when it is sent (#868): the resume position after a
     * resume, where playback was after a quality switch. Call it on the main thread, which reads
     * [player], as [reportProgress] does.
     *
     * The report runs in [reportScope] so that closing the player does not cut it off halfway;
     * [reportStopped] waits for it (with a time limit) to keep the stop behind it.
     */
    suspend fun sendPlaybackStart(item: PlayerItem, player: ReportedPlayer) {
        // The player moved on while the previous item's stop was being sent (Previous or Next
        // pressed while this item was loading). A start now would arrive after the next item's,
        // and nothing would stop it.
        if (playing?.itemId != item.itemId) {
            Timber.d("No playback start for ${item.itemId.asString()}: left before it was sent")
            return
        }
        val positionTicks = player.currentPositionMs().times(10000)
        val start =
            reportScope.launch(ioDispatcher) {
                try {
                    repository.postPlaybackStart(item.itemId, positionTicks, item.isTranscoded)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        playing = playing?.let {
            if (it.durationMs == C.TIME_UNSET) {
                it.copy(positionMs = item.playbackPosition, startJob = start)
            } else {
                it.copy(startJob = start)
            }
        }
        start.join()
    }

    /**
     * Tell the server (and the local database) that [snapshot]'s item stopped playing. The only
     * caller of [JellyfinRepository.postPlaybackStop]. Before the duration is known the stop is
     * only sent if the start was, and it leaves the local database alone ([playbackStopReport]).
     *
     * Runs in [reportScope] because it must outlive the ViewModel's scope: the player is released
     * from its `onCleared`, and the player can be closed right after an item change. It waits up to
     * [START_REPORT_WAIT_MS] for the item's start report, so the stop reaches the server after it.
     *
     * @return the reporting job, so a caller can wait for it, or `null` if nothing was sent.
     */
    fun reportStopped(snapshot: PlayingSnapshot): Job? {
        val (itemId, position, duration, startJob, finished) = snapshot
        val report =
            playbackStopReport(
                position,
                duration,
                startSent = startJob != null,
                finished = finished,
            )
        if (report == null) {
            Timber.d("No playback stop for ${itemId.asString()}: neither loaded nor started")
            // CE (#846): every way out of an item ends here (item change, release, the
            // still-watching prompt), so this is where the stop that will not come is called off.
            playbackEvents.onLeftWithoutStop()
            return null
        }
        return reportScope.launch(ioDispatcher) {
            try {
                if (startJob != null && !startJob.isCompleted) {
                    withTimeoutOrNull(START_REPORT_WAIT_MS) { startJob.join() }
                        ?: Timber.w(
                            "Start report for ${itemId.asString()} still running, sending stop anyway"
                        )
                }
                if (report.playedPercentage == null) {
                    Timber.d(
                        "Sending playback stop for ${itemId.asString()} at its start, $position ms"
                    )
                } else {
                    Timber.d(
                        "Sending playback stop for $itemId at $position / $duration ms" +
                            if (finished) ", as finished" else ""
                    )
                }
                repository.postPlaybackStop(itemId, report.positionTicks, report.playedPercentage)
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                playbackEvents.onStopped(itemId)
            }
        }
    }

    /** CE (#674): one progress report at a time; see [newProgressReport]. */
    private val progressReportLock = Mutex()

    /** CE (#674): the number of the latest [newProgressReport] call. Main thread only. */
    private var progressReportSeq = 0L

    /**
     * CE (#674): what the last progress report told the server; guarded by [progressReportLock].
     */
    private val reportedPause = ReportedPause()

    /**
     * CE (#674): a new progress report is asked for. Returns its number for [reportProgress]; a
     * report whose number is no longer the latest by the time it holds the lock is not sent.
     */
    fun newProgressReport(): Long = ++progressReportSeq

    /** CE (#674): the number of the latest progress report, for a report that replaces none. */
    val latestProgressReport: Long
        get() = progressReportSeq

    /**
     * Send progress report [seq], reading [player] when it is sent. With [onlyIfReportedPaused]
     * (playback started), only if the server was last told paused: a resume that had to buffer
     * first was reported as paused, and after any other report this sends nothing, so a stall adds
     * no reports. Call it on the main thread, from the scope of the player's owner.
     */
    suspend fun reportProgress(seq: Long, onlyIfReportedPaused: Boolean, player: ReportedPlayer) {
        // The current item's stop was already reported (end of item without autoplay, or the
        // still-watching prompt). A progress report now would reopen its session on the
        // server and overwrite the position the stop stored, e.g. from onPause while the
        // player closes.
        // CE (#601): nor before the start report, which waits for the item to load. The
        // server marks an item without a runtime played at its first position report, so an
        // item that failed to open was marked played.
        if (playing?.startJob == null) return
        // CE (#674): a pause right after the start must not reach the server before the start,
        // which would then mark the item playing again. The stop waits the same way. join()
        // returns for a failed or cancelled start too.
        val startJob = playing?.startJob
        if (startJob != null && !startJob.isCompleted) {
            withTimeoutOrNull(START_REPORT_WAIT_MS) { startJob.join() }
            if (playing?.startJob == null) return
        }
        val mediaId = player.currentMediaId() ?: return
        if (mediaId.isNotEmpty()) {
            val itemId = mediaId.toItemId()
            // CE (#599): the item as it plays now; a quality switch replaces it in items.
            val transcoded = player.isTranscoded(itemId)
            // CE (#674): a pause and a resume are reported as they happen, so two reports can
            // be in flight at once and reach the server in the wrong order. Send one at a
            // time, skip any that a newer call replaces, and read the state when it is sent.
            progressReportLock.withLock {
                if (seq != progressReportSeq) return@withLock
                // While this report waited for the lock, the item's stop may have been sent
                // (the snapshot is cleared) or another item may have started (a new start
                // report). Posting now would reopen the stopped item's session, or send the
                // new item's position for the old one.
                if (playing?.startJob !== startJob || player.currentMediaId() != mediaId) {
                    return@withLock
                }
                if (onlyIfReportedPaused && !reportedPause.needsReportWhenPlaying()) {
                    return@withLock
                }
                val isPaused = !player.isPlaying()
                try {
                    repository.postPlaybackProgress(
                        itemId,
                        player.currentPositionMs().times(10000),
                        isPaused,
                        transcoded,
                    )
                    reportedPause.onReported(isPaused)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
    }
}

/**
 * How long a stop report waits for its item's start report before it is sent anyway (#461), so a
 * start that hangs (no network) cannot hold back the stop.
 */
internal const val START_REPORT_WAIT_MS = 5_000L
