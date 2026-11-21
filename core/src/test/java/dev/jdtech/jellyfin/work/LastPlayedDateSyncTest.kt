package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.domain.ports.ServerUserData
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * CE (#633): the sync sends a row's LastPlayedDate only when it is newer than the server's, so a
 * device that watched the item later keeps it at the top of Continue Watching.
 *
 * #762: how the server's items become [ServerUserData] is tested with the adapter, in
 * `JellyfinUserDataSyncMappingTest` of `data`.
 */
class LastPlayedDateSyncTest {

    @Test
    fun `a later local time is sent`() {
        assertTrue(isNewerThanServer(local = 2_000L, server = 1_000L))
    }

    @Test
    fun `an earlier local time is not sent`() {
        // Another device watched the item after this one did.
        assertFalse(isNewerThanServer(local = 1_000L, server = 2_000L))
    }

    @Test
    fun `the same time is not sent`() {
        assertFalse(isNewerThanServer(local = 1_000L, server = 1_000L))
    }

    @Test
    fun `a local time is sent when the server has none`() {
        assertTrue(isNewerThanServer(local = 1_000L, server = null))
    }

    @Test
    fun `no local time is never sent`() {
        assertFalse(isNewerThanServer(local = null, server = null))
        assertFalse(isNewerThanServer(local = null, server = 1_000L))
    }

    @Test
    fun `a later play on the server wins over the phone's`() {
        // #635: another device played the item after this one did.
        assertTrue(serverIsNewer(local = 1_000L, server = 2_000L))
    }

    @Test
    fun `an earlier play on the server does not win`() {
        assertFalse(serverIsNewer(local = 2_000L, server = 1_000L))
        assertFalse(serverIsNewer(local = 1_000L, server = 1_000L))
    }

    @Test
    fun `a server without a date never wins, a row without one always loses`() {
        assertFalse(serverIsNewer(local = 1_000L, server = null))
        assertFalse(serverIsNewer(local = null, server = null))
        // A row that was never played here (a favourite changed offline) has no progress to send.
        assertTrue(serverIsNewer(local = null, server = 1_000L))
    }

    @Test
    fun `the server's last played date is read in the device's zone`() {
        // The SDK hands dates over in the device's zone; in a zone that is not UTC, a conversion
        // that ignored it would be hours off.
        val default = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            readsServerDate()
        } finally {
            TimeZone.setDefault(default)
        }
    }

    private fun readsServerDate() {
        // 2026-11-13 03:02:51 UTC, as the SDK hands it over in Los Angeles.
        val played = LocalDateTime.of(2026, 11, 12, 19, 2, 51)

        assertEquals(
            Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(),
            serverUserData(lastPlayed = played).lastPlayedMillis(),
        )
        assertEquals(null, serverUserData(lastPlayed = null).lastPlayedMillis())
    }

    @Test
    fun `dates convert between the SDK's local time and epoch milliseconds`() {
        val zone = ZoneId.of("America/Los_Angeles")
        // 2026-11-13 03:02:51 UTC, as the SDK hands it over in this time zone.
        val local = LocalDateTime.of(2026, 11, 12, 19, 2, 51)

        val millis = epochMillisOf(local, zone)

        assertEquals(Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(), millis)
        assertEquals(local, localDateTimeOf(millis, zone))
    }

    private fun serverUserData(lastPlayed: LocalDateTime?) =
        ServerUserData(
            itemId = UUID.randomUUID(),
            played = false,
            favorite = true,
            playbackPositionTicks = 1_270_000_000L,
            lastPlayedDate = lastPlayed,
        )
}
