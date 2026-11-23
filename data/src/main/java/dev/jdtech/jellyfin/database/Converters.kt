package dev.jdtech.jellyfin.database

import androidx.room3.ColumnTypeConverter
import dev.jdtech.jellyfin.models.Chapter
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.Json

class Converters {
    @ColumnTypeConverter
    fun fromDateTimeToLong(value: LocalDateTime?): Long? {
        return value?.toEpochSecond(ZoneOffset.UTC)
    }

    @ColumnTypeConverter
    fun fromLongToDatetime(value: Long?): LocalDateTime? {
        return value?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
    }

    @ColumnTypeConverter
    fun fromChaptersToString(value: List<Chapter>?): String? {
        return value?.let { Json.encodeToString(value) }
    }

    @ColumnTypeConverter
    fun fromStringToChapters(value: String?): List<Chapter>? {
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
