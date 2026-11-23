package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.models.ItemId
import kotlinx.coroutines.CoroutineScope

/**
 * [UserDataPort] over the app's Jellyfin client, for the current user (#757). The sync's writes for
 * any saved user are [JellyfinUserDataSync]. Throws [dev.jdtech.jellyfin.models.ServerException]
 * for every failure the SDK reports as one (see [asServerException]).
 *
 * #899: with a [pin], for that value's server and user, whatever is switched to meanwhile.
 */
class JellyfinUserData(private val api: JellyfinApi, private val pin: ActiveServer? = null) :
    UserDataPort {
    /** #899: every call of this port goes through here, held to [pin]. */
    private suspend fun <T> call(block: suspend CoroutineScope.() -> T): T =
        api.pinnedCall(pin, block)

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
