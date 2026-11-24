package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.Chapter
import dev.jdtech.jellyfin.models.ItemTrickplayInfo
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType
import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.models.toMediaSourceId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.TrickplayInfoDto

// What the player shows on the seek bar: chapters, segments and trickplay thumbnails.

internal fun BaseItemDto.toChapters(): List<Chapter> {
    return chapters?.map { chapter ->
        Chapter(
            startPosition = Ticks.toMilliseconds(chapter.startPositionTicks),
            name = chapter.name,
            imageTag = chapter.imageTag,
        )
    } ?: emptyList()
}

private fun MediaSegmentType.toSegmentType(): SegmentType =
    when (this) {
        MediaSegmentType.UNKNOWN -> SegmentType.UNKNOWN
        MediaSegmentType.INTRO -> SegmentType.INTRO
        MediaSegmentType.OUTRO -> SegmentType.OUTRO
        MediaSegmentType.RECAP -> SegmentType.RECAP
        MediaSegmentType.PREVIEW -> SegmentType.PREVIEW
        MediaSegmentType.COMMERCIAL -> SegmentType.COMMERCIAL
    }

internal fun MediaSegmentDto.toSegment(): Segment {
    return Segment(
        type = type.toSegmentType(),
        startTicks = Ticks.toMilliseconds(startTicks),
        endTicks = Ticks.toMilliseconds(endTicks),
    )
}

/**
 * The trickplay layout of each version of the item (`Trickplay` is source id to width to layout),
 * one width each (#860): see [preferredTrickplayInfo]. A version without a usable width is left
 * out, as one without trickplay.
 */
internal fun BaseItemDto.toItemTrickplayInfo(): Map<MediaSourceId, ItemTrickplayInfo>? =
    trickplay
        ?.mapNotNull { (sourceId, widths) ->
            widths?.preferredTrickplayInfo()?.let {
                sourceId.toMediaSourceId() to it.toItemTrickplayInfo()
            }
        }
        ?.toMap()

/**
 * CE (#860): the width the player shows of a version that has several. The keys are widths as text,
 * so they are compared as numbers; a key that is not a number is passed over.
 *
 * The widest width whose sheet fits, else the smallest sheet, so a version with a usable width
 * always gets one. A sheet fits when it is no wider than [MAX_TRICKPLAY_SHEET_WIDTH] and no bigger
 * than [MAX_TRICKPLAY_SHEET_PIXELS]. The phone player decodes whole sheets and keeps three, so a
 * sheet's size is what it costs: a 10 x 10 sheet of 1280 wide is 12800 x 7200, 184 MB decoded,
 * where Jellyfin's default of 320 is 11.5 MB. The tile is shown 160 to 320 dp wide on a phone and
 * 240 dp on TV, so a 320 tile is upscaled about twice there; a server with a coarser grid (fewer
 * tiles a sheet) still gets its wider width.
 *
 * The sheet's width, not its pixel count, is what is compared first: the height follows the video's
 * shape, so a 4:3 or portrait item at 320 has a taller sheet than a 16:9 one, and a pixel limit at
 * the 16:9 sheet took 160 over 320 for it. For 16:9 on a square grid both tests give the same
 * width.
 */
internal fun Map<String, TrickplayInfoDto>.preferredTrickplayInfo(): TrickplayInfoDto? {
    val byWidth = mapNotNull { (width, info) -> width.toIntOrNull()?.let { it to info } }
    return byWidth
        .filter { (_, info) ->
            info.width.toLong() * info.tileWidth <= MAX_TRICKPLAY_SHEET_WIDTH &&
                info.sheetPixels() <= MAX_TRICKPLAY_SHEET_PIXELS
        }
        .maxByOrNull { (width, _) -> width }
        ?.second ?: byWidth.minByOrNull { (_, info) -> info.sheetPixels() }?.second
}

private fun TrickplayInfoDto.sheetPixels(): Long =
    width.toLong() * tileWidth * height.toLong() * tileHeight

/** The width of a sheet of Jellyfin's default trickplay: 10 tiles of 320. */
internal const val MAX_TRICKPLAY_SHEET_WIDTH = 3200L

/**
 * The size above which the phone player samples a sheet down (`TrickplayLoader.MAX_SHEET_PIXELS`):
 * a 10 x 10 sheet of 320-wide tiles up to portrait 9:16.
 */
internal const val MAX_TRICKPLAY_SHEET_PIXELS = 3200L * 5760L

internal fun TrickplayInfoDto.toItemTrickplayInfo(): ItemTrickplayInfo {
    return ItemTrickplayInfo(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        interval = interval,
        bandwidth = bandwidth,
    )
}
