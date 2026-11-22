package dev.jdtech.jellyfin.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Architecture 0.4 (#738): pins the bodies of the start, progress and stop reports, for a stream
 * that plays directly and for a transcode the player loaded (setPlaySession), and that the stop of
 * a downloaded item writes its Room row. PlaySessionOfPlayingStreamTest covers which session a
 * start names after a quality switch; this covers the three reports end to end.
 *
 * The progress and stop reports write the user data before they report it, so the repository gets
 * an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackReportRequestTest {
    private val itemId = UUID.fromString("8f086228-6a92-020d-0cb7-35d734765afe")
    // Set on the test thread, read on the server thread.
    @Volatile private var answer = TRANSCODE
    private val server = StandInServer { request ->
        if (request.path.endsWith("/PlaybackInfo")) StandInServer.resource(answer) else null
    }
    private val context = StandInServer.context()
    private val preferences = StandInServer.preferences(context)
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database = room.getServerDatabaseDao()
    private val repository by lazy { server.repository(context, database, preferences) }

    @After
    fun stop() {
        server.close()
        room.close()
    }

    /** What a report says; `null` for a field not sent or sent null. */
    private data class Report(
        val path: String,
        val itemId: String?,
        val positionTicks: String?,
        val isPaused: String?,
        val playMethod: String?,
        val playSessionId: String?,
    )

    private fun JsonObject.value(name: String): String? =
        get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun reports(): List<Report> =
        server.requests
            .filter { it.path.startsWith("/Sessions/Playing") }
            .map {
                val body = it.json()
                Report(
                    path = "${it.method} ${it.path}",
                    itemId = body.value("ItemId"),
                    positionTicks = body.value("PositionTicks"),
                    isPaused = body.value("IsPaused"),
                    playMethod = body.value("PlayMethod"),
                    playSessionId = body.value("PlaySessionId"),
                )
            }

    /** Asks for the item's stream and loads it, as the player does, then plays and stops it. */
    private suspend fun playAndStop(answer: String) {
        this.answer = answer
        val source = repository.getPlaybackSources(itemId, StandInServer.LOCAL_PLAYER).single()
        repository.setPlaySession(itemId, source.playSessionId, source.transcoded)
        repository.postPlaybackStart(itemId, source.transcoded)
        repository.postPlaybackProgress(itemId, PROGRESS, isPaused = true, source.transcoded)
        repository.postPlaybackStop(itemId, STOP, playedPercentage = 30)
    }

    @Test
    fun `a stream that plays directly reports DirectPlay without a session`() = runBlocking {
        playAndStop(DIRECT_FILE)

        assertEquals(
            listOf(
                Report("POST /Sessions/Playing", ITEM, null, "false", "DirectPlay", null),
                Report(
                    "POST /Sessions/Playing/Progress",
                    ITEM,
                    "$PROGRESS",
                    "true",
                    "DirectPlay",
                    null,
                ),
                Report("POST /Sessions/Playing/Stopped", ITEM, "$STOP", null, null, null),
            ),
            reports(),
        )
    }

    @Test
    fun `a transcode reports Transcode with its session`() = runBlocking {
        playAndStop(TRANSCODE)

        assertEquals(
            listOf(
                Report("POST /Sessions/Playing", ITEM, null, "false", "Transcode", SESSION),
                Report(
                    "POST /Sessions/Playing/Progress",
                    ITEM,
                    "$PROGRESS",
                    "true",
                    "Transcode",
                    SESSION,
                ),
                // The stop names the session, so the server ends the transcode now.
                Report("POST /Sessions/Playing/Stopped", ITEM, "$STOP", null, null, SESSION),
            ),
            reports(),
        )
    }

    @Test
    fun `the stop of a downloaded item writes its row`() = runBlocking {
        preferences.setValue(preferences.currentServer, SERVER)
        database.insertMovie(
            FindroidMovieDto(
                id = itemId,
                serverId = SERVER,
                name = "Libass Sidecar Movie",
                originalTitle = null,
                overview = "",
                runtimeTicks = RUNTIME,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "",
                productionYear = null,
                endDate = null,
                chapters = null,
            )
        )
        database.insertUserData(
            FindroidUserDataDto(
                userId = StandInServer.USER_ID,
                itemId = itemId,
                played = false,
                favorite = false,
                playbackPositionTicks = 0L,
            )
        )

        repository.postPlaybackStop(itemId, STOP, playedPercentage = 30)

        val row = assertNotNull(database.getUserData(itemId, StandInServer.USER_ID))
        assertEquals(STOP, row.playbackPositionTicks, message = "30 % in: the position is kept")
        assertFalse(row.played)
        assertNotNull(row.lastPlayedDate, message = "the stop dates the play")
        assertFalse(row.toBeSynced, message = "the server got the stop")
        assertEquals(
            listOf(Report("POST /Sessions/Playing/Stopped", ITEM, "$STOP", null, null, null)),
            reports(),
        )
    }

    private companion object {
        const val DIRECT_FILE = "playback/direct-file.json"
        const val TRANSCODE = "playback/transcode.json"
        /** The item id as the reports send it: with dashes, unlike the server's own ids. */
        const val ITEM = "8f086228-6a92-020d-0cb7-35d734765afe"
        const val SESSION = "51c3f794f3da47dcb92c5c42f43f8c88"
        const val SERVER = "server"
        const val RUNTIME = 6_000_000_000L // 10 minutes
        const val PROGRESS = 1_200_000_000L
        const val STOP = 1_800_000_000L // 3 minutes, 30 %
    }
}
