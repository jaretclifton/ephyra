package dev.jdtech.jellyfin.server.jellyfin.cast

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.server.jellyfin.mappers.MapperContext
import dev.jdtech.jellyfin.server.jellyfin.mappers.toItemSource
import org.jellyfin.sdk.model.api.MediaSourceInfo

/**
 * CE (#767): this source as a Cast receiver fetches it. A receiver has no session with the server,
 * so every URL it gets must carry what it needs: [ItemSource.path] is the server's transcoding URL
 * (which has the token in it), else the file with [accessToken] added; a subtitle's
 * [path][dev.jdtech.jellyfin.models.MediaStream.path] is its delivery URL when that is on the
 * server ([receiverUrl]), null otherwise. The token is added here and nowhere else: these URLs are
 * not logged.
 */
internal suspend fun MediaSourceInfo.toReceiverSource(
    context: MapperContext,
    itemId: ItemId,
    accessToken: String?,
): ItemSource {
    val source = toItemSource(context, itemId)
    val baseUrl = context.baseUrl.removeSuffix("/")
    return source.copy(
        path = castStreamUrl(baseUrl, itemId, id, transcodingUrl, accessToken),
        // The mapper keeps the server's order, one stream for one.
        mediaStreams =
            source.mediaStreams.zip(mediaStreams.orEmpty()) { stream, server ->
                stream.copy(path = server.deliveryUrl?.let { receiverUrl(baseUrl, it) })
            },
    )
}
