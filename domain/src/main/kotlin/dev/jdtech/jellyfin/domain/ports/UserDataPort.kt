package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.models.ItemId

/**
 * The current user's marks on the server (#757). For a saved user who need not be the current one,
 * as the sync needs, see [UserDataSyncPort]. Failures are thrown as `ServerException`.
 */
interface UserDataPort {
    suspend fun setFavorite(itemId: ItemId, favorite: Boolean)

    suspend fun setPlayed(itemId: ItemId, played: Boolean)
}
