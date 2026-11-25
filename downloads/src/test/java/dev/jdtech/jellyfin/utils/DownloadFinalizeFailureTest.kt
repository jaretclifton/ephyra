package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Environment
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MediaStreamEntity
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import dev.jdtech.jellyfin.utils.download.TestCurrentServerId
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * CE (#848): a finished transfer whose finalizing fails keeps its `.download` file and its row, so
 * the queue can show it as failed and Retry finalizes it without downloading it again. A failed
 * rename used to delete the finished download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadFinalizeFailureTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val itemId = UUID.randomUUID().toString().toItemId()
    private lateinit var root: File

    /** Every request the server got, with its Range header. */
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var failRoomWrite = false
    @Volatile private var failSubtitleWrite = false

    private val engine =
        MediaDownloadEngine(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val range = chain.request().header("Range")
                    requests.add(range ?: "full")
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0L
                    val builder =
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    if (from >= VIDEO.size) {
                        builder.code(416).message("Range Not Satisfiable").body("".toResponseBody())
                    } else {
                        builder.code(200).message("OK").body(VIDEO.toResponseBody())
                    }
                    builder.build()
                }
                .build(),
            Online,
        )

    @Before
    fun setUp() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val external = context.getExternalFilesDirs(null).first()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        root =
            File(external, "downloads").apply {
                deleteRecursively()
                mkdirs()
            }
        runBlocking { database().insertMovie(movie()) }
    }

    @After
    fun tearDown() {
        room.close()
        root.deleteRecursively()
    }

    @Test
    fun `a finished download finalizes as before`() {
        val source = downloadingSource()
        transfer(source)

        assertTrue(runBlocking { downloader().finalizeDownload(VIDEO_ID) })

        assertFinalized(source)
    }

    @Test
    fun `a failed rename keeps the finished file and its row, and retry finalizes it`() {
        val source = downloadingSource()
        transfer(source)
        // Something is in the way of the final name: neither the rename nor the copy can use it.
        val blocker = File(final(source), "in-the-way").apply { parentFile!!.mkdirs() }
        blocker.writeText("x")

        assertFalse(runBlocking { downloader().finalizeDownload(VIDEO_ID) })

        assertKeptForRetry(source)
        assertNotNull(runBlocking { database().getMovieOrNull(itemId) }, "the item was deleted")
        assertTrue(File(final(source)).isDirectory, "a partial copy took the final name")

        // The user clears the way and taps Retry: the transfer resumes, the server says the file
        // is complete, and it is finalized without downloading it again.
        File(final(source)).deleteRecursively()
        transfer(source)
        assertEquals("bytes=${VIDEO.size}-", requests.last())
        assertTrue(runBlocking { downloader().finalizeDownload(VIDEO_ID) })
        assertFinalized(source)
    }

    @Test
    fun `a failed Room write puts the file back under its download name`() {
        val source = downloadingSource()
        transfer(source)
        failRoomWrite = true

        assertFalse(runBlocking { downloader().finalizeDownload(VIDEO_ID) })

        assertKeptForRetry(source)
        assertFalse(File(final(source)).exists(), "the final file is not in any row")

        failRoomWrite = false
        assertTrue(runBlocking { downloader().finalizeDownload(VIDEO_ID) })
        assertFinalized(source)
    }

    @Test
    fun `a failed subtitle write does not fail a finalized video`() {
        val source = downloadingSource()
        val subtitle = subtitleRow(source)
        transfer(source)
        transfer(SUBTITLE_ID, subtitle.path)
        failSubtitleWrite = true

        assertTrue(runBlocking { downloader().finalizeDownload(VIDEO_ID) })

        assertFinalized(source)
    }

    @Test
    fun `a download renamed when the app stopped is finalized at the next start, not resumed`() {
        val source = downloadingSource()
        // The app stopped between the rename and the Room write: the engine of the new process
        // does not know the transfer.
        File(final(source)).writeBytes(VIDEO)

        val active = runBlocking { downloader().getActiveDownloads() }

        assertEquals(emptyList(), active.map { it.item.id })
        assertFinalized(source)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a download renamed when the app stopped is not resumed while its Room write fails`() {
        val source = downloadingSource()
        File(final(source)).writeBytes(VIDEO)
        failRoomWrite = true

        val active = runBlocking { downloader().getActiveDownloads() }

        // Kept for the next start; nothing is downloaded again.
        assertEquals(emptyList(), active.map { it.item.id })
        assertEquals(source.path, sourceRow().path)
        assertTrue(File(final(source)).readBytes().contentEquals(VIDEO))
        assertEquals(emptyList(), requests)

        failRoomWrite = false
        runBlocking { downloader().getActiveDownloads() }
        assertFinalized(source)
    }

    @Test
    fun `the orphan sweep finalizes a download renamed when the app stopped, if it runs first`() {
        val source = downloadingSource()
        File(final(source)).writeBytes(VIDEO)

        runBlocking { downloader().sweepOrphans() }

        assertFinalized(source)
        assertNotNull(runBlocking { database().getMovieOrNull(itemId) }, "the item was deleted")
    }

    @Test
    fun `a download stopped during its transfer is still resumed at the next start`() {
        val source = downloadingSource()
        File(source.path).writeBytes(VIDEO.copyOf(4))

        val active = runBlocking { downloader().getActiveDownloads() }

        assertEquals(listOf(itemId), active.map { it.item.id })
        assertEquals(source.path, sourceRow().path)
    }

    private fun assertKeptForRetry(source: ItemSourceEntity) {
        assertEquals(source.path, sourceRow().path)
        assertTrue(File(source.path).readBytes().contentEquals(VIDEO), "the finished file is gone")
    }

    private fun assertFinalized(source: ItemSourceEntity) {
        assertEquals(final(source), sourceRow().path)
        assertTrue(File(final(source)).readBytes().contentEquals(VIDEO))
        assertFalse(File(source.path).exists())
    }

    private fun sourceRow() = runBlocking {
        room.getServerDatabaseDao().getSources(itemId).single()
    }

    private fun final(source: ItemSourceEntity) = source.path.removeSuffix(".download")

    private fun subtitleRow(source: ItemSourceEntity): MediaStreamEntity {
        val id = MediaStreamId.random()
        val row =
            MediaStreamEntity(
                id = id,
                sourceId = source.id,
                title = "Deutsch",
                displayTitle = null,
                language = "deu",
                type = MediaStreamType.SUBTITLE,
                codec = "subrip",
                isExternal = true,
                path = File(root, "Movie (2024)/Movie (2024).$id.srt.download").absolutePath,
                channelLayout = null,
                videoRangeType = null,
                height = null,
                width = null,
                videoDoViTitle = null,
                downloadId = SUBTITLE_ID,
            )
        runBlocking { database().insertMediaStream(row) }
        return row
    }

    private fun transfer(source: ItemSourceEntity) = transfer(VIDEO_ID, source.path)

    private fun transfer(id: Long, path: String) {
        engine.start(
            MediaDownloadEngine.Request(
                id = id,
                url = "http://server.test/$id",
                destFile = File(path),
                allowMetered = true,
                allowRoaming = true,
            )
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.snapshot(id)?.status != DownloadStatus.SUCCESSFUL) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun downloadingSource(): ItemSourceEntity {
        val file = File(root, "Movie (2024)/Movie (2024).mkv.download")
        file.parentFile!!.mkdirs()
        val source =
            ItemSourceEntity(
                id = "source".toMediaSourceId(),
                itemId = itemId,
                name = "Original",
                type = ItemSourceType.LOCAL,
                path = file.absolutePath,
                downloadId = VIDEO_ID,
            )
        runBlocking { database().insertSource(source) }
        return source
    }

    private fun movie() =
        MovieEntity(
            id = itemId,
            serverId = "server".toServerId(),
            name = "Movie",
            originalTitle = null,
            overview = "",
            runtimeTicks = 0L,
            premiereDate = null,
            communityRating = null,
            officialRating = null,
            status = "",
            productionYear = null,
            endDate = null,
            chapters = null,
        )

    /** Room, with a write that fails the way a full disk makes it fail when [failRoomWrite]. */
    private fun database(): ServerDatabaseDao {
        val real = room.getServerDatabaseDao()
        return fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            if (failRoomWrite && method.kotlinName == "setSourceDownloaded") {
                throw SQLiteFullException("database or disk is full")
            }
            if (failSubtitleWrite && method.kotlinName == "setMediaStreamPath") {
                throw SQLiteFullException("database or disk is full")
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
            as ServerDatabaseDao
    }

    private fun downloader(): DownloaderImpl {
        val repository =
            FakeRoles(ItemCatalog::class, PlaybackMedia::class, CurrentAccount::class) {
                _,
                method,
                _ ->
                when (method.kotlinName) {
                    "getUserId" -> USER_ID
                    else ->
                        throw IllegalStateException(
                            "${method.declaringClass.simpleName}.${method.kotlinName}"
                        )
                }
            }
        return DownloaderImpl(
            context,
            RoomDownloadStore(database()),
            Provider { repository.role() },
            Provider { repository.role() },
            Provider { repository.role() },
            TestDownloadSettings(),
            TestCurrentServerId(),
            Provider { error("No WorkManager in this test") },
            engine,
            TestDownloadTexts,
        )
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val VIDEO_ID = 1L
        const val SUBTITLE_ID = 2L
        val USER_ID: UserId = "00000000-0000-0000-0000-0000000000a1".toUserId()
        val VIDEO = ByteArray(64) { it.toByte() }
    }
}
