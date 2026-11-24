package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #543: a server renamed on the server is renamed in the app on the next Home load. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelServerNameTest {
    private val stored = Server(id = SERVER_ID.toServerId(), name = "Old name", null, null)
    private val updated = mutableListOf<Server>()
    private var systemInfo: () -> ServerInfo = { info(SERVER_ID, "New name") }

    /** #955: Main and the loads on one test scheduler, so no load outlives its test. */
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the rule renames the same server only`() {
        assertEquals("New name", stored.renamedTo(info(SERVER_ID, "New name"))?.name)
        assertNull(stored.renamedTo(info(SERVER_ID, "Old name")))
        assertNull(stored.renamedTo(info(SERVER_ID, " ")))
        assertNull(stored.renamedTo(info(SERVER_ID, "")))
        assertNull(stored.renamedTo(info("", "New name")))
        assertNull(stored.renamedTo(info("another-server", "New name")))
    }

    @Test
    fun `a Home load stores and shows the new name`() =
        runTest(dispatcher) {
            val state = load()

            assertEquals("New name", state.server?.name)
            assertEquals(listOf(stored.copy(name = "New name")), updated)
        }

    @Test
    fun `an unreachable server keeps the stored name`() =
        runTest(dispatcher) {
            systemInfo = { throw IOException("no connection") }

            val state = load()

            assertEquals("Old name", state.server?.name)
            assertTrue(updated.isEmpty())
        }

    /** A Home load, to its end. */
    private fun TestScope.load(): HomeState {
        val viewModel =
            HomeViewModel(
                repository = repository(),
                appPreferences = AppPreferences(sharedPreferences()),
                serverStore = database(),
                loadDispatcher = dispatcher,
            )
        val load = viewModel.loadData()
        advanceUntilIdle()
        assertTrue(load.isCompleted)
        assertFalse(viewModel.state.value.isLoading)
        return viewModel.state.value
    }

    private fun info(id: String, name: String) = ServerInfo(id.toServerId(), name, null, null)

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "getServerInfo" -> systemInfo()
                "refreshCurrentUserImage" -> Unit
                "getSuggestions",
                "getResumeItems",
                "getNextUp",
                "getUserViews" -> emptyList<Any>()
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun database(): ServerStore =
        fakeProxy(ServerStore::class.java.classLoader, arrayOf(ServerStore::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "server" -> stored
                "currentUser" -> null
                "updateServer" -> updated.add(args!![0] as Server).let { Unit }
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
                else ->
                    throw UnsupportedOperationException("SharedPreferences.${method.kotlinName}")
            }
        }
            as SharedPreferences

    private companion object {
        const val SERVER_ID = "0123456789abcdef0123456789abcdef"
    }
}
