package dev.jdtech.jellyfin.core.session

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.jdtech.jellyfin.database.RoomServerStore
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ServerAddressEntity
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerEntity
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast

/**
 * #763: what [ServerSessionKeeper] decides after a 401, now that it asks its [ServerSession]
 * session instead of the SDK client: only a refused token that is still the current one is checked,
 * at most once a minute after it was found valid, and only "not valid" signs the user out, never a
 * failed check; the sign-out only happens while the token is still the current one in Room and on
 * the client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerSessionKeeperTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val room =
        Room.inMemoryDatabaseBuilder(context, ServerDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val database: ServerDatabaseDao = room.getServerDatabaseDao()
    private val preferences =
        AppPreferences(context.getSharedPreferences("session-keeper", Context.MODE_PRIVATE))
    private val session = FakeServerSession(ADDRESS, TOKEN, USER_ID)
    private var capabilities = ServerCapabilities.ALL
    private val keeper =
        ServerSessionKeeper(
            context,
            session,
            preferences,
            RoomServerStore(database),
            { capabilities },
            session.active,
        )

    /**
     * Runs [ServerSessionKeeper.onUnauthorized] off the main thread, as the keeper's scope does,
     * while the main looper runs the toast. Returns what it threw.
     */
    private fun unauthorized(token: String): Throwable? {
        val run = CoroutineScope(Dispatchers.IO).async { keeper.onUnauthorized(token) }
        val until = System.currentTimeMillis() + 5_000
        while (!run.isCompleted) {
            check(System.currentTimeMillis() < until) { "onUnauthorized did not end" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return run.getCompletionExceptionOrNull()
    }

    @Before
    fun setUp() = runBlocking {
        // The clock of a device that has been on for a while: the first check is not held back.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        preferences.setValue(preferences.currentServer, SERVER)
        database.insertServer(
            ServerEntity(
                id = SERVER.toServerId(),
                name = "Home",
                currentServerAddressId = ADDRESS_ID,
                currentUserId = USER_ID,
            )
        )
        database.insertServerAddress(ServerAddressEntity(ADDRESS_ID, SERVER.toServerId(), ADDRESS))
        database.insertUser(
            UserEntity(
                id = USER_ID,
                name = "User",
                serverId = SERVER.toServerId(),
                accessToken = TOKEN,
            )
        )
    }

    @After fun tearDown() = room.close()

    private suspend fun assertSignedIn() {
        assertNotNull(database.getServerCurrentUser(SERVER.toServerId()))
        assertTrue(session.uses.isEmpty())
        assertEquals(TOKEN, session.accessToken)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `a revoked current token signs the user out, keeping the address`() = runBlocking {
        session.tokenCheck = { false }

        unauthorized(TOKEN)

        assertEquals(1, session.tokenChecks)
        assertNull(database.getServerCurrentUser(SERVER.toServerId()))
        assertNull(database.getUser(USER_ID))
        assertEquals(listOf(FakeServerSession.Use(ADDRESS, null, null)), session.uses)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "Home signed you out. Sign in again to continue.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a refused token that is no longer the current one is not checked`() = runBlocking {
        session.tokenCheck = { false }

        unauthorized("old-token")

        assertEquals(0, session.tokenChecks)
        assertSignedIn()
    }

    @Test
    fun `a valid token is not checked again within a minute`() = runBlocking {
        unauthorized(TOKEN)
        assertEquals(1, session.tokenChecks)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(59))
        unauthorized(TOKEN)
        assertEquals(1, session.tokenChecks)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        session.tokenCheck = { false }
        unauthorized(TOKEN)
        assertEquals(2, session.tokenChecks)
        assertNull(database.getServerCurrentUser(SERVER.toServerId()))
    }

    @Test
    fun `a check that cannot say signs nobody out, and counts as confirmed`() = runBlocking {
        // As before: offline or a server error is "not revoked", which holds the next check back.
        session.tokenCheck = { throw ServerException.Unreachable() }

        unauthorized(TOKEN)
        assertEquals(1, session.tokenChecks)
        assertSignedIn()

        session.tokenCheck = { throw ServerException.HttpStatus(500) }
        unauthorized(TOKEN)
        assertEquals(1, session.tokenChecks)

        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        unauthorized(TOKEN)
        assertEquals(2, session.tokenChecks)
        assertSignedIn()
    }

    @Test
    fun `any other failure of the check signs nobody out either`() = runBlocking {
        session.tokenCheck = { throw IllegalStateException("Expected BEGIN_OBJECT") }

        unauthorized(TOKEN)

        assertSignedIn()
    }

    @Test
    fun `no sign-out when Room has another current user meanwhile`() = runBlocking {
        val other = "00000000-0000-0000-0000-000000000002".toUserId()
        database.insertUser(
            UserEntity(
                id = other,
                name = "Other",
                serverId = SERVER.toServerId(),
                accessToken = "other-token",
            )
        )
        database.updateServerCurrentUser(SERVER.toServerId(), other)
        session.tokenCheck = { false }

        unauthorized(TOKEN)

        assertEquals(1, session.tokenChecks)
        assertEquals(other, database.getServerCurrentUser(SERVER.toServerId())?.id)
        assertNotNull(database.getUser(USER_ID))
        assertTrue(session.uses.isEmpty())
    }

    @Test
    fun `no sign-out when the client has another token meanwhile`() = runBlocking {
        session.tokenCheck = {
            // A profile switch while the server was asked.
            session.accessToken = "other-token"
            false
        }

        unauthorized(TOKEN)

        assertEquals(1, session.tokenChecks)
        assertNotNull(database.getServerCurrentUser(SERVER.toServerId()))
        assertTrue(session.uses.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `no sign-out without a current server`() = runBlocking {
        preferences.setValue(preferences.currentServer, null)
        session.tokenCheck = { false }

        unauthorized(TOKEN)

        assertNotNull(database.getServerCurrentUser(SERVER.toServerId()))
        assertTrue(session.uses.isEmpty())
    }

    @Test
    fun `cancellation is not swallowed`() {
        session.tokenCheck = { throw CancellationException("scope gone") }

        assertIs<CancellationException>(unauthorized(TOKEN))
        runBlocking { assertSignedIn() }
    }

    @Test
    fun `start follows the tokens the session reports as refused`() = runBlocking {
        session.tokenCheck = { false }
        keeper.start()

        val until = System.currentTimeMillis() + 5_000
        // Until the sign-out is through: the current user is cleared before the session is told,
        // so an empty current user alone does not mean the session was switched yet.
        while (session.uses.isEmpty()) {
            check(System.currentTimeMillis() < until) { "Not signed out" }
            // Again until the keeper's collector, which starts on its own scope, is there.
            if (database.getServerCurrentUser(SERVER.toServerId()) != null) {
                session.unauthorized.tryEmit(TOKEN)
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNull(database.getServerCurrentUser(SERVER.toServerId()))
        assertEquals(listOf(FakeServerSession.Use(ADDRESS, null, null)), session.uses)
    }

    @Test
    fun `the socket is kept open while the app is in the foreground`() {
        val process = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        keeper.start()
        assertEquals(0, session.keepingAlive.get())

        process.currentState = Lifecycle.State.STARTED
        waitFor { session.keepingAlive.get() == 1 }

        process.currentState = Lifecycle.State.CREATED
        waitFor { session.keepingAlive.get() == 0 }
    }

    @Test
    fun `#769 a server without the session socket gets none in the foreground`() {
        capabilities = ServerCapabilities.ALL.copy(sessionSocket = false)
        val process = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        keeper.start()

        process.currentState = Lifecycle.State.STARTED
        // Long enough for a socket to have been opened, as in the test above.
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, session.keepingAlive.get())

        // The next return to the foreground asks again: a server that has it gets it.
        process.currentState = Lifecycle.State.CREATED
        capabilities = ServerCapabilities.ALL
        process.currentState = Lifecycle.State.STARTED
        waitFor { session.keepingAlive.get() == 1 }

        process.currentState = Lifecycle.State.CREATED
        waitFor { session.keepingAlive.get() == 0 }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SERVER = "server"
        const val ADDRESS = "http://127.0.0.1:8096"
        const val TOKEN = "token"
        val ADDRESS_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ad")
        val USER_ID: UserId = "00000000-0000-0000-0000-000000000001".toUserId()
    }
}
