package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ServerUserData
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StandInServer
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * #762: the requests of the user data sync, as `SyncWorker` made them with the SDK before they
 * moved into [JellyfinUserDataSync]: the reads in batches of 100, the two writes, the stop of a
 * session left open, and the question which item this device still plays, asked only of the app's
 * own client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinUserDataSyncRequestTest {
    // Set on the test thread, read on the server thread.
    @Volatile private var items = ""
    @Volatile private var sessions = "[]"
    private val server = StandInServer { request ->
        when {
            request.path == "/Items" -> items
            request.path == "/Sessions" -> sessions
            // The server answers a write with the user data it now has.
            request.path.endsWith("/UserData") -> USER_DATA_ANSWER
            else -> null
        }
    }
    private val api =
        JellyfinApi(StandInServer.context()).apply {
            api.update(baseUrl = server.baseUrl, accessToken = TOKEN)
            userId = USER.toUuid()
        }
    private val ports = JellyfinUserDataSyncPorts(api)
    private val port by lazy { ports.forUser(server.baseUrl, TOKEN, USER) }

    @After fun stop() = server.close()

    private fun JsonObject.value(name: String): String? =
        get(name)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun itemsAnswer(vararg ids: ItemId) =
        ids.joinToString(
            prefix = "{\"Items\":[",
            postfix = "],\"TotalRecordCount\":${ids.size},\"StartIndex\":0}",
        ) {
            "{\"Id\":\"${it.asString()}\",\"Type\":\"Episode\",\"UserData\":{\"PlaybackPositionTicks\":1200," +
                "\"PlayCount\":1,\"IsFavorite\":true,\"Played\":false,\"Key\":\"${it.asString()}\"," +
                "\"ItemId\":\"${it.asString()}\",\"LastPlayedDate\":\"2026-11-20T20:15:00.0000000Z\"}}"
        }

    @Test
    fun `the server's user data is read 100 items per request`() = runBlocking {
        val ids = (1..150).map { UUID(0L, it.toLong()).toItemId() }
        items = itemsAnswer(ids.first())

        val read = port.userDataOf(ids)

        val reads = server.requests.filter { it.path == "/Items" }
        assertEquals(listOf("GET", "GET"), reads.map { it.method })
        assertEquals(
            listOf(ids.take(100), ids.drop(100)).map { batch -> batch.map { it.asString() } },
            reads.map { queryAll(it.decodedTarget, "ids") },
        )
        for (request in reads) {
            assertEquals(USER.asString(), query(request.decodedTarget, "userId"))
            assertEquals("true", query(request.decodedTarget, "enableUserData"))
        }
        assertEquals(setOf(ids.first()), read.keys)
        val first = read.getValue(ids.first())
        assertEquals(1200L, first.playbackPositionTicks)
        assertEquals(true, first.favorite)
        assertEquals(false, first.played)
    }

    @Test
    fun `the user data is written with the position, the favourite, the played state and the date`() =
        runBlocking {
            port.putUserData(
                ServerUserData(
                    itemId = ITEM,
                    played = true,
                    favorite = true,
                    playbackPositionTicks = 1_800_000_000L,
                    lastPlayedDate = LocalDateTime.of(2026, 11, 20, 20, 15, 0),
                )
            )

            val write = server.requests.single()
            assertEquals(
                "POST /UserItems/${ITEM.asString()}/UserData",
                "${write.method} ${write.path}",
            )
            assertEquals(USER.asString(), query(write.decodedTarget, "userId"))
            val body = write.json()
            assertEquals("1800000000", body.value("PlaybackPositionTicks"))
            assertEquals("true", body.value("IsFavorite"))
            assertEquals("true", body.value("Played"))
            // The local date-time as given, with the device's offset after it.
            val date = body.value("LastPlayedDate").orEmpty()
            assertTrue(date.startsWith("2026-11-20T20:15:00"), date)
        }

    @Test
    fun `a write without a date leaves the server's date alone`() = runBlocking {
        port.putUserData(
            ServerUserData(
                itemId = ITEM,
                played = false,
                favorite = false,
                playbackPositionTicks = 0L,
                lastPlayedDate = null,
            )
        )

        assertNull(server.requests.single().json().value("LastPlayedDate"))
    }

    @Test
    fun `a favourite is written on its own`() = runBlocking {
        port.setFavorite(ITEM, favorite = true)

        val write = server.requests.single()
        assertEquals("POST /UserItems/${ITEM.asString()}/UserData", "${write.method} ${write.path}")
        val body = write.json()
        assertEquals("true", body.value("IsFavorite"))
        assertNull(body.value("PlaybackPositionTicks"))
        assertNull(body.value("Played"))
        assertNull(body.value("LastPlayedDate"))
    }

    @Test
    fun `a session left open is stopped at the given position`() = runBlocking {
        port.reportStopped(ITEM, positionTicks = 1_800_000_000L)

        val stop = server.requests.single()
        assertEquals("POST /Sessions/Playing/Stopped", "${stop.method} ${stop.path}")
        val body = stop.json()
        assertEquals(ITEM.asString(), body.value("ItemId"))
        assertEquals("1800000000", body.value("PositionTicks"))
        assertEquals("false", body.value("Failed"))
    }

    @Test
    fun `the item this device still plays is the one of its session for this user`() = runBlocking {
        val other = "00000000-0000-0000-0000-0000000000b2".toUserId()
        sessions =
            "[${session(user = other, item = UUID(0L, 9L).toItemId())},${session(user = USER, item = ITEM)}]"

        assertEquals(ITEM, port.itemLeftPlaying())

        val ask = server.requests.single()
        assertEquals("GET /Sessions", "${ask.method} ${ask.path}")
        assertEquals(api.api.deviceInfo.id, query(ask.decodedTarget, "deviceId"))
    }

    @Test
    fun `another saved user is not asked for a session`() = runBlocking {
        val other = "00000000-0000-0000-0000-0000000000b2".toUserId()
        val otherPort = ports.forUser(server.baseUrl, "other-token", other)

        assertNull(otherPort.itemLeftPlaying())
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun `another token for the same user is another client`() = runBlocking {
        // A login renewed on another device: the app's client keeps its own.
        val renewed = ports.forUser(server.baseUrl, "renewed-token", USER)

        assertNull(renewed.itemLeftPlaying())
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun `a server that is not reached is thrown as a ServerException without a status`() =
        runBlocking {
            // An error status becomes HttpStatus through asServerException, see SdkErrorsTest. The
            // sync reads anything else as the server not being reached.
            val gone = StandInServer().also { it.close() }
            val away = ports.forUser(gone.baseUrl, TOKEN, USER)

            val error = assertFailsWith<ServerException> { away.reportStopped(ITEM, 0L) }
            assertFalse(error is ServerException.HttpStatus)
        }

    @Test
    fun `an answer the client cannot read is thrown as Unreachable`() = runBlocking {
        // As before the port, such a failure has no status, so the sync does not treat it as one.
        items = "{}"

        assertFailsWith<ServerException.Unreachable> { port.userDataOf(listOf(ITEM)) }
        Unit
    }

    private fun session(user: UserId, item: ItemId) =
        "{\"UserId\":\"${user.asString()}\",\"NowPlayingItem\":{\"Id\":\"${item.asString()}\",\"Type\":\"Episode\"}," +
            "\"IsActive\":true,\"SupportsMediaControl\":false,\"SupportsRemoteControl\":false," +
            "\"HasCustomDeviceName\":false,\"PlayableMediaTypes\":[]," +
            "\"LastActivityDate\":\"2026-11-20T20:15:00.0000000Z\"," +
            "\"LastPlaybackCheckIn\":\"2026-11-20T20:15:00.0000000Z\",\"SupportedCommands\":[]}"

    /** The value of query parameter [name] in [target], as sent. */
    private fun query(target: String, name: String): String? = queryAll(target, name).firstOrNull()

    /** Every value of query parameter [name] in [target], in the order sent. */
    private fun queryAll(target: String, name: String): List<String> =
        target
            .substringAfter('?', "")
            .split('&')
            .filter { it.startsWith("$name=") }
            .map { it.substringAfter('=') }

    private companion object {
        const val TOKEN = "token"
        val USER: UserId = StandInServer.USER_ID
        val ITEM: ItemId = "8f086228-6a92-020d-0cb7-35d734765afe".toItemId()
        const val USER_DATA_ANSWER =
            "{\"PlaybackPositionTicks\":0,\"PlayCount\":0,\"IsFavorite\":false,\"Played\":false," +
                "\"Key\":\"key\",\"ItemId\":\"8f0862286a92020d0cb735d734765afe\"}"
    }
}
