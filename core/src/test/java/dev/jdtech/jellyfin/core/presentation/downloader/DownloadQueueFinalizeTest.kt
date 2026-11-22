package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteFullException
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CE (#848): a download whose transfer finished but whose finalizing (the rename of the `.download`
 * file, the Room write) failed was published as Completed at 100%, while the item was not there. It
 * now ends as failed, with Retry, and is never shown as completed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadQueueFinalizeTest {
    private val item = episode()
    private val downloader = FakeDownloader()
    private val observer = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        observer.cancel()
    }

    @Test
    fun `a download whose finalizing returns false ends as failed, never as completed`() {
        downloader.finalize = { false }
        assertFailsToFinalize()
    }

    @Test
    fun `a download whose finalizing throws ends as failed, never as completed`() {
        downloader.finalize = { throw SQLiteFullException("database or disk is full") }
        assertFailsToFinalize()
    }

    @Test
    fun `retry after a failed finalizing finishes the download`() {
        downloader.finalize = { false }
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        await(queue) { it.single().state is DownloadQueue.EntryState.Downloading }
        downloader.progress = Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
        await(queue) { it.single().state is DownloadQueue.EntryState.Failed }

        downloader.finalize = { true }
        queue.retry(item.id)
        await(queue) { it.single().state == DownloadQueue.EntryState.Completed }

        assertEquals(2, downloader.starts.get())
        assertEquals(100, queue.entries.value.single().progress)
    }

    @Test
    fun `a download that finalizes is completed as before`() {
        downloader.finalize = { true }
        val queue = queue()
        runBlocking { queue.enqueue(item) }
        await(queue) { it.single().state is DownloadQueue.EntryState.Downloading }
        downloader.progress = Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
        await(queue) { it.single().state == DownloadQueue.EntryState.Completed }

        val entry = queue.entries.value.single()
        assertEquals(100, entry.progress)
        assertEquals(1, downloader.finalizes.get())
        awaitIdle(queue)
    }

    private fun assertFailsToFinalize() {
        val queue = queue()
        val published = CopyOnWriteArrayList<List<DownloadQueue.Entry>>()
        queue.entries.onEach { published.add(it) }.launchIn(observer)
        runBlocking { queue.enqueue(item) }
        await(queue) { it.single().state is DownloadQueue.EntryState.Downloading }

        downloader.progress = Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
        await(queue) { it.single().state is DownloadQueue.EntryState.Failed }
        awaitIdle(queue)

        val completed = published.filter { entries ->
            entries.any { it.state == DownloadQueue.EntryState.Completed }
        }
        assertEquals(0, completed.size, message = "Published as completed: $completed")
        val entry = queue.entries.value.single()
        val error = (entry.state as DownloadQueue.EntryState.Failed).error
        assertEquals(CoreR.string.downloading_error, (error as UiText.StringResource).resId)
        // A local failure the queue cannot fix by itself waits for the user's Retry: no auto-retry
        // that downloads the file again behind their back.
        assertEquals(0, entry.retryCount)
        assertNull(entry.retryAt)
        assertEquals(1, downloader.starts.get())
    }

    private fun awaitIdle(queue: DownloadQueue) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!queue.pumpActive.value) return
            Thread.sleep(10)
        }
        fail("The pump never went idle: ${queue.entries.value}")
    }

    private fun await(queue: DownloadQueue, matches: (List<DownloadQueue.Entry>) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (matches(queue.entries.value)) return
            Thread.sleep(10)
        }
        fail("The queue never reached the state: ${queue.entries.value}")
    }

    private fun queue(): DownloadQueue {
        val context = RuntimeEnvironment.getApplication()
        val preferences =
            context
                .getSharedPreferences("download-queue-finalize-test", Context.MODE_PRIVATE)
                .apply { edit().clear().putInt("pref_downloads_max_concurrent", 1).commit() }
        return DownloadQueue(
            downloader = downloader,
            appPreferences = AppPreferences(preferences),
            repositoryProvider = Provider { error("No repository in this test") },
            database = database(),
            context = context,
        )
    }

    private fun database() =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
        } as ServerDatabaseDao

    private class FakeDownloader : Downloader {
        @Volatile var progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
        @Volatile var finalize: () -> Boolean = { true }
        val starts = AtomicInteger()
        val finalizes = AtomicInteger()

        private fun start(): Pair<Long, UiText?> {
            // Each start is a new transfer that runs until the test says it is done.
            progress = Downloader.Progress(DownloadStatus.RUNNING, 0, 0L, -1L)
            return starts.incrementAndGet().toLong() to null
        }

        override suspend fun downloadItem(
            item: FindroidItem,
            sourceId: String,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = start()

        override suspend fun downloadItem(
            item: FindroidItem,
            storageIndex: Int,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ): Pair<Long, UiText?> {
            val started = start()
            // The retried transfer finds its file complete and finishes at once.
            if (starts.get() > 1)
                progress = Downloader.Progress(DownloadStatus.SUCCESSFUL, 100, 10L, 10L)
            return started
        }

        override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) = Unit

        override suspend fun retryFailedMediaStreams(item: FindroidItem) = Unit

        override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) = Unit

        override suspend fun getProgress(downloadId: Long?) = progress

        override suspend fun getProgress(downloadIds: List<Long>) = downloadIds.associateWith {
            progress
        }

        override suspend fun getActiveDownloads() = emptyList<Downloader.ActiveDownload>()

        override suspend fun savePendingDownload(
            item: FindroidItem,
            maxBitrate: Int?,
            smartQueued: Boolean,
        ) = Unit

        override suspend fun removePendingDownload(itemId: ItemId) = Unit

        override suspend fun getPendingDownloads() = emptyList<Downloader.PendingDownload>()

        override suspend fun sweepOrphans() = Unit

        override suspend fun finalizeFinishedMediaStreams() = Unit

        override suspend fun finalizeDownload(downloadId: Long): Boolean {
            finalizes.incrementAndGet()
            return finalize()
        }
    }

    private fun episode() =
        FindroidEpisode(
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
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
