package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.testing.fakeProxy
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
        fakeProxy(
            JellyfinRepository::class.java.classLoader,
            arrayOf(JellyfinRepository::class.java),
        ) { _, method, _ ->
            calls.add(method.kotlinName)
            when (method.kotlinName) {
                "getEpisode" -> first
                "getEpisodes" -> listOf(first, second)
                "getSeasons" -> emptyList<Any>()
                "getPlaybackSources" -> listOf(source())
                else -> error("Unexpected call to ${method.kotlinName}")
            }
        }
            as JellyfinRepository

    private fun episode(number: Int) =
        FindroidEpisode(
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
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun source() =
        FindroidSource(
            id = "source",
            name = "Source",
            type = FindroidSourceType.REMOTE,
            path = "http://server/video",
            size = 0L,
            mediaStreams = emptyList(),
        )
}
