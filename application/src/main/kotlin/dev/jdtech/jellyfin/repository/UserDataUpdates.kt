package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId

/**
 * #967: the current user's marks on an item: favourite and played. One of the roles the repository
 * was split into. A change is written to Room first and sent to the server, or kept for the user
 * data sync when the server is away (`PendingUserDataStore`, #899).
 */
interface UserDataUpdates {
    suspend fun markAsFavorite(itemId: ItemId)

    suspend fun unmarkAsFavorite(itemId: ItemId)

    suspend fun markAsPlayed(itemId: ItemId)

    suspend fun markAsUnplayed(itemId: ItemId)
}
