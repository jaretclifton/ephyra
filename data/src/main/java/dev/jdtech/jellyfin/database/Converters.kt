package dev.jdtech.jellyfin.database

import androidx.room3.ColumnTypeConverter
import dev.jdtech.jellyfin.models.FindroidChapter
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json

class Converters {
    @ColumnTypeConverter
    fun fromStringToUUID(value: String?): UUID? {
        return value?.let { UUID.fromString(it) }
    }

    @ColumnTypeConverter
    fun fromUUIDToString(value: UUID?): String? {
        return value?.toString()
    }

    @ColumnTypeConverter
    fun fromDateTimeToLong(value: LocalDateTime?): Long? {
        return value?.toEpochSecond(ZoneOffset.UTC)
    }

    @ColumnTypeConverter
    fun fromLongToDatetime(value: Long?): LocalDateTime? {
        return value?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
    }

    @ColumnTypeConverter
    fun fromFindroidChaptersToString(value: List<FindroidChapter>?): String? {
        return value?.let { Json.encodeToString(value) }
    }

    @ColumnTypeConverter
    fun fromStringToFindroidChapters(value: String?): List<FindroidChapter>? {
        return value?.let { Json.decodeFromString(value) }
    }

    @ColumnTypeConverter
    fun fromStringListToString(value: List<String>?): String? {
        return value?.let { Json.encodeToString(value) }
    }

    @ColumnTypeConverter
    fun fromStringToStringList(value: String?): List<String>? {
        return value?.let { Json.decodeFromString(value) }
    }
}
