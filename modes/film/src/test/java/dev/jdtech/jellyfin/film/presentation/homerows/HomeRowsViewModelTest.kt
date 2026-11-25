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
import dev.jdtech.jellyfin.repository.ItemLists
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeRoles
import dev.jdtech.jellyfin.testing.asServerId
import dev.jdtech.jellyfin.testing.fakeRole
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
        viewModel = HomeRowsViewModel(repository.role(), store, events)
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

    private val repository: FakeRoles =
        FakeRoles(ItemLists::class) { _, method, _ ->
            when (method.kotlinName) {
                "getUserViews" -> listOf(movies)
                else ->
                    throw NotImplementedError(
                        "${method.declaringClass.simpleName}.${method.kotlinName}"
                    )
            }
        }

    private fun dao(server: Server): StoredServers = fakeRole { _, method, args ->
        when (method.kotlinName) {
            "server" -> server.takeIf { it.id == args!![0].asServerId() }
            else ->
                throw NotImplementedError(
                    "${method.declaringClass.simpleName}.${method.kotlinName}"
                )
        }
    }
}
