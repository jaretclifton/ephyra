package dev.jdtech.jellyfin.player.cast.presentation

import dev.jdtech.jellyfin.models.MediaSegmentAction
import dev.jdtech.jellyfin.models.MediaSegmentAction.ASK
import dev.jdtech.jellyfin.models.MediaSegmentAction.IGNORE
import dev.jdtech.jellyfin.models.MediaSegmentAction.SKIP
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.SegmentType.INTRO
import dev.jdtech.jellyfin.models.SegmentType.OUTRO
import dev.jdtech.jellyfin.models.SegmentType.RECAP
import dev.jdtech.jellyfin.player.local.domain.SegmentTracker
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CastSegmentSkipper]: the local player's segment rule on the positions a Cast
 * receiver reports (#851), including the receiver's late reports.
 */
class CastSegmentSkipperTest {
    private val duration = 720_000L
    private val recap = Segment(RECAP, startTicks = 0L, endTicks = 8_000L)
    private val intro = Segment(INTRO, startTicks = 10_000L, endTicks = 40_000L)
    private val outro = Segment(OUTRO, startTicks = 700_000L, endTicks = 719_000L)

    private var clockMs = 0L

    private fun skipper(
        actions: Map<SegmentType, MediaSegmentAction> =
            mapOf(INTRO to SKIP, OUTRO to SKIP, RECAP to IGNORE),
        onlyInPip: Boolean = false,
    ) =
        CastSegmentSkipper(
                SegmentTracker(
                    actions = actions,
                    buttonDurationMs = 5_000L,
                    nextEpisodeThresholdMs = 5_000L,
                    autoSkipOnlyInPip = onlyInPip,
                ),
                now = { clockMs },
            )
            .apply { setSegments(listOf(recap, intro, outro)) }

    /** Reports [positions] one second apart, as the receiver does, and returns every step. */
    private fun CastSegmentSkipper.play(vararg positions: Long): List<CastSegmentStep> =
        positions.map { position ->
            clockMs += 1_000L
            update(position, duration)
        }

    private fun List<CastSegmentStep>.seeks() = filterIsInstance<CastSegmentStep.SeekTo>()

    @Test
    fun `a segment set to skip is skipped once, to its end`() {
        val steps = skipper().play(8_000L, 9_000L, 10_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps.seeks())
        assertEquals(CastSegmentStep.SeekTo(intro, 40_000L), steps.last())
    }

    @Test
    fun `entering the segment mid-way skips it too`() {
        val steps = skipper().play(25_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps)
    }

    @Test
    fun `a segment set to ask shows the button and does not seek`() {
        val steps = skipper(actions = mapOf(INTRO to ASK)).play(10_000L, 11_000L, 39_000L)

        assertEquals(List(3) { CastSegmentStep.ShowButton(intro) }, steps)
    }

    @Test
    fun `an ignored segment shows nothing`() {
        val steps = skipper().play(0L, 1_000L, 7_000L)

        assertEquals(List(3) { CastSegmentStep.None }, steps)
    }

    @Test
    fun `with auto-skip only in picture-in-picture, Cast shows the button`() {
        val steps = skipper(onlyInPip = true).play(10_000L, 11_000L)

        assertEquals(List(2) { CastSegmentStep.ShowButton(intro) }, steps)
    }

    @Test
    fun `reports still inside the segment after the seek do not seek again`() {
        // The receiver has not done the seek yet: it goes on reporting the intro.
        val steps = skipper().play(10_000L, 11_000L, 12_000L, 13_000L)

        assertEquals(1, steps.seeks().size)
        assertEquals(List(3) { CastSegmentStep.None }, steps.drop(1))
    }

    @Test
    fun `a late report from before the seek does not seek again`() {
        // The controller shows the target at once; then a report from before the seek arrives.
        val steps = skipper().play(10_000L, 40_000L, 11_000L, 41_000L, 42_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps.seeks())
        assertEquals(CastSegmentStep.None, steps[2])
    }

    @Test
    fun `the user seeking back into a skipped segment skips it again, as the local player does`() {
        val skipper = skipper()
        skipper.play(10_000L, 40_000L, 41_000L)

        skipper.onUserSeek()
        val steps = skipper.play(20_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps)
    }

    @Test
    fun `a seek back into the segment from the receiver, once settled, skips it again`() {
        val skipper = skipper()
        skipper.play(10_000L, 40_000L, 41_000L)
        clockMs += CastSegmentSkipper.SETTLE_MS

        val steps = skipper.play(15_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps)
    }

    @Test
    fun `seeking back inside a skipped segment does not skip it again`() {
        // The seek did not land (the receiver stays in the intro); the user seeks back in it.
        val skipper = skipper()
        skipper.play(10_000L, 11_000L, 30_000L)

        skipper.onUserSeek()
        val steps = skipper.play(15_000L, 16_000L)

        assertEquals(List(2) { CastSegmentStep.None }, steps)
    }

    @Test
    fun `an outro that closes the item is skipped to its end, not to the next item`() {
        val steps = skipper().play(700_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(outro, 719_000L)), steps)
    }

    @Test
    fun `a new item forgets the skip`() {
        val skipper = skipper()
        skipper.play(10_000L)

        skipper.setSegments(listOf(intro))
        val steps = skipper.play(10_000L)

        assertEquals(listOf(CastSegmentStep.SeekTo(intro, 40_000L)), steps)
    }
}
