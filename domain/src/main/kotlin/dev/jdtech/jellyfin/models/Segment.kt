package dev.jdtech.jellyfin.models

enum class SegmentType {
    INTRO,
    OUTRO,
    RECAP,
    PREVIEW,
    COMMERCIAL,
    UNKNOWN,
}

data class Segment(val type: SegmentType, val startTicks: Long, val endTicks: Long)
