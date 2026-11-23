package dev.jdtech.jellyfin.film.presentation.home

import android.content.SharedPreferences
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #543: a server renamed on the server is renamed in the app on the next Home load. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelServerNameTest {
    private val stored = Server(id = SERVER_ID, name = "Old name", null, null)
    private val updated = mutableListOf<Server>()
    private var systemInfo: () -> ServerInfo = { info(SERVER_ID, "New name") }

    @BeforeEach
    fun setUp() {
        // viewModelScope is built with Dispatchers.Main.immediate; provide one.
        Dispatchers.setMain(StandardTestDispatcher())
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
    fun `a Home load stores and shows the new name`() {
        val state = load { it.server?.name == "New name" }

        assertEquals("New name", state.server?.name)
        assertEquals(listOf(stored.copy(name = "New name")), updated)
    }

    @Test
    fun `an unreachable server keeps the stored name`() {
        systemInfo = { throw IOException("no connection") }

        val state = load { it.server != null }

        assertEquals("Old name", state.server?.name)
        assertTrue(updated.isEmpty())
    }

    private fun load(done: (HomeState) -> Boolean): HomeState {
        val viewModel =
            HomeViewModel(
                repository = repository(),
                appPreferences = AppPreferences(sharedPreferences()),
                database = database(),
            )
        viewModel.loadData()
        // loadData() runs on Dispatchers.Default; wait until it has finished.
        return runBlocking {
            withTimeout(5_000) {
                while (with(viewModel.state.value) { isLoading || !done(this) }) delay(10)
                viewModel.state.value
            }
        }
    }

    private fun info(id: String, name: String) = ServerInfo(id, name, null, null)

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

    private fun database(): ServerDatabaseDao =
        fakeProxy(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, args ->
            when (method.kotlinName) {
                "getServer" -> stored
                "getServerCurrentUser" -> null
                "updateServer" -> updated.add(args!![0] as Server).let { Unit }
                else -> throw NotImplementedError("ServerDatabaseDao.${method.kotlinName}")
            }
        }
            as ServerDatabaseDao

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
