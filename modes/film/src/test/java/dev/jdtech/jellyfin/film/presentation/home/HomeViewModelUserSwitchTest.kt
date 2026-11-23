package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.PlaybackEvents
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #810: Home stays on the back stack while a user is added (by password or Quick Connect) or picked
 * on the Users screen. Coming back less than a minute after a load, which is always the case right
 * after the player, Home kept the previous user's rows and avatar until the app restarted.
 *
 * The fakes stand for what each flow writes in `SetupRepositoryImpl`: a login (password and Quick
 * Connect both end in `saveAuthenticationResult`) inserts the user and sets the server's current
 * user; the Users screen (`setCurrentUser`) updates the server row. Both switch the client
 * (`session.use`) only after those writes, so the repository answers for the new user from a moment
 * after Room has the new user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelUserSwitchTest {
    private val alice =
        User(
            id = UUID.randomUUID().toString().toUserId(),
            name = "Alice",
            serverId = SERVER_ID.toServerId(),
        )
    private val bob =
        User(
            id = UUID.randomUUID().toString().toUserId(),
            name = "Bob",
            serverId = SERVER_ID.toServerId(),
            primaryImageTag = "b",
        )
    private val aliceItem = item("Alice's movie")
    private val bobItem = item("Bob's movie")

    private var server = Server(SERVER_ID.toServerId(), "Server", null, alice.id)
    private val users = mutableMapOf(alice.id to alice)
    private val currentUser = MutableStateFlow<User?>(alice)

    /** The user the client sends requests for; null when nobody is signed in. */
    @Volatile private var sessionUser: User? = alice

    /** Whom each request for rows was sent as. */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Set to hold the next requests for [bob] until it opens. */
    @Volatile private var bobGate: Gate? = null

    /** Set to hold the next requests for [alice] until it opens. */
    @Volatile private var aliceGate: Gate? = null

    /**
     * Requests that wait for the test, suspended: they hold no thread, so a few held requests
     * cannot keep the next load off `Dispatchers.Default`.
     */
    private class Gate {
        private val waiting = mutableListOf<() -> Unit>()
        private var opened = false

        /** False when the gate is open already: the request answers at once. */
        @Synchronized
        fun hold(answer: () -> Unit): Boolean {
            if (opened) return false
            waiting += answer
            return true
        }

        @get:Synchronized
        val held: Int
            get() = waiting.size

        fun open() {
            val answers =
                synchronized(this) {
                    opened = true
                    waiting.toList().also { waiting.clear() }
                }
            answers.forEach { it() }
        }
    }

    private val dataChanges = DataChangeEvents()
    private val playbackEvents = PlaybackEvents(dataChanges)

    private lateinit var viewModel: HomeViewModel

    @BeforeEach
    fun setUp() {
        // Unconfined: Home hears of a change to the current user as Room emits it.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel =
            HomeViewModel(
                repository = repository(),
                appPreferences = AppPreferences(sharedPreferences()),
                serverStore = database(),
                dataChanges = dataChanges,
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `adding a user by password shows their Home`() = switchUser(::login, playerFirst = false)

    @Test
    fun `adding a user by password after the player shows their Home`() =
        switchUser(::login, playerFirst = true)

    @Test
    fun `adding a user by Quick Connect shows their Home`() =
        switchUser(::quickConnect, playerFirst = false)

    @Test
    fun `adding a user by Quick Connect after the player shows their Home`() =
        switchUser(::quickConnect, playerFirst = true)

    @Test
    fun `switching user on the Users screen shows their Home`() {
        users[bob.id] = bob
        switchUser(::pickOnUsersScreen, playerFirst = false)
    }

    @Test
    fun `switching user on the Users screen after the player shows their Home`() {
        users[bob.id] = bob
        switchUser(::pickOnUsersScreen, playerFirst = true)
    }

    @Test
    fun `a load for the previous user that answers after the switch leaves nothing behind`() {
        homeShowsAlice()

        val gate = Gate()
        aliceGate = gate
        val aliceLoad = viewModel.loadData(silent = true)
        // The load is waiting for the server, for Alice's Continue Watching and Next Up.
        waitUntil { gate.held == 2 }
        users[bob.id] = bob
        pickOnUsersScreen(bob)
        val bobLoaded = waitFor {
            it.user?.id == bob.id && it.resumeSection != null && !it.isLoading
        }
        assertEquals(listOf(bobItem), bobLoaded.resumeSection?.homeSection?.items)
        // Alice's answers are still due, after Bob's rows: now the server gives them.
        assertFalse(aliceLoad.isCompleted)
        aliceGate = null
        gate.open()
        runBlocking { withTimeout(5_000) { aliceLoad.join() } }

        val state = settle { it.resumeSection == null }
        assertEquals(bob, state.user)
        assertEquals(listOf(bobItem), state.resumeSection?.homeSection?.items)
        assertEquals(listOf(bobItem), state.nextUpSection?.homeSection?.items)
    }

    @Test
    fun `the new user's load waits until the client acts for them`() {
        homeShowsAlice()
        requests.clear()

        // A login has written Room; the client still acts for Alice.
        loginRoomOnly(bob)
        val between = waitFor { it.user?.id == bob.id }
        assertTrue(between.isEmpty)
        // Long enough for a load that does not wait to have asked the server as Alice.
        Thread.sleep(300)
        assertEquals(emptyList(), requests, "Home asked the server as the previous user")

        sessionUser = bob
        val state = settle { it.resumeSection == null || it.nextUpSection == null }
        assertEquals(bob, state.user)
        assertEquals(listOf(bobItem), state.resumeSection?.homeSection?.items)
        assertEquals(listOf(bobItem), state.nextUpSection?.homeSection?.items)
        assertTrue(requests.all { it == bob.name }, "requests: $requests")
    }

    @Test
    fun `a client that never switches leaves Home empty, not waiting for good`() {
        homeShowsAlice()
        requests.clear()

        loginRoomOnly(bob)
        // The load gives up after its few seconds.
        val state = runBlocking {
            withTimeout(10_000) {
                while (viewModel.state.value.isLoading || viewModel.state.value.user == alice) {
                    delay(50)
                }
                viewModel.state.value
            }
        }

        assertEquals(bob.id, state.user?.id)
        assertTrue(state.isEmpty)
        assertEquals(emptyList(), requests)
        // Not counted as loaded: coming back loads again, once the given-up load has ended.
        waitUntil { viewModel.shouldReload(stopEventFollows = true) }
    }

    @Test
    fun `signing the user out leaves no rows`() {
        homeShowsAlice()

        server = server.copy(currentUserId = null)
        currentUser.value = null

        val state = waitFor { it.user == null }
        assertNull(state.user)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `the same user written again changes nothing`() {
        val before = homeShowsAlice()

        currentUser.value = alice.copy(primaryImageTag = "new")

        assertEquals(before, viewModel.state.value)
        assertFalse(viewModel.shouldReload(stopEventFollows = true))
    }

    @Test
    fun `the screen shows nothing of a user that is not the signed in one`() {
        val shown = homeShowsAlice()

        val forBob = shown.shownTo(bob.id)
        //
        assertNull(forBob.user)
        assertTrue(forBob.isEmpty)
        assertTrue(forBob.isLoading)
        assertEquals(shown.server, forBob.server)
        assertEquals(shown, shown.shownTo(alice.id))
        // No session at hand (previews): everything shows.
        assertEquals(shown, shown.shownTo(null))
    }

    private fun switchUser(flow: (User) -> Unit, playerFirst: Boolean) {
        homeShowsAlice()
        if (playerFirst) {
            // The player opens over Home, and its stop reloads Home (ReloadOnResume).
            playbackEvents.onPlaying(aliceItemId)
            playbackEvents.onPlaying(null)
            playbackEvents.onStopped(aliceItemId)
            runBlocking { withTimeout(5_000) { viewModel.loadData(silent = true).join() } }
        }

        // Home is under the Login or Users screen now. Bob's rows wait for the test.
        val gate = Gate()
        bobGate = gate
        flow(bob)

        // Between the switch and Bob's rows: Bob's avatar, none of Alice's rows.
        val between = waitFor { it.user?.id == bob.id }
        assertTrue(between.isEmpty, "Alice's rows are still there: $between")

        // Home comes back into view (ON_RESUME); it must not load a second time.
        if (viewModel.shouldReload(stopEventFollows = true)) viewModel.loadData(silent = true)
        bobGate = null
        gate.open()

        val state = settle { it.resumeSection == null || it.nextUpSection == null }
        assertEquals(bob, state.user)
        assertEquals(listOf(bobItem), state.resumeSection?.homeSection?.items)
        assertEquals(listOf(bobItem), state.nextUpSection?.homeSection?.items)
        assertNull(state.error)
        assertFalse(viewModel.shouldReload(stopEventFollows = true))
    }

    private fun homeShowsAlice(): HomeState {
        runBlocking { withTimeout(5_000) { viewModel.loadData().join() } }
        val state = viewModel.state.value
        assertEquals(alice, state.user)
        assertEquals(listOf(aliceItem), state.resumeSection?.homeSection?.items)
        return state
    }

    /** `saveAuthenticationResult`, after a login with a password. */
    private fun login(user: User) {
        loginRoomOnly(user)
        sessionUser = user
    }

    /** What `saveAuthenticationResult` writes before `session.use`. */
    private fun loginRoomOnly(user: User) {
        users[user.id] = user
        server = server.copy(currentUserId = user.id)
        currentUser.value = user
    }

    /** `saveAuthenticationResult` too, after Quick Connect: the same writes. */
    private fun quickConnect(user: User) = login(user)

    /** `setCurrentUser`. */
    private fun pickOnUsersScreen(user: User) {
        server = server.copy(currentUserId = user.id)
        currentUser.value = users.getValue(user.id)
        sessionUser = user
    }

    /** The first state that is [done], within a few seconds; fails with the last one otherwise. */
    private fun waitFor(done: (HomeState) -> Boolean): HomeState = runBlocking {
        val found =
            withTimeoutOrNull(5_000) {
                while (!done(viewModel.state.value)) delay(10)
                viewModel.state.value
            }
        found ?: throw AssertionError("Home never got there: ${viewModel.state.value}")
    }

    /** loadData() runs on Dispatchers.Default; wait until it has finished. */
    private fun settle(waiting: (HomeState) -> Boolean): HomeState = runBlocking {
        withTimeout(5_000) {
            while (with(viewModel.state.value) { isLoading || waiting(this) }) delay(10)
            viewModel.state.value
        }
    }

    /** Polls [done] until it holds, within a few seconds. */
    private fun waitUntil(done: () -> Boolean) = runBlocking {
        withTimeoutOrNull(5_000) { while (!done()) delay(10) }
            ?: throw AssertionError("never happened")
    }

    /**
     * The rows of the client's user, as a suspend function's proxy answers: at once, or
     * [COROUTINE_SUSPENDED] while a [Gate] holds them.
     */
    private fun rowsOf(user: User?, continuation: Continuation<Any?>): Any? {
        requests += user?.name ?: "nobody"
        if (user == null) throw IOException("signed out")
        val items = listOf(if (user.id == bob.id) bobItem else aliceItem)
        val gate = if (user.id == bob.id) bobGate else aliceGate
        val resume = continuation.intercepted()
        return if (gate?.hold { resume.resumeWith(Result.success(items)) } == true) {
            COROUTINE_SUSPENDED
        } else {
            items
        }
    }

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "getResumeItems",
                "getNextUp" ->
                    @Suppress("UNCHECKED_CAST")
                    rowsOf(sessionUser, args!!.last() as Continuation<Any?>)
                "getSuggestions",
                "getUserViews" -> emptyList<Any>()
                "refreshCurrentUserImage" -> Unit
                "getUserId" -> sessionUser?.id ?: throw IllegalStateException("signed out")
                "getServerInfo" -> throw IOException("not needed here")
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun database(): ServerStore =
        fakeProxy(ServerStore::class.java.classLoader, arrayOf(ServerStore::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "server" -> server.copy()
                "currentUser" -> server.currentUserId?.let { users[it] }
                "observeCurrentUser" -> currentUser
                else -> throw NotImplementedError("ServerStore.${method.kotlinName}")
            }
        }
            as ServerStore

    private fun sharedPreferences(): SharedPreferences =
        fakeProxy(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getString" -> if (args!![0] == "pref_current_server") SERVER_ID else args[1]
                "getBoolean",
                "getInt",
                "getLong" -> args!![1]
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences

    private fun item(name: String): Item =
        fakeProxy(Item::class.java.classLoader, arrayOf(Item::class.java)) { proxy, method, args ->
            when (method.kotlinName) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> throw NotImplementedError("Item.${method.kotlinName}")
            }
        }
            as Item

    private companion object {
        const val SERVER_ID = "0123456789abcdef0123456789abcdef"
        val aliceItemId: ItemId = UUID.randomUUID().toString().toItemId()
    }
}
