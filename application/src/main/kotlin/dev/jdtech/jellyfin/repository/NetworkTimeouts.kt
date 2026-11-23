package dev.jdtech.jellyfin.repository

/**
 * #918: the network timeouts set by the user, in milliseconds, for the server adapter's client.
 * `core` implements them over the saved preferences, read again on every call.
 */
interface NetworkTimeouts {
    fun requestTimeout(): Long

    fun connectTimeout(): Long

    fun socketTimeout(): Long
}
