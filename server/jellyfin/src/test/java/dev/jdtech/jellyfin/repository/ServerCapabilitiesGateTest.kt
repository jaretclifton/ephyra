package dev.jdtech.jellyfin.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.toItemId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #769: the repository does not ask a server for what its [ServerCapabilities] say it lacks, and
 * answers as for a server that has nothing to give: no segments, no trickplay, no auto-play
 * setting, the default resume thresholds. With [ServerCapabilities.ALL] (Jellyfin) it asks as
 * before. The player and the downloads ask through these calls, so this is their gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerCapabilitiesGateTest {
    private val itemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
    private val server = StandInServer()
    private val context = StandInServer.context()
    private val preferences =
        StandInServer.preferences(context).apply {
            // A current server, so only the capability can keep the resume thresholds unread.
            setValue(currentServer, "server")
        }
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()

    private fun repository(capabilities: ServerCapabilities) =
        server.repository(context, room.getServerDatabaseDao(), preferences, capabilities)

    private fun paths() = server.requests.map { it.path }

    @After
    fun stop() {
        server.close()
        room.close()
    }

    @Test
    fun `Jellyfin is asked for segments`() = runBlocking {
        repository(ServerCapabilities.ALL).getSegments(itemId)

        assertTrue(paths().any { it.startsWith("/MediaSegments/") }, paths().toString())
    }

    @Test
    fun `without media segments there are none and the server is not asked`() = runBlocking {
        val repository = repository(ServerCapabilities.ALL.copy(mediaSegments = false))

        assertEquals(emptyList(), repository.getSegments(itemId))
        assertEquals(emptyList(), repository.getServerSegments(itemId))
        assertEquals(emptyList(), paths())
    }

    @Test
    fun `Jellyfin is asked for trickplay tiles`() = runBlocking {
        repository(ServerCapabilities.ALL).getTrickplayData(itemId, 320, 0)

        assertTrue(paths().any { it.contains("/Trickplay/") }, paths().toString())
    }

    @Test
    fun `without trickplay there are no tiles and the server is not asked`() = runBlocking {
        val repository = repository(ServerCapabilities.ALL.copy(trickplay = false))

        assertNull(repository.getTrickplayData(itemId, 320, 0))
        assertEquals(emptyList(), paths())
    }

    @Test
    fun `without the auto-play setting there is none and the server is not asked`() = runBlocking {
        val repository = repository(ServerCapabilities.ALL.copy(autoPlayNextSetting = false))

        assertNull(repository.autoPlayNextEpisode())
        assertEquals(emptyList(), paths())
    }

    @Test
    fun `without device rename the server is not asked`() = runBlocking {
        repository(ServerCapabilities.ALL.copy(deviceRename = false)).updateDeviceName("Phone")

        assertEquals(emptyList(), paths())
    }

    @Test
    fun `Jellyfin is asked for its resume thresholds with the start report`() = runBlocking {
        repository(ServerCapabilities.ALL).postPlaybackStart(itemId, transcoded = false)

        assertTrue(paths().contains("/System/Configuration"), paths().toString())
    }

    @Test
    fun `without resume thresholds only the start is reported`() = runBlocking {
        repository(ServerCapabilities.ALL.copy(serverResumeThresholds = false))
            .postPlaybackStart(itemId, transcoded = false)

        assertEquals(listOf("/Sessions/Playing"), paths())
    }
}
