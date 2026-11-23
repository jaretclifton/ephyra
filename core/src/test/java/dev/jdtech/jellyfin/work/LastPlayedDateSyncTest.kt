package dev.jdtech.jellyfin.work

import dev.jdtech.jellyfin.domain.ports.ServerUserData
import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.toItemId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * CE (#633): the sync compares last-played dates in epoch milliseconds, as the database keeps them,
 * while the port carries the server's local date-times. #898: the comparisons themselves are rules
 * of `PlayState`, tested in `domain` (`SyncMergeTest`).
 *
 * #762: how the server's items become [ServerUserData] is tested with the adapter, in
 * `JellyfinUserDataSyncMappingTest` of `data`.
 */
class LastPlayedDateSyncTest {

    @Test
    fun `the server's user data becomes a play state with its date in milliseconds`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val played = LocalDateTime.of(2026, 11, 12, 19, 2, 51)

        assertEquals(
            PlayState(
                played = false,
                favorite = true,
                positionTicks = 1_270_000_000L,
                lastPlayedMillis = Instant.parse("2026-11-13T03:02:51Z").toEpochMilli(),
            ),
            serverUserData(lastPlayed = played).toPlayState(zone),
        )
        assertEquals(null, serverUserData(lastPlayed = null).toPlayState(zone).lastPlayedMillis)
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
            itemId = UUID.randomUUID().toString().toItemId(),
            played = false,
            favorite = true,
            playbackPositionTicks = 1_270_000_000L,
            lastPlayedDate = lastPlayed,
        )
}
