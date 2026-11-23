package dev.jdtech.jellyfin.models

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "mediastreams", indices = [Index("sourceId")])
data class FindroidMediaStreamDto(
    @PrimaryKey val id: UUID,
    val sourceId: String,
    val title: String,
    val displayTitle: String?,
    val language: String,
    val type: MediaStreamType,
    val codec: String,
    val profile: String? = null,
    val isExternal: Boolean,
    val path: String,
    val channelLayout: String?,
    val videoRangeType: String?,
    val height: Int?,
    val width: Int?,
    val videoDoViTitle: String?,
    val downloadId: Long? = null,
)

fun MediaStream.toFindroidMediaStreamDto(
    id: UUID,
    sourceId: String,
    path: String,
): FindroidMediaStreamDto {
    return FindroidMediaStreamDto(
        id = id,
        sourceId = sourceId,
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = type,
        codec = codec,
        profile = profile,
        isExternal = isExternal,
        path = path,
        channelLayout = channelLayout,
        videoRangeType = videoRangeType?.name,
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
    )
}

fun FindroidMediaStreamDto.toMediaStream(): MediaStream {
    return MediaStream(
        title = title,
        displayTitle = displayTitle,
        language = language,
        type = type,
        codec = codec,
        profile = profile,
        isExternal = isExternal,
        path = path,
        channelLayout = channelLayout,
        videoRangeType = videoRangeType?.let(::storedVideoRangeType),
        height = height,
        width = width,
        videoDoViTitle = videoDoViTitle,
    )
}

/**
 * The stored column holds the constant name ([toFindroidMediaStreamDto] writes `name`). It was read
 * by the server's serial name, so `DOVI_WITH_HDR10`, `HDR10_PLUS` and the other values whose two
 * names differ came back as null (#776). The serial name is accepted too, for a row written that
 * way.
 */
internal fun storedVideoRangeType(value: String): VideoRangeType? =
    VideoRangeType.entries.firstOrNull { it.name == value }
        ?: VideoRangeType.entries.firstOrNull { it.serialName == value }
