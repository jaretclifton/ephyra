package dev.jdtech.jellyfin.player.cast.presentation

import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.player.local.domain.SegmentDecision
import dev.jdtech.jellyfin.player.local.domain.SegmentTracker
import dev.jdtech.jellyfin.player.local.domain.SkipTarget

/**
 * CE (#851): the local player's segment rule ([SegmentTracker]) applied to the positions a Cast
 * receiver reports. The rule is the same: a segment set to skip is skipped once per entry, and
 * again after the user leaves it and comes back; one set to ask shows the button; an ignored one
 * shows nothing. Seeking back inside a skipped segment does not skip it again.
 *
 * What Cast adds is a guard for late reports. After a skip, the controller shows the target at
 * once, but the receiver can still report a position from before the seek. To the tracker that is
 * playback entering the segment again. So for [SETTLE_MS] after a skip, a report inside that
 * segment is dropped before the tracker sees it, unless the user seeks ([onUserSeek]).
 *
 * Differences from the local player, on purpose:
 * - The button shows for the whole segment of an "ask" type: the local player shows it after its
 *   time only while the controls are visible, and the Cast controls are always on screen.
 * - No picture-in-picture: with auto-skip set to "only in picture-in-picture", Cast shows the
 *   button.
 * - A skip always seeks to the segment's end, also for an outro that closes the item: the
 *   receiver's queue then moves on by itself, and reports the item to the server as it ends.
 *
 * Pure: no Android, no Cast SDK. [now] is a monotonic clock in milliseconds.
 */
class CastSegmentSkipper(private val tracker: SegmentTracker, private val now: () -> Long) {
    /** The segments skipped in the last [SETTLE_MS], with when. */
    private val recentSkips = mutableMapOf<Segment, Long>()

    /** The current item's segments. Call on every item change; it forgets the previous item. */
    fun setSegments(segments: List<Segment>) {
        tracker.setSegments(segments)
        recentSkips.clear()
    }

    /** The user moved the position: a report inside a skipped segment is then not a late one. */
    fun onUserSeek() {
        recentSkips.clear()
    }

    /** What to do at [positionMs], the receiver's position. */
    fun update(positionMs: Long, durationMs: Long): CastSegmentStep {
        val nowMs = now()
        recentSkips.values.removeAll { nowMs - it >= SETTLE_MS }
        // A report from before a seek landed. It does not reach the tracker, so it cannot count
        // as an entry, and a real one after it is still skipped. Each recent skip keeps its own
        // guard, so skipping the next segment straight after does not drop this one's.
        if (recentSkips.keys.any { positionMs >= it.startTicks && positionMs < it.endTicks }) {
            return CastSegmentStep.None
        }
        val decision =
            tracker.update(
                positionMs = positionMs,
                durationMs = durationMs,
                hasNextItem = false,
                inPictureInPicture = false,
            )
        return when (decision) {
            SegmentDecision.None -> CastSegmentStep.None
            is SegmentDecision.Ask -> CastSegmentStep.ShowButton(decision.segment)
            is SegmentDecision.AutoSkip -> {
                val segment = decision.segment
                recentSkips[segment] = nowMs
                val target = decision.target
                CastSegmentStep.SeekTo(
                    segment,
                    if (target is SkipTarget.SeekTo) target.positionMs else segment.endTicks,
                )
            }
        }
    }

    companion object {
        /** How long after a skip a report inside the skipped segment counts as a late one. */
        const val SETTLE_MS = 5_000L
    }
}

/** What Cast should do about the segment at the receiver's position. */
sealed interface CastSegmentStep {
    /** Not in a segment, or in one that is ignored or was just skipped. */
    data object None : CastSegmentStep

    /** Show the skip button for [segment]. */
    data class ShowButton(val segment: Segment) : CastSegmentStep

    /** Seek the receiver to [positionMs], past [segment]. */
    data class SeekTo(val segment: Segment, val positionMs: Long) : CastSegmentStep
}
