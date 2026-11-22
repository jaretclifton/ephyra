package dev.jdtech.jellyfin.models

/** What a server says about itself without a login. */
data class ServerInfo(
    val id: ServerId,
    val name: String,
    val version: String?,
    val product: String?,
)
