package dev.jdtech.jellyfin.server.jellyfin

import android.app.Application
import android.provider.Settings
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #748: the profile picture URL is the one `User.primaryImageUrl` built before it moved here, and
 * the session follows the client when the server or the user changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinServerSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val userId = UUID.fromString("0b3c8f8e-6a1d-4c39-9a3e-2f4f6a1b2c3d")

    private lateinit var api: JellyfinApi
    private lateinit var session: JellyfinServerSession

    @Before
    fun setUp() {
        // The SDK names the device after it; Robolectric has none.
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "test")
        api = JellyfinApi(context)
        session = JellyfinServerSession(api)
    }

    @Test
    fun `the profile picture URL is the server's user image path with the tag`() {
        api.api.update(baseUrl = "https://jf.example/jellyfin")

        assertEquals(
            "https://jf.example/jellyfin/Users/0b3c8f8e-6a1d-4c39-9a3e-2f4f6a1b2c3d/Images/" +
                "Primary?tag=abc123",
            session.userImageUrl(userId, "abc123"),
        )
    }

    @Test
    fun `a trailing slash on the address is not doubled`() {
        api.api.update(baseUrl = "http://10.0.2.2:8096//")

        assertEquals(
            "http://10.0.2.2:8096/Users/0b3c8f8e-6a1d-4c39-9a3e-2f4f6a1b2c3d/Images/Primary?tag=t",
            session.userImageUrl(userId, "t"),
        )
    }

    @Test
    fun `no URL without a server`() {
        assertNull(session.baseUrl)
        assertNull(session.userImageUrl(userId, "abc123"))
    }

    @Test
    fun `the address and the user are read at use, not when the session is made`() {
        assertNull(session.userId)

        api.api.update(baseUrl = "https://one.example")
        api.userId = userId
        assertEquals("https://one.example", session.baseUrl)
        assertEquals(userId, session.userId)

        val other = UUID.fromString("11111111-2222-3333-4444-555555555555")
        api.api.update(baseUrl = "https://two.example")
        api.userId = other
        assertEquals("https://two.example", session.baseUrl)
        assertEquals(other, session.userId)
    }

    @Test
    fun `use puts the address, the token and the user on the client`() {
        session.use("https://one.example", "token", userId)

        assertEquals("https://one.example", api.api.baseUrl)
        assertEquals("token", api.api.accessToken)
        assertEquals(userId, api.userId)
        assertEquals("token", session.accessToken)
    }

    @Test
    fun `signing out with use keeps the address and clears the token and the user`() {
        // #763: what ServerSessionKeeper did with `api.update(accessToken = null)`.
        session.use("https://one.example", "token", userId)

        session.use(session.baseUrl, null, null)

        assertEquals("https://one.example", api.api.baseUrl)
        assertNull(api.api.accessToken)
        assertNull(api.userId)
        assertNull(session.accessToken)
    }

    @Test
    fun `the request watcher is the client's own field`() {
        val watcher =
            object : RequestWatcher {
                override fun onStarted() = Unit

                override fun onAnswered() = Unit

                override fun onUnanswered() = Unit

                override fun onCancelled() = Unit
            }

        session.requestWatcher = watcher
        assertSame(watcher, api.requestWatcher)

        api.requestWatcher = null
        assertNull(session.requestWatcher)
    }

    @Test
    fun `the probe waits as long as it always did`() {
        assertEquals(3_000L, session.probeTimeoutMs)
        assertEquals(JellyfinApi.PROBE_TIMEOUT_MS, session.probeTimeoutMs)
    }
}
