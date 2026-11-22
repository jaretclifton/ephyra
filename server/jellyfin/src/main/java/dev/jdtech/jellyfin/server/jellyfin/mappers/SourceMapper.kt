package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.ItemId
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo

internal suspend fun MediaSourceInfo.toFindroidSource(
    context: MapperContext,
    itemId: ItemId,
    includePath: Boolean = false,
): FindroidSource {
    // When the server decided this source needs transcoding it returns a (relative)
    // transcodingUrl — an HLS manifest on the playback path, a progressive stream
    // on the download path. Honor it over the original file.
    val transcodeUrl = transcodingUrl
    val transcoded = !transcodeUrl.isNullOrBlank()
    val path =
        when {
            transcodeUrl != null && transcoded -> serverUrl(context.baseUrl, transcodeUrl)
            protocol == MediaProtocol.FILE -> {
                try {
                    if (includePath) context.streamUrl(itemId, id.orEmpty()) else ""
                } catch (_: Exception) {
                    ""
                }
            }
            protocol == MediaProtocol.HTTP -> this.path.orEmpty()
            else -> ""
        }
    return FindroidSource(
        id = id.orEmpty(),
        name = name.orEmpty(),
        type = FindroidSourceType.REMOTE,
        path = path,
        size = size ?: 0,
        mediaStreams =
            mediaStreams?.map { it.toFindroidMediaStream(context.baseUrl) } ?: emptyList(),
        transcoded = transcoded,
        container = container,
        bitrate = bitrate,
        defaultAudioStreamIndex = defaultAudioStreamIndex,
        defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
    )
}
