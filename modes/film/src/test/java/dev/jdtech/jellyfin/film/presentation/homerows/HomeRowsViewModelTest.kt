package dev.jdtech.jellyfin.film.presentation.homerows

import dev.jdtech.jellyfin.film.domain.homerows.HomeRowsStore
import dev.jdtech.jellyfin.film.domain.homerows.MemorySharedPreferences
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemImages
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.repository.ServerStore
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.asServerId
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #431: a change in Home rows makes Home load again when it comes back into view. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRowsViewModelTest {
    private val movies =
        Library(
            id = UUID.randomUUID().toString().toItemId(),
            name = "Movies",
            type = CollectionType.Movies,
            images = ItemImages(),
        )
    private val events = DataChangeEvents()

    /** Home's own policy, loaded just now: within the 60 s it would not load on return. */
    private val home = ReloadPolicy(events).apply { complete(begin()) }

    private lateinit var viewModel: HomeRowsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val prefs = AppPreferences(MemorySharedPreferences())
        prefs.setValue(prefs.currentServer, "server")
        val store =
            HomeRowsStore(
                prefs,
                dao(
                    Server(
                        "server".toServerId(),
                        "Server",
                        null,
                        UUID.randomUUID().toString().toUserId(),
                    )
                ),
            )
        viewModel = HomeRowsViewModel(repository(), store, events)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening Home rows without a change leaves Home as it is`() {
        assertFalse(viewModel.state.value.isLoading)
        assertFalse(home.shouldReload())
    }

    @Test
    fun `hiding a row makes Home load again`() {
        viewModel.onAction(HomeRowsAction.OnVisibleChange("suggestions", visible = false))

        assertTrue(home.shouldReload())
    }

    @Test
    fun `moving a row makes Home load again`() {
        viewModel.onAction(HomeRowsAction.OnMoveUp("next_up"))
        assertTrue(home.shouldReload())

        home.complete(home.begin())
        viewModel.onAction(HomeRowsAction.OnMoveDown("next_up"))
        assertTrue(home.shouldReload())
    }

    private fun repository(): MediaRepository =
        fakeProxy(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) {
            _,
            method,
            _ ->
            when (method.kotlinName) {
                "getUserViews" -> listOf(movies)
                else -> throw NotImplementedError("MediaRepository.${method.kotlinName}")
            }
        }
            as MediaRepository

    private fun dao(server: Server): ServerStore =
        fakeProxy(ServerStore::class.java.classLoader, arrayOf(ServerStore::class.java)) {
            _,
            method,
            args ->
            when (method.kotlinName) {
                "server" -> server.takeIf { it.id == args!![0].asServerId() }
                else -> throw NotImplementedError("ServerStore.${method.kotlinName}")
            }
        }
            as ServerStore
}
