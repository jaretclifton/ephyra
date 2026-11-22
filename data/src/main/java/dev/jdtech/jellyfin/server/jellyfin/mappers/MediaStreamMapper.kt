package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.server.jellyfin.toModel
import org.jellyfin.sdk.model.api.MediaStream

internal fun MediaStream.toFindroidMediaStream(baseUrl: String): FindroidMediaStream {
    return FindroidMediaStream(
        title = title.orEmpty(),
        displayTitle = displayTitle,
        language = language.orEmpty(),
        type = type.toModel(),
        codec = codec.orEmpty(),
        profile = profile,
        isExternal = isExternal,
        path = deliveryUrl?.let { serverUrl(baseUrl, it) },
        channelLayout = channelLayout,
        videoRangeType = videoRangeType?.toModel(),
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
        bitRate = bitRate,
        // The average is what the server puts in the display title; the "real" rate is the
        // stream's time base and can be far off for variable frame rate video.
        frameRate = averageFrameRate ?: realFrameRate,
        bitDepth = bitDepth,
        sampleRate = sampleRate,
        index = index,
        channels = channels,
        deliveryMethod = deliveryMethod?.toModel(),
        isForced = isForced,
        isDefault = isDefault,
        isHearingImpaired = isHearingImpaired,
    )
}
