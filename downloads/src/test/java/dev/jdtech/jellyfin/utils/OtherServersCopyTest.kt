package dev.jdtech.jellyfin.utils

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.RoomDownloadStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toEpisode
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.models.toMovie
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.kotlinName
import dev.jdtech.jellyfin.utils.download.MediaDownloadEngine
import dev.jdtech.jellyfin.utils.download.TestCurrentServerId
import dev.jdtech.jellyfin.utils.download.TestDownloadSettings
import dev.jdtech.jellyfin.utils.download.TestDownloadTexts
import java.io.File
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment

/**
 * CE (#718): Room keeps one copy per item id. A download from one server of an id another server
 * stored replaces that copy whole (rows and files), so nothing is left behind under the other
 * server's name; a download of the server's own copy, or of an item nobody stored, touches nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OtherServersCopyTest {
    private val userA = "00000000-0000-0000-0000-0000000000a1".toUserId()
    private val userB = "00000000-0000-0000-0000-0000000000b1".toUserId()
    private val movieId = "00000000-0000-0000-0000-000000000040".toItemId()
    private val showId = "00000000-0000-0000-0000-000000000020".toItemId()
    private val seasonId = "00000000-0000-0000-0000-000000000021".toItemId()
    private val episodeId = "00000000-0000-0000-0000-000000000022".toItemId()
    private val otherEpisodeId = "00000000-0000-0000-0000-000000000023".toItemId()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private val currentServer = TestCurrentServerId()
    private val dir = File(context.cacheDir, "other-servers-copy").apply { mkdirs() }

    /** What the server answers; anything else fails, as with the server gone. */
    private var server: (String) -> Any? = { name -> throw IllegalStateException(name) }

    private val downloader: DownloaderImpl by lazy {
        val repository =
            FakeRoles(ItemCatalog::class, PlaybackMedia::class, CurrentAccount::class) {
                _,
                method,
                _ ->
                server("${method.declaringClass.simpleName}.${method.kotlinName}")
            }
        DownloaderImpl(
            context,
            RoomDownloadStore(database),
            Provider { repository.role() },
            Provider { repository.role() },
            Provider { repository.role() },
            TestDownloadSettings(),
            currentServer,
            Provider { error("No WorkManager in this test") },
            MediaDownloadEngine(OkHttpClient(), Online),
            TestDownloadTexts,
        )
    }

    @After
    fun close() {
        room.close()
        dir.deleteRecursively()
    }

    private fun current(serverId: String) {
        currentServer.id = serverId.toServerId()
    }

    private fun file(itemId: ItemId) =
        File(dir, "${itemId.asString()}.mkv").apply { writeText("video") }

    private suspend fun storeSource(itemId: ItemId): File {
        val file = file(itemId)
        database.insertSource(
            ItemSourceEntity(
                id = "source-${itemId.asString()}".toMediaSourceId(),
                itemId = itemId,
                name = "Original",
                type = ItemSourceType.LOCAL,
                path = file.absolutePath,
            )
        )
        return file
    }

    private suspend fun storeMovie(serverId: String): File {
        database.insertMovie(
            MovieEntity(
                id = movieId,
                serverId = serverId.toServerId(),
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
        )
        return storeSource(movieId)
    }

    private suspend fun storeEpisode(serverId: String, id: ItemId, index: Int): File {
        database.insertShow(
            ShowEntity(
                id = showId,
                serverId = serverId.toServerId(),
                name = "Bluey",
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = 2018,
                endDate = null,
            )
        )
        database.insertSeason(
            SeasonEntity(
                id = seasonId,
                seriesId = showId,
                name = "Season 1",
                seriesName = "Bluey",
                overview = "",
                indexNumber = 1,
            )
        )
        database.insertEpisode(
            EpisodeEntity(
                id = id,
                serverId = serverId.toServerId(),
                seasonId = seasonId,
                seriesId = showId,
                name = "Episode $index",
                seriesName = "Bluey",
                overview = "",
                indexNumber = index,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                runtimeTicks = 0L,
                premiereDate = null,
                communityRating = null,
                chapters = null,
            )
        )
        return storeSource(id)
    }

    private suspend fun userData(userId: UserId, itemId: ItemId, toBeSynced: Boolean) =
        database.insertUserData(
            UserDataEntity(
                userId = userId,
                itemId = itemId,
                played = false,
                favorite = false,
                playbackPositionTicks = 1_200_000_000L,
                toBeSynced = toBeSynced,
            )
        )

    @Test
    fun `a download of a movie another server stored replaces that server's copy`(): Unit =
        runBlocking {
            val file = storeMovie(SERVER_A)
            userData(userA, movieId, toBeSynced = true)
            val movie = database.getMovie(movieId).toMovie(database, userB)

            current(SERVER_B)
            downloader.releaseOtherServersCopy(movie, SERVER_B.toServerId())

            assertNull(database.getMovieOrNull(movieId), "A's movie row")
            assertEquals(emptyList(), database.getSources(movieId), "A's source rows")
            assertFalse(file.exists(), "A's file")
            // A's user watched it offline: that still goes to A.
            assertNotNull(database.getUserDataToBeSynced(userA, movieId), "A's progress to send")
        }

    @Test
    fun `the current server's own copy is left alone`(): Unit = runBlocking {
        val file = storeMovie(SERVER_A)
        val movie = database.getMovie(movieId).toMovie(database, userA)

        current(SERVER_A)
        downloader.releaseOtherServersCopy(movie, SERVER_A.toServerId())

        assertEquals(SERVER_A, database.getMovieOrNull(movieId)?.serverId?.asString())
        assertEquals(1, database.getSources(movieId).size)
        assertTrue(file.exists(), "A's file")
    }

    @Test
    fun `an episode another server stored gives way, its other episodes stay`(): Unit =
        runBlocking {
            val taken = storeEpisode(SERVER_A, episodeId, index = 1)
            val kept = storeEpisode(SERVER_A, otherEpisodeId, index = 2)
            val episode = database.getEpisode(episodeId).toEpisode(database, userB)

            current(SERVER_B)
            downloader.releaseOtherServersCopy(episode, SERVER_B.toServerId())

            assertNull(database.getEpisodeOrNull(episodeId), "the episode B downloads")
            assertFalse(taken.exists(), "its file")
            assertEquals(SERVER_A, database.getEpisodeOrNull(otherEpisodeId)?.serverId?.asString())
            assertTrue(kept.exists(), "A's other episode's file")
            assertEquals(listOf(seasonId), database.getSeasonsByShowId(showId).map { it.id })
            assertEquals(1, database.getSources(otherEpisodeId).size)
        }

    /** The server's source of the movie, [size] bytes. */
    private fun serverSource(size: Long) =
        ItemSource(
            id = "source-${movieId.asString()}".toMediaSourceId(),
            name = "Original",
            type = ItemSourceType.REMOTE,
            path = "http://server.invalid/Videos/${movieId.asString()}/stream",
            size = size,
            mediaStreams = emptyList(),
        )

    private fun assertMovieKept(file: File) = runBlocking {
        assertEquals(
            SERVER_A,
            database.getMovieOrNull(movieId)?.serverId?.asString(),
            "A's movie row",
        )
        assertEquals(listOf(file.absolutePath), database.getSources(movieId).map { it.path })
        assertTrue(file.exists(), "A's file")
    }

    @Test
    fun `a download refused for want of space leaves the other server's copy`(): Unit =
        runBlocking {
            ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
            for (external in context.getExternalFilesDirs(null)) {
                ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_MOUNTED)
            }
            val file = storeMovie(SERVER_A)
            val movie = database.getMovie(movieId).toMovie(database, userB)
            server = { name ->
                when (name) {
                    "PlaybackMedia.getMediaSources" -> listOf(serverSource(Long.MAX_VALUE / 4))
                    "PlaybackMedia.getServerSegments" -> emptyList<Any>()
                    else -> throw IllegalStateException(name)
                }
            }

            current(SERVER_B)
            val (downloadId, error) =
                downloader.downloadItem(
                    movie,
                    "source-${movieId.asString()}".toMediaSourceId(),
                    0,
                    null,
                )

            assertEquals(-1L, downloadId)
            assertNotNull(error, "the refusal")
            assertMovieKept(file)
        }

    @Test
    fun `a download that fails at the server leaves the other server's copy`(): Unit = runBlocking {
        val file = storeMovie(SERVER_A)
        val movie = database.getMovie(movieId).toMovie(database, userB)
        // The source comes, then the server stops answering.
        server = { name ->
            when (name) {
                "PlaybackMedia.getMediaSources" -> listOf(serverSource(1L))
                else -> throw IllegalStateException(name)
            }
        }

        current(SERVER_B)
        val (downloadId, _) =
            downloader.downloadItem(
                movie,
                "source-${movieId.asString()}".toMediaSourceId(),
                0,
                null,
            )

        assertEquals(-1L, downloadId)
        assertMovieKept(file)
    }

    private object Online : NetworkConnectivity {
        override fun isOnline() = true

        override fun isMetered() = false

        override fun isRoaming() = false
    }

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
    }
}
