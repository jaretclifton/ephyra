package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup

/** CE (#331): what the Test button of the ratings key says. */
enum class RatingsKeyStatus {
    VALID,
    INVALID,
    RATE_LIMITED,
    UNREACHABLE,
}

/**
 * CE (#331): the external ratings of a title, for the movie and show pages, and the key check of
 * the settings page. [ExternalRatingsService] is the implementation; the view models see only this.
 */
interface ExternalRatings {
    /** Switched on, with a key. Reads only memory and the settings. */
    fun isOn(): Boolean

    /**
     * Whether the ratings service may be asked about [lookup] now: on, with a key it didn't turn
     * down, not waiting out a 429, and no recent failure for this title. Memory and settings only.
     */
    fun canRequest(lookup: RatingsLookup): Boolean

    /**
     * Whether a page should keep room for ratings of [lookup]: when [canRequest], or when [ratings]
     * would show cached ones without asking. Memory and settings only, so it is cheap enough for
     * the page's first load.
     */
    fun mightHaveRatings(lookup: RatingsLookup): Boolean

    /**
     * The ratings of [lookup], from the cache or from the service. Never throws: off, no key, an
     * error or a title the service doesn't know all give an empty list, or the cached ratings when
     * there are any. Makes no request at all while switched off.
     */
    suspend fun ratings(lookup: RatingsLookup): List<ExternalRating>

    /**
     * Asks the service whether [apiKey] works, for the settings page: the only place where an error
     * of the service is shown. Sends one request even while switched off: the user pressed the
     * button.
     */
    suspend fun checkKey(apiKey: String): RatingsKeyStatus

    /** For tests and previews: never any ratings. */
    object None : ExternalRatings {
        override fun isOn() = false

        override fun canRequest(lookup: RatingsLookup) = false

        override fun mightHaveRatings(lookup: RatingsLookup) = false

        override suspend fun ratings(lookup: RatingsLookup) = emptyList<ExternalRating>()

        override suspend fun checkKey(apiKey: String) = RatingsKeyStatus.UNREACHABLE
    }
}
