package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.SeriesTrackPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The episode list for the queue is fetched after the first item, not before it. */
class PlaylistManagerQueueTest {
    private val seriesId = UUID.randomUUID().toString().toItemId()
    private val seasonId = UUID.randomUUID().toString().toItemId()
    private val first = episode(1)
    private val second = episode(2)
    private val calls = mutableListOf<String>()

    @Test
    fun `the first episode is returned without fetching the season`() {
        val manager = playlistManager(fakeRepository())

        val item = runBlocking { manager.getInitialItem(first.id, ItemKind.EPISODE) }

        assertNotNull(item)
        assertEquals(first.id, item!!.itemId)
        assertEquals(false, "getEpisodes" in calls)
    }

    @Test
    fun `loading the queue fills in the upcoming episodes once`() {
        val manager = playlistManager(fakeRepository())
        runBlocking {
            manager.getInitialItem(first.id, ItemKind.EPISODE)
            manager.loadQueue()
            manager.loadQueue()
        }

        assertEquals(listOf(second.id), manager.upcomingItems().map { it.id })
        assertEquals(1, calls.count { it == "getEpisodes" })
    }

    @Test
    fun `the next item waits for the queue`() {
        val manager = playlistManager(fakeRepository())
        val next = runBlocking {
            manager.getInitialItem(first.id, ItemKind.EPISODE)
            manager.getNextPlayerItem()
        }

        assertEquals(second.id, next?.itemId)
    }

    private fun fakeRepository() =
        FakeRoles(ItemCatalog::class, SeriesTrackPreferences::class, PlaybackMedia::class) {
            _,
            method,
            _ ->
            calls.add(method.kotlinName)
            when (method.kotlinName) {
                "getEpisode" -> first
                "getEpisodes" -> listOf(first, second)
                "getSeasons" -> emptyList<Any>()
                "getPlaybackSources" -> listOf(source())
                else -> error("Unexpected call to ${method.kotlinName}")
            }
        }

    private fun episode(number: Int) =
        Episode(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Episode $number",
            originalTitle = null,
            overview = "",
            indexNumber = number,
            indexNumberEnd = null,
            parentIndexNumber = 1,
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            seriesId = seriesId,
            seriesName = "Show",
            seasonId = seasonId,
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun source() =
        ItemSource(
            id = "source".toMediaSourceId(),
            name = "Source",
            type = ItemSourceType.REMOTE,
            path = "http://server/video",
            size = 0L,
            mediaStreams = emptyList(),
        )
}
