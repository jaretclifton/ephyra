package dev.jdtech.jellyfin.repository

/**
 * CE (#775): what the app already knows about the server, so a list Room can answer does not wait
 * [STORED_FALLBACK_TIMEOUT_MS] for a server known to be away. `ServerReachability` in core is the
 * one the app uses; [NONE] (never known to be away) is for where nothing watches the server, such
 * as TV and tests.
 */
interface KnownServerState {
    /** True while the server is known not to answer. */
    fun isKnownUnreachable(): Boolean

    /**
     * Room answered without asking the server. The request that was not made could have been the
     * one to find the server back, so this asks whether it is.
     */
    fun onServerSkipped()

    companion object {
        val NONE =
            object : KnownServerState {
                override fun isKnownUnreachable() = false

                override fun onServerSkipped() = Unit
            }
    }
}
