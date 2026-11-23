package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StandInServer
import java.util.Collections
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.api.sockets.SocketConnectionState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #899: the socket `keepAlive` holds while the app switches user or server. There is one socket: it
 * closes and connects again with the new address and token, so the server never sees two sockets
 * from the device, not even for the few seconds the SDK keeps an unused one open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SocketAcrossASwitchTest {
    private val sockets = RecordingSockets()
    private val active = ActiveServerHolder()
    private val api =
        JellyfinApi(StandInServer.context(), activeServer = active, socketConnections = sockets)
    private val session = JellyfinServerSession(api)

    private fun on(address: String, token: String, user: String = USER) =
        ActiveServer(
            type = ServerType.JELLYFIN.value,
            serverId = "a".toServerId(),
            baseUrl = address,
            accessToken = token,
            userId = user.toUserId(),
        )

    /** Keeps the socket open while [block] switches, then lets go. */
    private fun keptAliveWhile(block: suspend () -> Unit) = runBlocking {
        val keeper = launch(Dispatchers.IO) { session.keepAlive().collect {} }
        sockets.awaitConnects(1)
        block()
        keeper.cancel()
    }

    @Test
    fun `a switch of user reconnects the one socket with the new token`() {
        active.switchTo(on(ADDRESS_A, "token-a"))

        keptAliveWhile {
            active.switchTo(on(ADDRESS_A, "token-b", user = OTHER_USER))
            sockets.awaitConnects(2)
        }

        assertEquals(1, sockets.made.size, "Another socket was made")
        assertEquals(listOf("token-a", "token-b"), sockets.connects.map { it.token })
        assertEquals(1, sockets.made.single().disconnects, "The old token stayed connected")
    }

    @Test
    fun `a switch of server leaves no socket on the old one`() {
        active.switchTo(on(ADDRESS_A, "token-a"))

        keptAliveWhile {
            active.switchTo(on(ADDRESS_B, "token-b"))
            sockets.awaitConnects(2)
        }

        assertEquals(1, sockets.made.size, "Another socket was made")
        assertEquals(
            listOf(ADDRESS_A, ADDRESS_B),
            sockets.connects.map { it.url.substringBefore("/socket") },
        )
        assertEquals(1, sockets.made.single().disconnects, "The old server stayed connected")
    }

    @Test
    fun `a change of user alone with the same token keeps the socket`() {
        active.switchTo(on(ADDRESS_A, "token-a"))

        keptAliveWhile {
            active.onSameServer { it.copy(userId = OTHER_USER.toUserId()) }
            // Give a wrong reconnect the time to show.
            repeat(50) { yield() }
            Thread.sleep(200)
        }

        assertEquals(listOf("token-a"), sockets.connects.map { it.token })
    }

    private class Connect(val url: String, val token: String)

    private class RecordingSockets : SocketConnectionFactory {
        val made: MutableList<Connection> = Collections.synchronizedList(mutableListOf())
        val connects: MutableList<Connect> = Collections.synchronizedList(mutableListOf())

        override fun create(
            clientOptions: HttpClientOptions,
            scope: CoroutineScope,
        ): SocketConnection = Connection().also { made += it }

        suspend fun awaitConnects(count: Int) =
            withTimeout(5_000) { while (connects.size < count) delay(10) }

        inner class Connection : SocketConnection {
            private val _state =
                MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected())
            override val state: StateFlow<SocketConnectionState> = _state

            /** Sockets of this connection closed, by [disconnect] or by connecting again. */
            @Volatile var disconnects = 0

            @Volatile private var open = false

            /** As the SDK's OkHttp connection: connecting again first closes the open socket. */
            override suspend fun connect(
                url: String,
                clientName: String,
                clientVersion: String,
                deviceId: String,
                deviceName: String,
                accessToken: String,
                languages: List<String>,
            ): Boolean {
                if (open) disconnect()
                connects += Connect(url, accessToken)
                open = true
                _state.value = SocketConnectionState.Connecting
                return true
            }

            override suspend fun send(message: String): Boolean = true

            override suspend fun disconnect() {
                if (!open) return
                open = false
                disconnects++
                _state.value = SocketConnectionState.Disconnected()
            }
        }
    }

    private companion object {
        const val ADDRESS_A = "http://a.test"
        const val ADDRESS_B = "http://b.test"
        const val USER = "00000000-0000-0000-0000-000000000001"
        const val OTHER_USER = "00000000-0000-0000-0000-000000000002"
    }
}
