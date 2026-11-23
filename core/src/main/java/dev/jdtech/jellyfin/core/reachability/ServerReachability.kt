package dev.jdtech.jellyfin.core.reachability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.repository.KnownServerState
import dev.jdtech.jellyfin.repository.ServerAvailability
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CE (#281): whether the server in use answers, kept up to date while the app runs.
 *
 * This replaces the look at the device's internet at app start: a server that is down, a VPN that
 * is off or being away from home all leave the internet up. The screens show a banner with a way to
 * Downloads while [state] is unreachable; nothing is blocked and no screen waits for it.
 *
 * [start] hooks up the signs: the app's own requests ([ServerSession.requestWatcher]), the app
 * coming to the foreground, and the default network changing. Without [start] (Android TV, which
 * has no downloads to point at) the state stays unknown and nothing is probed.
 */
@Singleton
class ServerReachability
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val serverSession: ServerSession,
    private val appPreferences: AppPreferences,
) : KnownServerState, ServerAvailability {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val monitor =
        ServerReachabilityMonitor(
            scope = scope,
            probe = { serverSession.probe() },
            hasNetwork = ::hasNetwork,
            now = SystemClock::elapsedRealtime,
            probeTimeoutMs = serverSession.probeTimeoutMs,
        )

    val state: StateFlow<ServerReachabilityState> = monitor.state

    /** See [ServerReachabilityMonitor.serverConfirmed]: the user data sync starts over on it. */
    override val serverConfirmed: SharedFlow<Unit> = monitor.serverConfirmed

    /** Asks now, without waiting for the answer: the banner's Retry and a pull to refresh. */
    fun check() = monitor.check()

    // CE (#775): the lists Room can answer skip the server while this says it is away. Unknown
    // (also all of TV, where this is not started) never skips.
    override fun isKnownUnreachable(): Boolean = state.value.unreachable

    // While unreachable the app's failed requests start no probe, only an answered one would show
    // the server back sooner than the retry probes. The skipped request cannot, so a probe does.
    override fun onServerSkipped() = check()

    /**
     * Starts watching. Safe to call more than once, and cheap enough for `Application.onCreate`.
     */
    fun start() {
        if (started) return
        started = true

        serverSession.requestWatcher = monitor

        scope.launch { state.collect { Timber.d("Server reachability: %s", it) } }

        // Another server: what was known is about the old one.
        scope.launch {
            appPreferences.observeCurrentServerId().distinctUntilChanged().drop(1).collect {
                monitor.reset()
            }
        }

        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get()
                .lifecycle
                .addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) = monitor.onForeground()

                        override fun onStop(owner: LifecycleOwner) = monitor.onBackground()
                    }
                )
        }

        try {
            context
                .getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = monitor.onNetworkChanged()

                        override fun onLost(network: Network) = monitor.onNetworkChanged()
                    }
                )
        } catch (e: RuntimeException) {
            // Too many callbacks registered, or a device that refuses: the other signs remain.
            Timber.w(e, "No network callback for the server reachability")
        }
    }

    private fun hasNetwork(): Boolean =
        try {
            context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
        } catch (e: RuntimeException) {
            true
        }
}
