package dev.jdtech.jellyfin.models

import androidx.room3.Entity

/**
 * CE (#693): `startTicks` is part of the key, so an item with two segments of one type (two
 * commercial breaks) keeps both.
 */
@Entity(tableName = "segments", primaryKeys = ["itemId", "type", "startTicks"])
data class SegmentEntity(
    val itemId: ItemId,
    val type: SegmentType,
    val startTicks: Long,
    val endTicks: Long,
)

fun Segment.toSegmentEntity(itemId: ItemId): SegmentEntity {
    return SegmentEntity(itemId = itemId, type = type, startTicks = startTicks, endTicks = endTicks)
}

fun SegmentEntity.toSegment(): Segment {
    return Segment(type = type, startTicks = startTicks, endTicks = endTicks)
}
