package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup

/**
 * CE (#331): the outside service that passes on the ratings (MDBList, in `ratings/mdblist`). It
 * answers one request with what came back and keeps no state: when to ask is
 * [ExternalRatingsService]'s rule. An implementation never logs or stores the key.
 */
interface RatingsProvider {
    /**
     * The ratings of [lookup]. Never throws for a failed request: that is [RatingsAnswer.Failed].
     */
    suspend fun title(lookup: RatingsLookup, apiKey: String): RatingsAnswer

    /** Whether [apiKey] works: [RatingsAnswer.Found] (with no ratings) when it does. */
    suspend fun checkKey(apiKey: String): RatingsAnswer
}

sealed interface RatingsAnswer {
    /** The ratings of the sites the app shows; empty when the service has none of them. */
    data class Found(val ratings: List<ExternalRating>) : RatingsAnswer

    /** The service doesn't know the title. */
    data object NotFound : RatingsAnswer

    /** The key is wrong, or its account may not do this. */
    data object InvalidKey : RatingsAnswer

    /** Too many requests: wait [retryAfterSeconds] (null when the service didn't say). */
    data class RateLimited(val retryAfterSeconds: Long?) : RatingsAnswer

    /** No answer, a timeout, another status, or a body that isn't what the service documents. */
    data object Failed : RatingsAnswer
}

/**
 * CE (#331): the answers of [RatingsProvider] kept on the device, so a second visit needs no
 * request and a page still has its ratings when the service can't be reached. The key is never part
 * of an entry.
 */
interface RatingsCache {
    /** The entry of [lookup], or null when there is none or it can't be read. */
    suspend fun read(lookup: RatingsLookup): CachedRatings?

    /** Stores [ratings] (also an empty list: nothing known) for [lookup]. Never throws. */
    suspend fun write(lookup: RatingsLookup, ratings: List<ExternalRating>, fetchedAt: Long)
}

/** [ratings] as the service gave them at [fetchedAt] (epoch milliseconds). */
data class CachedRatings(val fetchedAt: Long, val ratings: List<ExternalRating>)

/**
 * CE (#331): the user's ratings settings, read live on every call: a change counts at the next
 * request. The app implements it over the preferences.
 */
interface ExternalRatingsSettings {
    /** The switch. Off by default. */
    val enabled: Boolean

    /** The user's key for the service, as typed; null when there is none. */
    val apiKey: String?
}
