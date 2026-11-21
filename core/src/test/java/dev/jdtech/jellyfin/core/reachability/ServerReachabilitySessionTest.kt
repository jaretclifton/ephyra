package dev.jdtech.jellyfin.core.reachability

import android.app.Application
import android.content.Context
import android.os.Looper
import dev.jdtech.jellyfin.core.session.FakeServerSession
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #763: [ServerReachability] over its [dev.jdtech.jellyfin.domain.ports.ServerSession]: [start]
 * hands the session the watcher of the app's requests, a check asks the session's probe, and the
 * probe is given up after the session's timeout. The rules themselves are in
 * [ServerReachabilityMonitorTest] and [ServerReachabilityTrackerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerReachabilitySessionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val preferences =
        AppPreferences(context.getSharedPreferences("reachability", Context.MODE_PRIVATE))
    private val session = FakeServerSession(baseUrl = "http://127.0.0.1:8096")

    private fun reachability() = ServerReachability(context, session, preferences)

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `start hands the session the watcher of the requests`() {
        val reachability = reachability()
        assertNull(session.requestWatcher)

        reachability.start()

        val watcher = assertNotNull(session.requestWatcher)
        watcher.onStarted()
        watcher.onAnswered()
        assertEquals(Reachability.Reachable, reachability.state.value.reachability)
    }

    @Test
    fun `without start, as on TV, the session gets no watcher`() {
        reachability()

        assertNull(session.requestWatcher)
    }

    @Test
    fun `a check asks the session's probe`() {
        val reachability = reachability()
        session.probeAnswer = { false }

        reachability.check()

        waitFor { reachability.state.value.unreachable }
        assertEquals(1, session.probes)

        session.probeAnswer = { true }
        reachability.check()
        waitFor { reachability.state.value.reachability == Reachability.Reachable }
    }

    @Test
    fun `a probe that does not end is given up after the session's timeout`() {
        session.probeTimeoutMs = 100
        session.probeAnswer = { awaitCancellation() }
        val reachability = reachability()

        reachability.check()

        waitFor { reachability.state.value.unreachable }
    }
}
