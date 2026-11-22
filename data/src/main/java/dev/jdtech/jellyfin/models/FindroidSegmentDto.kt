package dev.jdtech.jellyfin.models

import androidx.room3.Entity

/**
 * CE (#693): `startTicks` is part of the key, so an item with two segments of one type (two
 * commercial breaks) keeps both.
 */
@Entity(tableName = "segments", primaryKeys = ["itemId", "type", "startTicks"])
data class FindroidSegmentDto(
    val itemId: ItemId,
    val type: FindroidSegmentType,
    val startTicks: Long,
    val endTicks: Long,
)

fun FindroidSegment.toFindroidSegmentsDto(itemId: ItemId): FindroidSegmentDto {
    return FindroidSegmentDto(
        itemId = itemId,
        type = type,
        startTicks = startTicks,
        endTicks = endTicks,
    )
}

fun FindroidSegmentDto.toFindroidSegment(): FindroidSegment {
    return FindroidSegment(type = type, startTicks = startTicks, endTicks = endTicks)
}
