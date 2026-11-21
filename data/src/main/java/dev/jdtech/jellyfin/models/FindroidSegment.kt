package dev.jdtech.jellyfin.models

enum class FindroidSegmentType {
    INTRO,
    OUTRO,
    RECAP,
    PREVIEW,
    COMMERCIAL,
    UNKNOWN,
}

data class FindroidSegment(val type: FindroidSegmentType, val startTicks: Long, val endTicks: Long)

fun FindroidSegmentDto.toFindroidSegment(): FindroidSegment {
    return FindroidSegment(type = type, startTicks = startTicks, endTicks = endTicks)
}
