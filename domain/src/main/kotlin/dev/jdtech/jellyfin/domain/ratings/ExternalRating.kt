package dev.jdtech.jellyfin.domain.ratings

import dev.jdtech.jellyfin.models.ExternalIds
import java.util.Locale
import kotlin.math.roundToInt

/*
 * CE (#331): scores from outside sites (IMDb, Rotten Tomatoes, ...) on the movie and show pages,
 * shown when the user switches them on with a key of their own for the service that passes them on
 * (MDBList, the adapter in ratings/mdblist).
 */

/** The sites the detail pages show, in the order they show them. */
enum class RatingSource(
    /** The site's own name; a name, so not translated. */
    val label: String
) {
    IMDB("IMDb"),
    ROTTEN_TOMATOES("Rotten Tomatoes"),
    METACRITIC("Metacritic"),
    LETTERBOXD("Letterboxd");

    /**
     * The score as the site writes it: IMDb 7.8, Rotten Tomatoes 85%, Metacritic 66, Letterboxd
     * 3.9. The scales are 0–10, 0–100, 0–100 and 0–5.
     */
    fun format(value: Float, locale: Locale): String =
        when (this) {
            IMDB,
            LETTERBOXD -> String.format(locale, "%.1f", value)
            ROTTEN_TOMATOES -> "${value.roundToInt()}%"
            METACRITIC -> value.roundToInt().toString()
        }
}

data class ExternalRating(val source: RatingSource, val value: Float)

/** The site whose id a title is looked up by. */
enum class ExternalIdType {
    TMDB,
    IMDB,
    TVDB,
}

/** Whether a title is a movie or a show; the ratings service asks for each differently. */
enum class RatedKind {
    MOVIE,
    SHOW,
}

/**
 * The title the ratings are asked for: one of its ids at an outside site. Built from the item's
 * [ExternalIds], TMDB first, then IMDb, then (shows only) TVDB.
 */
data class RatingsLookup(val idType: ExternalIdType, val kind: RatedKind, val id: String) {
    /** A file name and a map key: the id is plain letters and digits (see [of]). */
    val key: String
        get() = "${idType.name.lowercase()}-${kind.name.lowercase()}-$id"

    companion object {
        private val validId = Regex("^[A-Za-z0-9]{1,32}$")

        /**
         * The lookup of a title with [ids], or null without a usable id. An id that isn't plain
         * letters and digits is never used: it goes into a URL and a file name.
         */
        fun of(ids: ExternalIds, kind: RatedKind): RatingsLookup? {
            val candidates =
                listOf(ExternalIdType.TMDB to ids.tmdb, ExternalIdType.IMDB to ids.imdb) +
                    if (kind == RatedKind.SHOW) listOf(ExternalIdType.TVDB to ids.tvdb)
                    else emptyList()
            return candidates.firstNotNullOfOrNull { (type, id) ->
                id?.trim()?.takeIf { validId.matches(it) }?.let { RatingsLookup(type, kind, it) }
            }
        }
    }
}
