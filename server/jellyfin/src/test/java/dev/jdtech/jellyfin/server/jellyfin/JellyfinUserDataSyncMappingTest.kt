package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.domain.ports.ServerUserData
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.Test

/**
 * CE (#633), moved here from the sync's `LastPlayedDateSyncTest` with the adapter (#762): the
 * server's items become [ServerUserData] by item id. The date is handed on as the SDK gave it; the
 * sync converts it in the device's zone.
 */
class JellyfinUserDataSyncMappingTest {

    @Test
    fun `the server's user data is read by item, with its last played date`() {
        // 2026-11-13 03:02:51 UTC, as the SDK hands it over in Los Angeles.
        val played = LocalDateTime.of(2026, 11, 12, 19, 2, 51)
        val withDate = item(lastPlayed = played, positionTicks = 1_270_000_000L, favorite = true)
        val withoutDate = item(lastPlayed = null)
        val withoutUserData = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)

        val read = serverUserDataOf(listOf(withDate, withoutDate, withoutUserData))

        assertEquals(setOf(withDate.id.toItemId(), withoutDate.id.toItemId()), read.keys)
        assertEquals(
            ServerUserData(
                itemId = withDate.id.toItemId(),
                played = false,
                favorite = true,
                playbackPositionTicks = 1_270_000_000L,
                lastPlayedDate = played,
            ),
            read[withDate.id.toItemId()],
        )
        assertEquals(null, read[withoutDate.id.toItemId()]!!.lastPlayedDate)
    }

    private fun item(
        lastPlayed: LocalDateTime?,
        positionTicks: Long = 0L,
        favorite: Boolean = false,
    ): BaseItemDto {
        val id = UUID.randomUUID()
        return BaseItemDto(
            id = id,
            type = BaseItemKind.EPISODE,
            userData =
                UserItemDataDto(
                    playbackPositionTicks = positionTicks,
                    playCount = 0,
                    isFavorite = favorite,
                    played = false,
                    key = "",
                    itemId = id,
                    lastPlayedDate = lastPlayed,
                ),
        )
    }
}
