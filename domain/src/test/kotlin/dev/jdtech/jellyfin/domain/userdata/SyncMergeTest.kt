package dev.jdtech.jellyfin.domain.userdata

import dev.jdtech.jellyfin.models.ItemId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The sync's rules (#898, moved from `core`'s `LastPlayedDateSyncTest` and `UserDataRefreshTest`):
 * CE (#633) a row sends its LastPlayedDate only when it is newer than the server's, so a device
 * that watched the item later keeps it at the top of Continue Watching; CE (#635) a later play on
 * the server wins over the row's progress, and the server's values are stored in the rows of
 * downloads. `SyncWorkerRunTest` in `core` checks the requests and rows.
 */
class SyncMergeTest {

    private fun at(lastPlayed: Long?) =
        PlayState(
            played = false,
            favorite = false,
            positionTicks = 100L,
            lastPlayedMillis = lastPlayed,
        )

    @Test
    fun `a later local time is sent`() {
        assertTrue(at(2_000L).playedAfter(1_000L))
    }

    @Test
    fun `an earlier local time is not sent`() {
        // Another device watched the item after this one did.
        assertFalse(at(1_000L).playedAfter(2_000L))
    }

    @Test
    fun `the same time is not sent`() {
        assertFalse(at(1_000L).playedAfter(1_000L))
    }

    @Test
    fun `a local time is sent when the server has none`() {
        assertTrue(at(1_000L).playedAfter(null))
    }

    @Test
    fun `no local time is never sent`() {
        assertFalse(at(null).playedAfter(null))
        assertFalse(at(null).playedAfter(1_000L))
    }

    @Test
    fun `a later play on the server wins over the phone's`() {
        // #635: another device played the item after this one did.
        assertTrue(at(1_000L).mergeWithServer(at(2_000L)) is SyncMerge.TakeServer)
    }

    @Test
    fun `an earlier play on the server does not win`() {
        assertTrue(at(2_000L).mergeWithServer(at(1_000L)) is SyncMerge.SendLocal)
        assertTrue(at(1_000L).mergeWithServer(at(1_000L)) is SyncMerge.SendLocal)
    }

    @Test
    fun `a server without a date never wins, a row without one always loses`() {
        assertTrue(at(1_000L).mergeWithServer(at(null)) is SyncMerge.SendLocal)
        assertTrue(at(null).mergeWithServer(at(null)) is SyncMerge.SendLocal)
        // A row that was never played here (a favourite changed offline) has no progress to send.
        assertTrue(at(null).mergeWithServer(at(1_000L)) is SyncMerge.TakeServer)
    }

    @Test
    fun `an item the server does not have is sent, with its date`() {
        val row = at(1_000L)
        assertEquals(
            SyncMerge.SendLocal(state = row, lastPlayedMillisToSend = 1_000L),
            row.mergeWithServer(null),
        )
    }

    @Test
    fun `a row sends its own values, and its date only when newer`() {
        val row = at(2_000L).copy(played = true, favorite = true, positionTicks = 900L)
        assertEquals(
            SyncMerge.SendLocal(state = row, lastPlayedMillisToSend = 2_000L),
            row.mergeWithServer(at(1_000L)),
        )
        // Same time: everything but the date, which stays as the server has it.
        assertEquals(
            SyncMerge.SendLocal(state = row, lastPlayedMillisToSend = null),
            row.mergeWithServer(at(2_000L)),
        )
        assertEquals(
            SyncMerge.SendLocal(state = at(null), lastPlayedMillisToSend = null),
            at(null).mergeWithServer(null),
        )
    }

    @Test
    fun `the server's progress is stored with the row's favourite`() {
        val row = at(1_000L).copy(favorite = true, positionTicks = 100L)
        val server =
            PlayState(played = true, favorite = true, positionTicks = 0L, lastPlayedMillis = 2_000L)
        assertEquals(
            SyncMerge.TakeServer(stored = server, favoriteToSend = null),
            row.mergeWithServer(server),
        )
    }

    @Test
    fun `a favourite changed here is still sent when the server's progress wins`() {
        val row = at(1_000L).copy(favorite = false)
        val server =
            PlayState(
                played = false,
                favorite = true,
                positionTicks = 70L,
                lastPlayedMillis = 2_000L,
            )
        assertEquals(
            SyncMerge.TakeServer(stored = server.copy(favorite = false), favoriteToSend = false),
            row.mergeWithServer(server),
        )
    }

    @Test
    fun `the server's values are stored, with its last played date`() {
        val item = ItemId("00000000-0000-0000-0000-000000000001")
        val state = at(9_000L).copy(favorite = true, positionTicks = 1_270_000_000L)

        assertEquals(
            mapOf(item to state),
            refreshedFromServer(mapOf(item to state), playingHere = null),
        )
    }

    @Test
    fun `the item open in the player here is left alone`() {
        val playing = ItemId("00000000-0000-0000-0000-000000000001")
        val other = ItemId("00000000-0000-0000-0000-000000000002")
        val third = ItemId("00000000-0000-0000-0000-000000000003")

        val stored =
            refreshedFromServer(
                mapOf(third to at(30L), playing to at(10L), other to at(20L)),
                playingHere = playing,
            )

        // In the server's order.
        assertEquals(listOf(third, other), stored.keys.toList())
    }
}
