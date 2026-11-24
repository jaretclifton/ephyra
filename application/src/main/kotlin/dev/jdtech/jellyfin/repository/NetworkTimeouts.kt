package dev.jdtech.jellyfin.repository

/**
 * #918: the network timeouts set by the user, in milliseconds, for the server adapter's client.
 * `core` implements them over the saved preferences, read again on every call.
 */
interface NetworkTimeouts {
    fun requestTimeout(): Long

    fun connectTimeout(): Long

    fun socketTimeout(): Long

    /**
     * What applies until the user sets something else, in milliseconds: the defaults of the
     * settings and of a client made before the settings are read. Here, so the adapter and the
     * settings share them without the adapter knowing the settings (R18).
     */
    companion object {
        const val DEFAULT_REQUEST_TIMEOUT = 30_000L
        const val DEFAULT_CONNECT_TIMEOUT = 6_000L
        const val DEFAULT_SOCKET_TIMEOUT = 10_000L
    }
}
