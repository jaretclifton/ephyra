package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.os.Environment
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.io.File
import java.util.UUID
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * CE (#379): after a database reset the downloaded files and their artwork stayed on disk for good.
 * The sweep now removes what no row refers to once it is a day old, and nothing when the database
 * cannot be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrphanSweepTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var root: File
    private val old = System.currentTimeMillis() - ORPHAN_MIN_AGE_MS - 60_000

    private val trackedId = UUID.randomUUID().toString().toItemId()
    private val orphanId = UUID.randomUUID().toString().toItemId()

    @Before
    fun setUp() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        val external = context.getExternalFilesDirs(null).first()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
        root = File(external, "downloads").apply { deleteRecursively() }
        File(context.filesDir, "images").deleteRecursively()
    }

    private fun file(path: String, modified: Long = old) =
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
            setLastModified(modified)
        }

    private fun artwork(id: ItemId, modified: Long = old) =
        File(context.filesDir, "images/$id/primary").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
            setLastModified(modified)
            parentFile!!.setLastModified(modified)
        }

    private fun source(file: File) =
        ItemSourceEntity(
            id = "source".toMediaSourceId(),
            itemId = trackedId,
            name = "Original",
            type = ItemSourceType.LOCAL,
            path = file.absolutePath,
        )

    @Test
    fun `untracked finished files and artwork go, tracked and fresh ones stay`() {
        val tracked = file("Short Movie (2021)/Short Movie (2021).mkv")
        val orphan = file("Pioneer One/S01/S01E01 - Earthfall.mkv")
        val freshOrphan = file("Parasite (2019)/Parasite (2019).mkv", System.currentTimeMillis())
        val emptyFolder = File(root, "Bluey/S01").apply { mkdirs() }
        emptyFolder.setLastModified(old)
        File(root, "Bluey").setLastModified(old)
        val trackedArtwork = artwork(trackedId)
        val orphanArtwork = artwork(orphanId)

        runBlocking { downloader(database(sources = listOf(source(tracked)))).sweepOrphans() }

        assertTrue(tracked.exists())
        assertFalse(orphan.exists())
        assertFalse(File(root, "Pioneer One").exists(), message = "its emptied folders go too")
        assertTrue(freshOrphan.exists())
        assertFalse(File(root, "Bluey").exists())
        assertTrue(root.isDirectory)
        assertTrue(trackedArtwork.exists())
        assertFalse(orphanArtwork.parentFile!!.exists())
    }

    @Test
    fun `nothing is deleted when the sources cannot be read`() {
        val orphan = file("Pioneer One/S01/S01E01 - Earthfall.mkv")
        val orphanArtwork = artwork(orphanId)

        val failure = runCatching {
            runBlocking {
                downloader(database(failOn = "getCompletedDownloadSources")).sweepOrphans()
            }
        }

        assertTrue(failure.isFailure)
        assertTrue(orphan.exists())
        assertTrue(orphanArtwork.exists())
    }

    @Test
    fun `nothing is deleted when the item ids cannot be read`() {
        val orphan = file("Pioneer One/S01/S01E01 - Earthfall.mkv")
        val partial = file("Parasite (2019)/Parasite (2019).mkv.download")
        val orphanArtwork = artwork(orphanId)

        val failure = runCatching {
            runBlocking { downloader(database(failOn = "getAllItemIds")).sweepOrphans() }
        }

        assertTrue(failure.isFailure)
        assertTrue(orphan.exists())
        assertTrue(partial.exists())
        assertTrue(orphanArtwork.exists())
    }

    private fun database(sources: List<ItemSourceEntity> = emptyList(), failOn: String? = null) =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            if (method.kotlinName == failOn)
                throw IllegalStateException("database cannot be opened")
            when (method.kotlinName) {
                "getCompletedDownloadSources" -> sources
                "getActiveDownloadSources" -> emptyList<ItemSourceEntity>()
                "getMediaStreamsBySourceId" -> emptyList<Any>()
                "getAllItemIds" -> listOf(trackedId)
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

    private fun downloader(database: ServerDatabaseDao): DownloaderImpl {
        val repository =
            fakeProxy(
                MediaRepository::class.java.classLoader,
                arrayOf(MediaRepository::class.java),
            ) { _, method, _ ->
                when (method.kotlinName) {
                    "getUserId" -> UUID.randomUUID().toString().toItemId()
                    else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
                }
            }
                as MediaRepository
        val preferences = context.getSharedPreferences("orphan-sweep-test", Context.MODE_PRIVATE)
        return DownloaderImpl(
            context,
            RoomDownloadStore(database),
            Provider { repository },
            AppPreferences(preferences),
            Provider { error("No WorkManager in this test") },
            MediaDownloadEngine(OkHttpClient(), Offline),
            TestDownloadTexts,
        )
    }

    private object Offline : NetworkConnectivity {
        override fun isOnline() = false

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    @Test
    fun `the minimum age is a day`() {
        assertEquals(24L * 60 * 60 * 1000, ORPHAN_MIN_AGE_MS)
    }
}
