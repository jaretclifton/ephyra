package dev.jdtech.jellyfin.setup.presentation

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.profilelock.ProfileLock
import dev.jdtech.jellyfin.setup.domain.FakeLocalNetworkAccess
import dev.jdtech.jellyfin.setup.domain.ServerLocalNetworkBlock
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.testing.kotlinName
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #769: the login and user screens offer Quick Connect, the disclaimer and the server's users only
 * when the server type has them, and do not ask the server for what it does not have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginCapabilitiesTest {
    private val asked = mutableListOf<String>()
    private val saved =
        User(id = UUID.randomUUID().toString().toUserId(), name = "Saved", serverId = "s")
    private val listed =
        User(id = UUID.randomUUID().toString().toUserId(), name = "Listed", serverId = "s")
    private val server =
        Server(id = "s", name = "Home", currentServerAddressId = null, currentUserId = null)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Jellyfin gets the Quick Connect button, the disclaimer and the listed users`() {
        val login = loginViewModel(ServerCapabilities.ALL)
        login.loadQuickConnectEnabled()
        login.loadDisclaimer()
        val users = usersViewModel(ServerCapabilities.ALL)
        users.loadUsers()

        assertTrue(login.state.value.quickConnectEnabled)
        // The disclaimer's HTML needs Android to become text (LoginDisclaimerTest): asked is
        // enough.
        assertEquals(listOf(listed), users.state.value.publicUsers)
        assertEquals(listOf("getIsQuickConnectEnabled", "loadDisclaimer", "getPublicUsers"), asked)
    }

    @Test
    fun `without Quick Connect there is no button and the server is not asked`() {
        val login = loginViewModel(ServerCapabilities.ALL.copy(quickConnect = false))

        login.loadQuickConnectEnabled()

        assertFalse(login.state.value.quickConnectEnabled)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `without a login disclaimer there is none and the server is not asked`() {
        val login = loginViewModel(ServerCapabilities.ALL.copy(loginDisclaimer = false))

        login.loadDisclaimer()

        assertNull(login.state.value.disclaimer)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `without public users only the saved users are shown and the server is not asked`() {
        val users = usersViewModel(ServerCapabilities.ALL.copy(publicUsers = false))

        users.loadUsers()

        assertEquals(listOf(saved), users.state.value.users)
        assertTrue(users.state.value.publicUsers.isEmpty())
        assertTrue(asked.isEmpty())
    }

    private fun loginViewModel(capabilities: ServerCapabilities) =
        LoginViewModel(
            fakeRepository(capabilities),
            ServerLocalNetworkBlock(FakeLocalNetworkAccess(), { null }),
        )

    private fun usersViewModel(capabilities: ServerCapabilities) =
        UsersViewModel(
            fakeRepository(capabilities),
            ProfileLock(unusedDao(), Dispatchers.Unconfined),
        )

    private fun fakeRepository(capabilities: ServerCapabilities): SetupRepository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, _ ->
            when (method.kotlinName) {
                "getCapabilities" -> capabilities
                "getCurrentServer" -> server
                "getUsers" -> listOf(saved)
                "getIsQuickConnectEnabled" -> true.also { asked += method.kotlinName }
                "loadDisclaimer" -> "For testing.".also { asked += method.kotlinName }
                "getPublicUsers" -> listOf(saved, listed).also { asked += method.kotlinName }
                "toString" -> "FakeSetupRepository"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException(method.kotlinName)
            }
        } as SetupRepository

    private fun unusedDao(): ServerDatabaseDao =
        Proxy.newProxyInstance(
            ServerDatabaseDao::class.java.classLoader,
            arrayOf(ServerDatabaseDao::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException(method.kotlinName)
        } as ServerDatabaseDao
}
