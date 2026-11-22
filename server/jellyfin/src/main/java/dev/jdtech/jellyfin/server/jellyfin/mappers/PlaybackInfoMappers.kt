package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidChapter
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.TrickplayInfoDto

// What the player shows on the seek bar: chapters, segments and trickplay thumbnails.

internal fun BaseItemDto.toFindroidChapters(): List<FindroidChapter> {
    return chapters?.map { chapter ->
        FindroidChapter(
            startPosition = chapter.startPositionTicks / 10000,
            name = chapter.name,
            imageTag = chapter.imageTag,
        )
    } ?: emptyList()
}

private fun MediaSegmentType.toFindroidSegmentType(): FindroidSegmentType =
    when (this) {
        MediaSegmentType.UNKNOWN -> FindroidSegmentType.UNKNOWN
        MediaSegmentType.INTRO -> FindroidSegmentType.INTRO
        MediaSegmentType.OUTRO -> FindroidSegmentType.OUTRO
        MediaSegmentType.RECAP -> FindroidSegmentType.RECAP
        MediaSegmentType.PREVIEW -> FindroidSegmentType.PREVIEW
        MediaSegmentType.COMMERCIAL -> FindroidSegmentType.COMMERCIAL
    }

internal fun MediaSegmentDto.toFindroidSegment(): FindroidSegment {
    return FindroidSegment(
        type = type.toFindroidSegmentType(),
        startTicks = startTicks / 10000,
        endTicks = endTicks / 10000,
    )
}

internal fun TrickplayInfoDto.toFindroidTrickplayInfo(): FindroidTrickplayInfo {
    return FindroidTrickplayInfo(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}
