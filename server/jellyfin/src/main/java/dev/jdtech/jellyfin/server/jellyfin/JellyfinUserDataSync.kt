package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.ServerUserData
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPort
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/** #762: [UserDataSyncPort]s over the Jellyfin client, for any saved user. */
class JellyfinUserDataSyncPorts(private val jellyfinApi: JellyfinApi) : UserDataSyncPorts {
    /**
     * Over the app's own client when it is signed in as [userId] at [baseUrl], so the requests
     * share its connections and its detection of a revoked token; otherwise over a client for that
     * user from the same Jellyfin instance. The app's client is never pointed at another user,
     * because the screens use it at the same time.
     */
    override fun forUser(baseUrl: String, accessToken: String?, userId: UserId): UserDataSyncPort {
        val current = jellyfinApi.api
        if (
            jellyfinApi.userId?.toUserId() == userId &&
                current.baseUrl == baseUrl &&
                current.accessToken == accessToken
        ) {
            return JellyfinUserDataSync(current, userId, appClient = true)
        }
        val api =
            jellyfinApi.jellyfin.createApi(
                baseUrl = baseUrl,
                accessToken = accessToken,
                httpClientOptions = current.httpClientOptions,
            )
        return JellyfinUserDataSync(api, userId, appClient = false)
    }
}

/**
 * #762: the user data of [userId] through [api]. [appClient]: [api] is the app's own client, the
 * one whose play sessions this device reports.
 */
internal class JellyfinUserDataSync(
    private val api: ApiClient,
    private val userId: UserId,
    private val appClient: Boolean,
) : UserDataSyncPort {

    /** CE (#633): one request per [USER_DATA_BATCH_SIZE] items. */
    override suspend fun userDataOf(itemIds: List<ItemId>): Map<ItemId, ServerUserData> =
        itemIds.chunked(USER_DATA_BATCH_SIZE).fold(emptyMap()) { found, batch ->
            val items = serverCall {
                api.libraryApi
                    .getItems(
                        userId = userId.toUuid(),
                        ids = batch.map { it.toUuid() },
                        enableUserData = true,
                    )
                    .content
                    .items
            }
            found + serverUserDataOf(items)
        }

    override suspend fun putUserData(data: ServerUserData) {
        serverCall {
            api.userDataApi.updateItemUserData(
                itemId = data.itemId.toUuid(),
                userId = userId.toUuid(),
                data =
                    UpdateUserItemDataDto(
                        playbackPositionTicks = data.playbackPositionTicks,
                        isFavorite = data.favorite,
                        played = data.played,
                        // Null leaves the server's date as it is.
                        lastPlayedDate = data.lastPlayedDate,
                    ),
            )
        }
    }

    override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
        serverCall {
            api.userDataApi.updateItemUserData(
                itemId = itemId.toUuid(),
                userId = userId.toUuid(),
                data = UpdateUserItemDataDto(isFavorite = favorite),
            )
        }
    }

    override suspend fun reportStopped(itemId: ItemId, positionTicks: Long) {
        serverCall {
            api.sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = itemId.toUuid(),
                    positionTicks = positionTicks,
                    failed = false,
                )
            )
        }
    }

    override suspend fun itemLeftPlaying(): ItemId? {
        if (!appClient) return null
        return serverCall {
            api.sessionApi
                .getSessions(deviceId = api.deviceInfo.id)
                .content
                .firstOrNull { it.userId.toUserId() == userId }
                ?.nowPlayingItem
                ?.id
                ?.toItemId()
        }
    }
}

/**
 * Runs [block], throwing its failure as a [ServerException]: an answer with an error status as
 * [ServerException.HttpStatus], anything else that is not one already as
 * [ServerException.Unreachable].
 */
internal inline fun <T> serverCall(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.asServerException() ?: ServerException.Unreachable(e)
    }

/** CE (#633): the user data of [items] by item id; items without user data are left out. */
internal fun serverUserDataOf(items: List<BaseItemDto>): Map<ItemId, ServerUserData> =
    items
        .mapNotNull { item ->
            val userData = item.userData ?: return@mapNotNull null
            ServerUserData(
                itemId = item.id.toItemId(),
                played = userData.played,
                favorite = userData.isFavorite,
                playbackPositionTicks = userData.playbackPositionTicks,
                lastPlayedDate = userData.lastPlayedDate,
            )
        }
        .associateBy { it.itemId }

/** CE (#633): items per request when reading the server's user data, to keep the URL short. */
private const val USER_DATA_BATCH_SIZE = 100
