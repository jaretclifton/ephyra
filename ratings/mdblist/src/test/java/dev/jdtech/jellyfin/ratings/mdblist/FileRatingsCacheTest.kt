package dev.jdtech.jellyfin.ratings.mdblist

import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.ratings.CachedRatings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** CE (#331): the ratings cache on disk. */
class FileRatingsCacheTest {
    @TempDir lateinit var cacheDir: File

    private val lookup = RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "1")

    @Test
    fun `reads back what it stored, and drops a broken file`() = runBlocking {
        val cache = FileRatingsCache(cacheDir)
        val stored =
            listOf(
                ExternalRating(RatingSource.IMDB, 7.5f),
                ExternalRating(RatingSource.LETTERBOXD, 3.9f),
            )
        cache.write(lookup, stored, fetchedAt = 42)
        assertEquals(CachedRatings(42, stored), cache.read(lookup))
        cache.write(lookup, emptyList(), fetchedAt = 43)
        assertEquals(CachedRatings(43, emptyList()), cache.read(lookup))

        val file = File(cacheDir, "tmdb-movie-1.json")
        file.writeText("{broken")
        assertNull(cache.read(lookup))
        assertFalse(file.exists(), "a broken file is deleted")
        assertNull(cache.read(RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "2")))
    }

    @Test
    fun `a site it no longer knows is left out`() = runBlocking {
        File(cacheDir, "tmdb-movie-1.json")
            .writeText(
                """{"fetchedAt": 5, "ratings": [{"source": "TRAKT", "value": 80},""" +
                    """ {"source": "IMDB", "value": 8.1}]}"""
            )
        assertEquals(
            CachedRatings(5, listOf(ExternalRating(RatingSource.IMDB, 8.1f))),
            FileRatingsCache(cacheDir).read(lookup),
        )
    }
}
