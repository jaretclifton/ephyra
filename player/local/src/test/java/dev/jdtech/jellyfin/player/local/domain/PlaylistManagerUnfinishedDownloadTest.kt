package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** An unfinished download's partial file is never what plays (#454). */
class PlaylistManagerUnfinishedDownloadTest {
    private val episode = episode()

    @Test
    fun `an unfinished download gives way to the server source`() {
        val manager = playlistManager(fakeRepository(listOf(partial(), remote())))

        val item = runBlocking { manager.getInitialItem(episode.id, ItemKind.EPISODE) }

        assertEquals(REMOTE_PATH, item?.mediaSourceUri)
    }

    @Test
    fun `an unfinished download alone is not played and gives no raw error text`() {
        val manager = playlistManager(fakeRepository(listOf(partial())))

        try {
            val item = runBlocking { manager.getInitialItem(episode.id, ItemKind.EPISODE) }
            fail("Expected no playable source, got ${item?.mediaSourceUri}")
        } catch (e: NoSuchElementException) {
            // PlayerViewModel toasts the message when there is one; the player's own error
            // is the one the user should see.
            assertNull(e.message)
        }
    }

    private fun fakeRepository(sources: List<ItemSource>) =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "getEpisode" -> episode
                "getPlaybackSources" -> sources
                else -> error("Unexpected call to ${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun partial() =
        ItemSource(
            id = "source",
            name = "Source",
            type = ItemSourceType.LOCAL,
            path = "/downloads/Show/S01/S01E01 - Episode.ts.download",
            size = 0L,
            mediaStreams = emptyList(),
            downloadId = 1L,
        )

    private fun remote() =
        ItemSource(
            id = "source",
            name = "Source",
            type = ItemSourceType.REMOTE,
            path = REMOTE_PATH,
            size = 0L,
            mediaStreams = emptyList(),
        )

    private fun episode() =
        Episode(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Episode 1",
            originalTitle = null,
            overview = "",
            indexNumber = 1,
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
            seriesId = UUID.randomUUID().toString().toItemId(),
            seriesName = "Show",
            seasonId = UUID.randomUUID().toString().toItemId(),
            seasonName = "Season 1",
            communityRating = null,
            people = emptyList(),
            missing = false,
            images = ItemImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private companion object {
        const val REMOTE_PATH = "http://server/video"
    }
}
