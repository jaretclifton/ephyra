package dev.jdtech.jellyfin.models

/** A server that answered the search on the local network. */
data class DiscoveredServer(val id: String, val name: String, val address: String)

/**
 * A Quick Connect request: [code] is shown to the user, [secret] polls it and logs in once
 * [authenticated].
 */
data class QuickConnectState(val code: String, val secret: String, val authenticated: Boolean)
