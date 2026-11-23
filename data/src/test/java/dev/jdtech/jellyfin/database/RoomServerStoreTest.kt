package dev.jdtech.jellyfin.database

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #899: [RoomServerStore] over a real Room database. What a caller writes through the store comes
 * back the same, field by field, so the mappers between the domain models and the Room rows lose
 * nothing; and the observing methods follow later writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomServerStoreTest {
    private val room =
        Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                ServerDatabase::class.java,
            )
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val store = RoomServerStore(room.getServerDatabaseDao())

    @After fun close() = room.close()

    private val serverId = "server-1".toServerId()
    private val address = ServerAddress(UUID.randomUUID(), serverId, "http://10.0.0.2:8096")
    private val other = ServerAddress(UUID.randomUUID(), serverId, "https://media.example.org")

    /** Every field away from its default, so a mapper that drops one is seen. */
    private val user =
        User(
            id = "00000000-0000-0000-0000-00000000000a".toUserId(),
            name = "Alice",
            serverId = serverId,
            accessToken = "token-a",
            primaryImageTag = "tag-a",
            pinHash = "hash-a",
            pinSalt = "salt-a",
            pinOnStart = false,
            lockType = ProfileLockType.PATTERN.value,
        )
    private val bob =
        User(
            id = "00000000-0000-0000-0000-00000000000b".toUserId(),
            name = "Bob",
            serverId = serverId,
        )
    private val server =
        Server(
            id = serverId,
            name = "Home",
            currentServerAddressId = address.id,
            currentUserId = user.id,
            type = ServerType.EMBY.value,
        )

    private suspend fun save() {
        store.insertServer(server)
        store.insertAddress(address)
        store.insertAddress(other)
        store.insertUser(user)
        store.insertUser(bob)
    }

    @Test
    fun `every field of a server, its addresses and its users comes back`() = runBlocking {
        save()

        assertEquals(server, store.server(serverId))
        assertEquals(address, store.address(address.id))
        assertEquals(address, store.currentAddress(serverId))
        assertEquals(user, store.user(user.id))
        assertEquals(user, store.currentUser(serverId))
        assertEquals(setOf(user, bob), store.users(serverId).toSet())
        assertEquals(
            ServerWithAddressAndUser(server, address, user),
            store.serverWithAddressAndUser(serverId),
        )
        val withAddresses = store.serverWithAddresses(serverId)
        assertEquals(ServerWithAddresses(server, withAddresses.addresses, user), withAddresses)
        assertEquals(setOf(address, other), withAddresses.addresses.toSet())
        assertEquals(listOf(withAddresses), store.serversWithAddresses())
    }

    @Test
    fun `an update keeps the current address and user, and the user calls change one column`() =
        runBlocking {
            save()

            store.updateServer(server.copy(name = "Renamed"))
            assertEquals(server.copy(name = "Renamed"), store.server(serverId))

            store.updateServer(server.copy(currentServerAddressId = other.id))
            assertEquals(other, store.currentAddress(serverId))
            assertEquals(user.id, store.server(serverId)?.currentUserId)

            store.setCurrentUser(serverId, bob.id)
            assertEquals(bob, store.currentUser(serverId))
            assertEquals(other.id, store.server(serverId)?.currentServerAddressId)

            store.setUserImageTag(user.id, "tag-b")
            store.setUserPinOnStart(user.id, true)
            assertEquals(
                user.copy(primaryImageTag = "tag-b", pinOnStart = true),
                store.user(user.id),
            )

            store.setUserPin(user.id, "hash-pin", "salt-pin")
            assertEquals(
                user.copy(
                    primaryImageTag = "tag-b",
                    pinOnStart = true,
                    pinHash = "hash-pin",
                    pinSalt = "salt-pin",
                    lockType = ProfileLockType.PIN.value,
                ),
                store.user(user.id),
            )
        }

    @Test
    fun `clearing the current user leaves the server without one`() = runBlocking {
        save()

        store.clearCurrentUser(serverId)

        assertNull(store.currentUser(serverId))
        assertNull(store.server(serverId)?.currentUserId)
        assertEquals(user, store.user(user.id))
        assertNull(store.serverWithAddressAndUser(serverId)?.user)
    }

    @Test
    fun `the observing methods emit again after a write`() = runBlocking {
        save()

        val sessions = twoValues(store.observeServerWithAddressAndUser(serverId))
        val users = twoValues(store.observeCurrentUser(serverId))
        val ids = twoValues(store.observeCurrentUserId(serverId))
        // Each has its first value before the write, so the second can only come from the write.
        withTimeout(5_000) { listOf(sessions, users, ids).forEach { it.first.await() } }

        store.setCurrentUser(serverId, bob.id)

        withTimeout(5_000) {
            assertEquals(
                listOf(
                    ServerWithAddressAndUser(server, address, user),
                    ServerWithAddressAndUser(server.copy(currentUserId = bob.id), address, bob),
                ),
                sessions.all.await(),
            )
            assertEquals(listOf(user, bob), users.all.await())
            assertEquals(listOf(user.id, bob.id), ids.all.await())
        }
    }

    private class TwoValues<T>(val first: CompletableDeferred<Unit>, val all: Deferred<List<T>>)

    /**
     * The first two values of [flow], collected on another thread; [TwoValues.first] says when one
     * came.
     */
    private fun <T> CoroutineScope.twoValues(flow: Flow<T>): TwoValues<T> {
        val first = CompletableDeferred<Unit>()
        val all = async(Dispatchers.IO) { flow.onEach { first.complete(Unit) }.take(2).toList() }
        return TwoValues(first, all)
    }
}
