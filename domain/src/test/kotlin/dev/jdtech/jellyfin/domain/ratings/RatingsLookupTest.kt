package dev.jdtech.jellyfin.domain.ratings

import dev.jdtech.jellyfin.models.ExternalIds
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** CE (#331): which id a title is looked up by, and how scores read. */
class RatingsLookupTest {
    @Test
    fun `TMDB first, then IMDb`() {
        assertEquals(
            RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "2"),
            RatingsLookup.of(ExternalIds(imdb = "tt1", tmdb = "2"), RatedKind.MOVIE),
        )
        assertEquals(
            RatingsLookup(ExternalIdType.IMDB, RatedKind.SHOW, "tt1"),
            RatingsLookup.of(ExternalIds(imdb = "tt1", tvdb = "3"), RatedKind.SHOW),
        )
    }

    @Test
    fun `TVDB only for shows`() {
        assertEquals(
            RatingsLookup(ExternalIdType.TVDB, RatedKind.SHOW, "3"),
            RatingsLookup.of(ExternalIds(tvdb = "3"), RatedKind.SHOW),
        )
        assertNull(RatingsLookup.of(ExternalIds(tvdb = "3"), RatedKind.MOVIE))
    }

    @Test
    fun `an id that isn't plain letters and digits is not used`() {
        assertNull(RatingsLookup.of(ExternalIds(tmdb = "../user"), RatedKind.MOVIE))
        assertNull(RatingsLookup.of(ExternalIds(imdb = "tt1?x=y"), RatedKind.MOVIE))
        assertNull(RatingsLookup.of(ExternalIds(), RatedKind.MOVIE))
        assertEquals(
            RatingsLookup(ExternalIdType.IMDB, RatedKind.MOVIE, "tt1"),
            RatingsLookup.of(ExternalIds(tmdb = " ", imdb = " tt1 "), RatedKind.MOVIE),
        )
    }

    @Test
    fun `the key names the id type, the kind and the id`() {
        assertEquals("tmdb-show-42", RatingsLookup(ExternalIdType.TMDB, RatedKind.SHOW, "42").key)
    }

    @Test
    fun `scores read as each site writes them`() {
        val locale = Locale.US
        assertEquals("7.8", RatingSource.IMDB.format(7.8f, locale))
        assertEquals("85%", RatingSource.ROTTEN_TOMATOES.format(85f, locale))
        assertEquals("66", RatingSource.METACRITIC.format(66f, locale))
        assertEquals("3.9", RatingSource.LETTERBOXD.format(3.92f, locale))
        assertEquals("7,8", RatingSource.IMDB.format(7.8f, Locale.GERMANY))
    }
}
