package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CE (#331): when the ratings service is asked, behind the switch and the user's key.
 * - Off, or no key: no request, no cache read, no ratings. The switch and the key are read again
 *   right before a request.
 * - Concurrent lookups of one title share one request.
 * - A title's answer is kept for [FOUND_TTL_MS] ([EMPTY_TTL_MS] when the service had nothing).
 * - A failed request (no answer, timeout, 5xx, bad body) isn't repeated for that title for
 *   [FAILURE_BACKOFF_MS]. A 429 stops every request for `Retry-After` seconds ([DEFAULT_BACKOFF_MS]
 *   when it is missing). A key the service turns down isn't used again until it changes or the Test
 *   button finds it works.
 * - Whenever a request isn't made or fails, older cached ratings are still shown.
 *
 * The rejected key and the back-offs live in memory only: a restart tries once more.
 */
class ExternalRatingsService(
    private val settings: ExternalRatingsSettings,
    private val provider: RatingsProvider,
    private val cache: RatingsCache,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExternalRatings {
    @Volatile private var rejectedKey: String? = null
    @Volatile private var blockedUntil = 0L
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Titles this process has seen ratings for, in the cache or from the service. Memory only. */
    private val rated = ConcurrentHashMap.newKeySet<String>()
    /** One lookup per title at a time, so two pages share a request and a cache write. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The key while switched on with one, rejected or not; null otherwise. */
    private fun key(): String? {
        if (!settings.enabled) return null
        return settings.apiKey?.trim()?.ifEmpty { null }
    }

    /** The key to send now, or null when nothing may be sent. */
    private fun sendableKey(lookup: RatingsLookup): String? {
        val key = key() ?: return null
        val now = clock()
        if (key == rejectedKey || now < blockedUntil || recentlyFailed(lookup, now)) return null
        return key
    }

    private fun recentlyFailed(lookup: RatingsLookup, now: Long): Boolean =
        failedAt[lookup.key]?.let { now - it < FAILURE_BACKOFF_MS } == true

    override fun isOn(): Boolean = key() != null

    override fun canRequest(lookup: RatingsLookup): Boolean = sendableKey(lookup) != null

    /**
     * Room is kept when the service may be asked, or when the title had ratings earlier in this
     * process: [ratings] then shows those cached ones even while it may not ask.
     */
    override fun mightHaveRatings(lookup: RatingsLookup): Boolean =
        isOn() && (canRequest(lookup) || lookup.key in rated)

    override suspend fun ratings(lookup: RatingsLookup): List<ExternalRating> {
        if (!isOn()) return emptyList()
        return locks.getOrPut(lookup.key) { Mutex() }.withLock { lookUp(lookup) }
    }

    private suspend fun lookUp(lookup: RatingsLookup): List<ExternalRating> {
        val cached = cache.read(lookup)
        if (cached != null && cached.ratings.isNotEmpty()) rated += lookup.key
        val now = clock()
        if (cached != null) {
            val ttl = if (cached.ratings.isEmpty()) EMPTY_TTL_MS else FOUND_TTL_MS
            if (now - cached.fetchedAt in 0 until ttl) return cached.ratings
        }
        val stale = cached?.ratings.orEmpty()
        // Read again right before the request: the switch or the key may have changed while the
        // cache was read. A rejected key, a 429 wait or a recent failure send nothing either.
        val key = sendableKey(lookup) ?: return stale

        return when (val answer = provider.title(lookup, key)) {
            is RatingsAnswer.Found -> {
                failedAt.remove(lookup.key)
                cache.write(lookup, answer.ratings, clock())
                if (answer.ratings.isNotEmpty()) rated += lookup.key else rated -= lookup.key
                answer.ratings
            }
            RatingsAnswer.NotFound -> {
                failedAt.remove(lookup.key)
                cache.write(lookup, emptyList(), clock())
                rated -= lookup.key
                emptyList()
            }
            RatingsAnswer.InvalidKey -> {
                rejectedKey = key
                stale
            }
            is RatingsAnswer.RateLimited -> {
                backOff(answer.retryAfterSeconds)
                stale
            }
            RatingsAnswer.Failed -> {
                failedAt[lookup.key] = clock()
                stale
            }
        }
    }

    /** One request, also while a 429 is waited out: the user pressed the button. */
    override suspend fun checkKey(apiKey: String): RatingsKeyStatus {
        val key = apiKey.trim()
        if (key.isEmpty()) return RatingsKeyStatus.INVALID
        return when (val answer = provider.checkKey(key)) {
            is RatingsAnswer.Found -> {
                if (rejectedKey == key) rejectedKey = null
                RatingsKeyStatus.VALID
            }
            RatingsAnswer.InvalidKey -> {
                rejectedKey = key
                RatingsKeyStatus.INVALID
            }
            is RatingsAnswer.RateLimited -> {
                backOff(answer.retryAfterSeconds)
                RatingsKeyStatus.RATE_LIMITED
            }
            // The key check has no "not found"; anything else means no answer as documented.
            RatingsAnswer.NotFound,
            RatingsAnswer.Failed -> RatingsKeyStatus.UNREACHABLE
        }
    }

    private fun backOff(retryAfterSeconds: Long?) {
        val wait =
            retryAfterSeconds?.coerceAtMost(MAX_BACKOFF_SECONDS)?.times(1000) ?: DEFAULT_BACKOFF_MS
        blockedUntil = maxOf(blockedUntil, clock() + wait)
    }

    companion object {
        const val FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val EMPTY_TTL_MS = 24L * 60 * 60 * 1000
        const val FAILURE_BACKOFF_MS = 15L * 60 * 1000
        const val DEFAULT_BACKOFF_MS = 60L * 60 * 1000
        /** A daily limit resets within a day; a larger Retry-After is a mistake. */
        private const val MAX_BACKOFF_SECONDS = 24L * 60 * 60
    }
}
