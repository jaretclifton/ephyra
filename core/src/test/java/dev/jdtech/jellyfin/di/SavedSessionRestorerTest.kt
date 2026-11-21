package dev.jdtech.jellyfin.di

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.core.session.FakeServerSession
import dev.jdtech.jellyfin.core.session.FakeServerSession.Use
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #763: the session the client starts with, as the Hilt provider of the client set it up before it
 * moved into [SavedSessionRestorer]: the copy in preferences when it is for the current server,
 * else one read of Room, both before [SavedSessionRestorer.restore] returns; then one correction
 * from Room when the copy was behind and nothing changed the session meanwhile, and the copy
 * follows Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SavedSessionRestorerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private val preferences =
        AppPreferences(context.getSharedPreferences("session-restorer", Context.MODE_PRIVATE))
    private val session = FakeServerSession()
    private val restorer = SavedSessionRestorer(preferences, database)

    @After fun close() = room.close()

    private fun storeServer(token: String = TOKEN) = runBlocking {
        database.insertServer(
            Server(
                id = SERVER,
                name = "Home",
                currentServerAddressId = ADDRESS_ID,
                currentUserId = USER_ID,
            )
        )
        database.insertServerAddress(ServerAddress(ADDRESS_ID, SERVER, ADDRESS))
        database.insertUser(
            User(id = USER_ID, name = "User", serverId = SERVER, accessToken = token)
        )
        preferences.setValue(preferences.currentServer, SERVER)
    }

    /** Waits for the mirror, which runs on its own scope. */
    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the copy in preferences is used before restore returns`() {
        storeServer()
        preferences.saveApiSession(ApiSession(SERVER, ADDRESS, TOKEN, USER_ID))

        restorer.restore(session)

        assertEquals(Use(ADDRESS, TOKEN, USER_ID), session.uses.first())
        // Room says the same: nothing to correct.
        Thread.sleep(200)
        assertEquals(1, session.uses.size)
    }

    @Test
    fun `without a copy for this server Room is read before restore returns`() {
        storeServer()
        preferences.saveApiSession(ApiSession("other-server", "http://other", "x", null))

        restorer.restore(session)

        assertEquals(listOf(Use(ADDRESS, TOKEN, USER_ID)), session.uses.toList())
        eventually { preferences.savedApiSession()?.serverId == SERVER }
        assertEquals(ApiSession(SERVER, ADDRESS, TOKEN, USER_ID), preferences.savedApiSession())
    }

    @Test
    fun `a copy that is behind is corrected once from Room`() {
        storeServer(token = "new-token")
        preferences.saveApiSession(ApiSession(SERVER, ADDRESS, TOKEN, USER_ID))

        restorer.restore(session)

        assertEquals(Use(ADDRESS, TOKEN, USER_ID), session.uses.first())
        eventually { session.uses.size == 2 }
        assertEquals(Use(ADDRESS, "new-token", USER_ID), session.uses[1])
        eventually { preferences.savedApiSession()?.accessToken == "new-token" }
    }

    @Test
    fun `no correction when the session changed after the copy was applied`() {
        storeServer(token = "new-token")
        preferences.saveApiSession(ApiSession(SERVER, ADDRESS, TOKEN, USER_ID))
        // A login or a switch right after the start, before Room was first read.
        session.afterUse = { if (session.uses.size == 1) session.accessToken = "switched" }

        restorer.restore(session)

        eventually { preferences.savedApiSession()?.accessToken == "new-token" }
        assertEquals(listOf(Use(ADDRESS, TOKEN, USER_ID)), session.uses.toList())
    }

    @Test
    fun `no current server, nothing is put on the session and the copy is cleared`() {
        preferences.saveApiSession(ApiSession(SERVER, ADDRESS, TOKEN, USER_ID))

        restorer.restore(session)

        eventually { preferences.savedApiSession() == null }
        assertTrue(session.uses.isEmpty())
    }

    private companion object {
        const val SERVER = "server"
        const val ADDRESS = "http://127.0.0.1:8096"
        const val TOKEN = "token"
        val ADDRESS_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ad")
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
