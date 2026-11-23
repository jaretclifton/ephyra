package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.R as DownloadsR
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.testing.asItemIdOrNull
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#383): a download the server refuses shows why, with its Retry, instead of waiting through
 * three auto-retries. A server error that may pass keeps its auto-retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueHttpErrorTest {
    private val item = episode()

    private val partialRow =
        ItemSourceEntity(
            id = "source".toMediaSourceId(),
            itemId = item.id,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = "/downloads/Show/S01/S01E01 - Episode.ts.download",
            downloadId = DOWNLOAD_ID,
        )

    private val downloader = FakeDownloader()

    @Test
    fun `a download the server refuses fails at once with the status and is not auto-retried`() {
        val queue = refusedDownload(404)

        val entry = entry(queue)
        assertEquals(0, entry.retryCount)
        assertNull(entry.retryAt)
        val error = (entry.state as DownloadQueue.EntryState.Failed).error
        val resource = error as UiText.StringResource
        assertEquals(DownloadsR.string.ce_download_error_server, resource.resId)
        assertEquals(listOf(404), resource.args.toList())
        // It stays failed: no auto-retry is waiting.
        Thread.sleep(500)
        assertEquals(DownloadQueue.EntryState.Failed::class, entry(queue).state::class)
        assertEquals(1, downloader.starts)
    }

    @Test
    fun `a server error that may pass is still auto-retried`() {
        downloader.startResult = DOWNLOAD_ID to null
        downloader.progress = Downloader.Progress(DownloadStatus.FAILED, -1, 0L, -1L, 503)
        val queue = queue()
        runBlocking { queue.enqueue(item) }

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Pending && it.retryCount == 1 }
    }

    @Test
    fun `retry starts a refused download again`() {
        val queue = refusedDownload(404)
        downloader.progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)

        queue.retry(item.id)

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
        assertEquals(2, downloader.starts)
    }

    @Test
    fun `cancelling a refused download removes its partial download`() {
        val queue = refusedDownload(404)

        queue.remove(item.id)

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (downloader.cancelled.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(listOf(item.id to DOWNLOAD_ID), downloader.cancelled.toList())
    }

    private fun refusedDownload(code: Int): DownloadQueue {
        downloader.startResult = DOWNLOAD_ID to null
        downloader.progress = Downloader.Progress(DownloadStatus.FAILED, -1, 0L, -1L, code)
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        return queue
    }

    private fun entry(queue: DownloadQueue) = queue.entries.value.first { it.id == item.id }

    private fun awaitEntry(queue: DownloadQueue, matches: (DownloadQueue.Entry) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val entry = queue.entries.value.firstOrNull { it.id == item.id }
            if (entry != null && matches(entry)) return
            Thread.sleep(20)
        }
        fail("Entry never reached the state: ${queue.entries.value}")
    }

    private fun queue(): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        return DownloadQueue(
            downloader = downloader,
            settings = TestDownloadSettings(maxConcurrentDownloads = 1),
            repositoryProvider = Provider { error("No repository in this test") },
            downloadStore = RoomDownloadStore(database()),
            context = context,
            texts = TestDownloadTexts,
        )
    }

    private fun database() =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getSources" ->
                    listOf(partialRow).filter { it.itemId == args?.firstOrNull().asItemIdOrNull() }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private class FakeDownloader : Downloader {
        var startResult: Pair<Long, UiText?> = -1L to null
        @Volatile var progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
        @Volatile var starts = 0
        val cancelled = CopyOnWriteArrayList<Pair<ItemId, Long>>()

        override suspend fun downloadItem(
            item: Item,
            sourceId: MediaSourceId,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = startResult.also { starts++ }

        override suspend fun downloadItem(
            item: Item,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = startResult.also { starts++ }

        override suspend fun cancelDownload(item: Item, downloadId: Long) {
            cancelled.add(item.id to downloadId)
        }

        override suspend fun retryFailedMediaStreams(item: Item) = Unit

        override suspend fun deleteItem(item: Item, source: ItemSource) = Unit

        override suspend fun getProgress(downloadId: Long?) = progress

        override suspend fun getProgress(downloadIds: List<Long>) = downloadIds.associateWith {
            progress
        }

        override suspend fun getActiveDownloads() = emptyList<Downloader.ActiveDownload>()

        override suspend fun savePendingDownload(
            item: Item,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = Unit

        override suspend fun removePendingDownload(itemId: ItemId) = Unit

        override suspend fun getPendingDownloads() = emptyList<Downloader.PendingDownload>()

        override suspend fun sweepOrphans() = Unit

        override suspend fun finalizeFinishedMediaStreams() = Unit

        override suspend fun finalizeDownload(downloadId: Long) = false
    }

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
            canDownload = true,
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
        const val DOWNLOAD_ID = 7L
        const val TIMEOUT_MS = 5_000L
    }
}
