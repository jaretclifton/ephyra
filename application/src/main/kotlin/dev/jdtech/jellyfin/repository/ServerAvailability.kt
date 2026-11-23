package dev.jdtech.jellyfin.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the app learns about the server coming back, for code that waits for it (the download
 * queue, #912). `ServerReachability` in core is the one the app uses; [NONE] (never confirms) is
 * for where nothing watches the server, such as tests.
 */
interface ServerAvailability {
    /**
     * Emits when the server was found to answer: a probe reached it, a request was answered while
     * it counted as unreachable, or the app came to the foreground and the server answered a moment
     * ago. Not a state: a collector that is late misses it, and what waited before it started waits
     * for the next one.
     */
    val serverConfirmed: Flow<Unit>

    companion object {
        val NONE =
            object : ServerAvailability {
                override val serverConfirmed: Flow<Unit> = emptyFlow()
            }
    }
}
