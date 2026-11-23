package dev.jdtech.jellyfin.ratings.mdblist

import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.ratings.CachedRatings
import dev.jdtech.jellyfin.ratings.RatingsCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * CE (#331): the ratings on disk, one small file per title in the app's cache directory. An empty
 * list is stored too: the service doesn't know the title, or has none of the shown scores. Android
 * may clear the directory when space runs out, which only costs a request. No key is ever written.
 */
class FileRatingsCache(private val directory: File) : RatingsCache {
    override suspend fun read(lookup: RatingsLookup): CachedRatings? =
        withContext(Dispatchers.IO) { readNow(lookup) }

    override suspend fun write(
        lookup: RatingsLookup,
        ratings: List<ExternalRating>,
        fetchedAt: Long,
    ) = withContext(Dispatchers.IO) { writeNow(lookup, ratings, fetchedAt) }

    private fun readNow(lookup: RatingsLookup): CachedRatings? {
        val file = file(lookup)
        if (!file.isFile) return null
        return try {
            val stored = Json.parseToJsonElement(file.readText()).jsonObject
            CachedRatings(
                fetchedAt = stored.getValue(FETCHED_AT).jsonPrimitive.long,
                ratings =
                    stored.getValue(RATINGS).jsonArray.mapNotNull { element ->
                        val rating = element.jsonObject
                        val name = rating.getValue(SOURCE).jsonPrimitive.content
                        RatingSource.entries
                            .firstOrNull { it.name == name }
                            ?.let {
                                ExternalRating(
                                    it,
                                    rating.getValue(VALUE).jsonPrimitive.content.toFloat(),
                                )
                            }
                    },
            )
        } catch (e: Exception) {
            Timber.w("Unreadable external ratings cache entry (%s)", e.javaClass.simpleName)
            file.delete()
            null
        }
    }

    private fun writeNow(lookup: RatingsLookup, ratings: List<ExternalRating>, fetchedAt: Long) {
        try {
            directory.mkdirs()
            val stored: JsonObject = buildJsonObject {
                put(FETCHED_AT, fetchedAt)
                put(
                    RATINGS,
                    buildJsonArray {
                        ratings.forEach { rating ->
                            add(
                                buildJsonObject {
                                    put(SOURCE, rating.source.name)
                                    put(VALUE, rating.value)
                                }
                            )
                        }
                    },
                )
            }
            // A temp file of its own per write, so two writes of one title can't mix.
            val temp = File.createTempFile("${lookup.key}-", ".tmp", directory)
            try {
                temp.writeText(stored.toString())
                Files.move(
                    temp.toPath(),
                    file(lookup).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temp.delete()
            }
        } catch (e: Exception) {
            Timber.w("Could not store external ratings (%s)", e.javaClass.simpleName)
        }
    }

    private fun file(lookup: RatingsLookup) = File(directory, "${lookup.key}.json")

    private companion object {
        const val FETCHED_AT = "fetchedAt"
        const val RATINGS = "ratings"
        const val SOURCE = "source"
        const val VALUE = "value"
    }
}
