package dev.jdtech.jellyfin.models

/**
 * One user on one server (#899): what the stores that keep per-user data are keyed by. A server
 * with no user yet has no key.
 */
data class AccountKey(val serverId: ServerId, val userId: UserId)
