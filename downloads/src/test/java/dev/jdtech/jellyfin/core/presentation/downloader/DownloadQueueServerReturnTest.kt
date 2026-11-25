package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.repository.ServerAvailability
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#912): a download that failed for want of the server (also after an app restart with the
 * server still away) goes on by itself when the server is confirmed again, instead of staying
 * failed until the user presses Retry. One the server refused stays failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueServerReturnTest {
    private val item = episode()
    private val availability = FakeAvailability()
    private val downloader = FakeDownloader()

    @Test
    fun `a download that could not start without the server goes on when it returns`() {
        downloader.startResult = -1L to UiText.StringResource(TestDownloadTexts.START_ERROR)
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        assertEquals(1, downloader.starts)

        downloader.startResult = DOWNLOAD_ID to null
        availability.confirm()

        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Downloading }
        assertEquals(2, downloader.starts)
        assertFalse(entry(queue).retryWhenServerReturns)
    }

    @Test
    fun `a download the server refused stays failed when it returns`() {
        downloader.startResult = DOWNLOAD_ID to null
        downloader.progress =
            Downloader.Progress(DownloadStatus.FAILED, 0, 0L, -1L, httpStatus = 403)
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        assertFalse(entry(queue).retryWhenServerReturns)

        availability.confirm()

        Thread.sleep(500)
        assertTrue(entry(queue).state is DownloadQueue.EntryState.Failed)
        assertEquals(1, downloader.starts)
    }

    @Test
    fun `a download the user removed does not come back when the server returns`() {
        downloader.startResult = -1L to UiText.StringResource(TestDownloadTexts.START_ERROR)
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        awaitEntry(queue) { it.state is DownloadQueue.EntryState.Failed }
        queue.remove(item.id)
        awaitGone(queue)

        availability.confirm()

        Thread.sleep(500)
        assertTrue(queue.entries.value.isEmpty())
        assertEquals(1, downloader.starts)
    }

    private fun awaitGone(queue: DownloadQueue) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (queue.entries.value.none { it.id == item.id }) return
            Thread.sleep(20)
        }
        fail("Entry never left the queue: ${queue.entries.value}")
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
            downloadSources = RoomDownloadStore(database()),
            context = context,
            texts = TestDownloadTexts,
            serverAvailability = availability,
        )
    }

    private fun database() =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
        }
            as ServerDatabaseDao

    private class FakeAvailability : ServerAvailability {
        private val confirmed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val serverConfirmed = confirmed

        /** Waits for the queue to listen, as the app's server watcher starts before it does. */
        fun confirm() {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (
                confirmed.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10)
            }
            confirmed.tryEmit(Unit)
        }
    }

    private class FakeDownloader : Downloader {
        @Volatile var startResult: Pair<Long, UiText?> = -1L to null
        @Volatile var onStart: () -> Unit = {}
        @Volatile var starts = 0

        private fun start(): Pair<Long, UiText?> {
            starts++
            onStart()
            return startResult
        }

        override suspend fun downloadItem(
            item: Item,
            sourceId: MediaSourceId,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun downloadItem(
            item: Item,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun cancelDownload(item: Item, downloadId: Long) = Unit

        override suspend fun retryFailedMediaStreams(item: Item) = Unit

        override suspend fun deleteItem(item: Item, source: ItemSource) = Unit

        @Volatile var progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)

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
        const val TIMEOUT_MS = 8_000L
    }
}
