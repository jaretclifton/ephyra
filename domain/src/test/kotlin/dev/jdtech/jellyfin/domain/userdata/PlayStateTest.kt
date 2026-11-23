package dev.jdtech.jellyfin.domain.userdata

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * #898: what the marks, a progress report and a stop write to the local play state. The repository
 * in `data` applies these; `PendingUserDataSyncTest` and `UserDataReportTest` there check the rows.
 */
class PlayStateTest {
    private val unplayed =
        PlayState(played = false, favorite = false, positionTicks = 500L, lastPlayedMillis = null)
    private val playedBefore = unplayed.copy(played = true, lastPlayedMillis = 1_000L)

    @Test
    fun `a played mark sets the date when unknown`() {
        // CE (#633): as the server does for a played mark without a date.
        assertEquals(
            unplayed.copy(played = true, lastPlayedMillis = 5_000L),
            unplayed.markedPlayed(at = 5_000L),
        )
    }

    @Test
    fun `a played mark keeps a known date and the position`() {
        val state = unplayed.copy(lastPlayedMillis = 1_000L)
        assertEquals(state.copy(played = true), state.markedPlayed(at = 5_000L))
    }

    @Test
    fun `an unplayed mark keeps a known date for the sync`() {
        // CE (#635): the sync sees the mark is not older than the play it undoes.
        assertEquals(playedBefore.copy(played = false), playedBefore.markedUnplayed(5_000L))
    }

    @Test
    fun `an unplayed mark without a date gets the time of the mark`() {
        // Or it would always count as older than the server's play.
        val state = unplayed.copy(played = true)
        assertEquals(
            state.copy(played = false, lastPlayedMillis = 5_000L),
            state.markedUnplayed(markedAt = 5_000L),
        )
    }

    @Test
    fun `after an unplayed mark that reached the server the date is cleared`() {
        val marked = playedBefore.markedUnplayed(5_000L)
        assertEquals(
            marked.copy(lastPlayedMillis = null),
            marked.afterUnplayedReport(reached = true),
        )
    }

    @Test
    fun `after an unplayed mark that did not reach the server nothing is written`() {
        assertNull(playedBefore.markedUnplayed(5_000L).afterUnplayedReport(reached = false))
    }

    @Test
    fun `a favourite changes nothing else`() {
        assertEquals(playedBefore.copy(favorite = true), playedBefore.withFavorite(true))
        assertEquals(playedBefore, playedBefore.withFavorite(true).withFavorite(false))
    }

    @Test
    fun `a progress report sets the position and the date, and leaves played alone`() {
        assertEquals(
            playedBefore.copy(positionTicks = 9_000L, lastPlayedMillis = 7_000L),
            playedBefore.progressed(positionTicks = 9_000L, at = 7_000L),
        )
    }

    @Test
    fun `a stop sets the position and the date`() {
        assertEquals(
            playedBefore.copy(positionTicks = 9_000L, lastPlayedMillis = 7_000L),
            playedBefore.stopped(PlayStop(positionTicks = 9_000L, played = null), at = 7_000L),
        )
    }

    @Test
    fun `a stop that plays the item to the end marks it played`() {
        assertEquals(
            unplayed.copy(played = true, positionTicks = 0L, lastPlayedMillis = 7_000L),
            unplayed.stopped(PlayStop(positionTicks = 0L, played = true), at = 7_000L),
        )
    }

    @Test
    fun `a stop without a played flag keeps the one there is`() {
        val stop = PlayStop(positionTicks = 0L, played = null)
        assertEquals(true, playedBefore.stopped(stop, at = 7_000L).played)
        assertEquals(false, unplayed.stopped(stop, at = 7_000L).played)
    }
}
