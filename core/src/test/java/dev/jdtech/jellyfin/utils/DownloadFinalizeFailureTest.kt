package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.Environment
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.download.DownloadStatus
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
    private val itemId = UUID.randomUUID()
    private lateinit var root: File

    /** Every request the server got, with its Range header. */
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var failRoomWrite = false

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
    fun `a download stopped during its transfer is still resumed at the next start`() {
        val source = downloadingSource()
        File(source.path).writeBytes(VIDEO.copyOf(4))

        val active = runBlocking { downloader().getActiveDownloads() }

        assertEquals(listOf(itemId), active.map { it.item.id })
        assertEquals(source.path, sourceRow().path)
    }

    private fun assertKeptForRetry(source: FindroidSourceDto) {
        assertEquals(source.path, sourceRow().path)
        assertTrue(File(source.path).readBytes().contentEquals(VIDEO), "the finished file is gone")
    }

    private fun assertFinalized(source: FindroidSourceDto) {
        assertEquals(final(source), sourceRow().path)
        assertTrue(File(final(source)).readBytes().contentEquals(VIDEO))
        assertFalse(File(source.path).exists())
    }

    private fun sourceRow() = runBlocking {
        room.getServerDatabaseDao().getSources(itemId).single()
    }

    private fun final(source: FindroidSourceDto) = source.path.removeSuffix(".download")

    private fun transfer(source: FindroidSourceDto) {
        engine.start(
            MediaDownloadEngine.Request(
                id = VIDEO_ID,
                url = "http://server.test/video",
                destFile = File(source.path),
                allowMetered = true,
                allowRoaming = true,
            )
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.snapshot(VIDEO_ID)?.status != DownloadStatus.SUCCESSFUL) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun downloadingSource(): FindroidSourceDto {
        val file = File(root, "Movie (2024)/Movie (2024).mkv.download")
        file.parentFile!!.mkdirs()
        val source =
            FindroidSourceDto(
                id = "source",
                itemId = itemId,
                name = "Original",
                type = FindroidSourceType.LOCAL,
                path = file.absolutePath,
                downloadId = VIDEO_ID,
            )
        runBlocking { database().insertSource(source) }
        return source
    }

    private fun movie() =
        FindroidMovieDto(
            id = itemId,
            serverId = "server",
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
        return Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            if (failRoomWrite && method.name == "setSourceDownloaded") {
                throw SQLiteFullException("database or disk is full")
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ServerDatabaseDao
    }

    private fun downloader(): DownloaderImpl {
        val repository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getUserId" -> USER_ID
                    else -> throw IllegalStateException("JellyfinRepository.${method.name}")
                }
            } as JellyfinRepository
        val preferences =
            context.getSharedPreferences("download-finalize-failure-test", Context.MODE_PRIVATE)
        return DownloaderImpl(
            context,
            database(),
            Provider { repository },
            AppPreferences(preferences),
            Provider { error("No WorkManager in this test") },
            engine,
        )
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val VIDEO_ID = 1L
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val VIDEO = ByteArray(64) { it.toByte() }
    }
}
