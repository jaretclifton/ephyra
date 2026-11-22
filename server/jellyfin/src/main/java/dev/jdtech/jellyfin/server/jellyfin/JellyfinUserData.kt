package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ItemId

/**
 * [UserDataPort] over the app's Jellyfin client, for the current user (#757). The sync's writes for
 * any saved user are [JellyfinUserDataSync]. Throws [dev.jdtech.jellyfin.models.ServerException]
 * for every failure the SDK reports as one (see [asServerException]).
 */
class JellyfinUserData(private val api: JellyfinApi) : UserDataPort {
    override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
        call {
            if (favorite) api.userDataApi.markFavoriteItem(itemId.toUuid())
            else api.userDataApi.unmarkFavoriteItem(itemId.toUuid())
        }
    }

    override suspend fun setPlayed(itemId: ItemId, played: Boolean) {
        call {
            if (played) api.userDataApi.markPlayedItem(itemId.toUuid())
            else api.userDataApi.markUnplayedItem(itemId.toUuid())
        }
    }
}
