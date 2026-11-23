package dev.jdtech.jellyfin.ratings

import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

/**
 * CE (#331): the rules around the requests: nothing while off, the cache and its TTLs, the negative
 * cache, Retry-After and a rejected key. The clock, the service and the cache are the test's.
 */
class ExternalRatingsServiceTest {
    private val found =
        listOf(
            ExternalRating(RatingSource.IMDB, 9.3f),
            ExternalRating(RatingSource.ROTTEN_TOMATOES, 89f),
        )

    private class Settings : ExternalRatingsSettings {
        override var enabled = true
        override var apiKey: String? = "key"
    }

    private inner class Provider : RatingsProvider {
        var answer: RatingsAnswer = RatingsAnswer.Found(found)
        var keyAnswer: RatingsAnswer = RatingsAnswer.Found(emptyList())
        /** Awaited by every title request before it answers, when set. */
        var gate: CompletableDeferred<Unit>? = null
        /** The key of each title request. */
        val keys: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun title(lookup: RatingsLookup, apiKey: String): RatingsAnswer {
            keys += apiKey
            gate?.await()
            return answer
        }

        override suspend fun checkKey(apiKey: String) = keyAnswer
    }

    private inner class MemoryCache : RatingsCache {
        val entries: MutableMap<String, CachedRatings> = Collections.synchronizedMap(mutableMapOf())

        override suspend fun read(lookup: RatingsLookup): CachedRatings? {
            val entry = entries[lookup.key]
            onCacheRead()
            return entry
        }

        override suspend fun write(
            lookup: RatingsLookup,
            ratings: List<ExternalRating>,
            fetchedAt: Long,
        ) {
            entries[lookup.key] = CachedRatings(fetchedAt, ratings)
        }
    }

    private val settings = Settings()
    private val provider = Provider()
    private val cache = MemoryCache()
    private var now = 1_000_000_000L
    private val lookup = RatingsLookup(ExternalIdType.TMDB, RatedKind.MOVIE, "278")
    private val other = RatingsLookup(ExternalIdType.IMDB, RatedKind.MOVIE, "tt0111161")

    /** Runs right after each cache read, before the service decides whether to ask. */
    private var onCacheRead: () -> Unit = {}

    private val ratings = ExternalRatingsService(settings, provider, cache) { now }

    private fun get(which: RatingsLookup = lookup) = runBlocking { ratings.ratings(which) }

    private val requests: Int
        get() = provider.keys.size

    @Test
    fun `switched off during the cache read, nothing is sent`() {
        onCacheRead = { settings.enabled = false }
        assertEquals(emptyList(), get())
        assertEquals(0, requests)
    }

    @Test
    fun `a key changed during the cache read, the new one is sent`() {
        onCacheRead = { settings.apiKey = "new-key" }
        get()
        assertEquals(listOf("new-key"), provider.keys.toList())
    }

    @Test
    fun `two pages asking at once share one request`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val both =
            withContext(Dispatchers.Default) {
                val first = async { ratings.ratings(lookup) }
                val second = async { ratings.ratings(lookup) }
                while (requests == 0) delay(5)
                // Time for the second lookup to reach the title's lock.
                delay(50)
                gate.complete(Unit)
                listOf(first, second).awaitAll()
            }
        assertEquals(found, both[0])
        assertEquals(both[0], both[1])
        assertEquals(1, requests)
    }

    @Test
    fun `with a rejected key the cached ratings still show, and room is kept for them`() {
        val cached = get()
        now += ExternalRatingsService.FOUND_TTL_MS
        provider.answer = RatingsAnswer.InvalidKey
        assertEquals(cached, get(), message = "stale ratings after the 401")
        assertTrue(ratings.isOn())
        assertFalse(ratings.canRequest(lookup))
        assertTrue(ratings.mightHaveRatings(lookup), message = "the cached title keeps its room")
        assertFalse(ratings.mightHaveRatings(other), message = "a title without ratings does not")
        assertEquals(cached, get())
        assertEquals(2, requests)
    }

    @Test
    fun `during a 429 wait a title with cached ratings keeps its room`() {
        get()
        now += ExternalRatingsService.FOUND_TTL_MS
        provider.answer = RatingsAnswer.RateLimited(600)
        get()
        assertTrue(ratings.mightHaveRatings(lookup))
        assertFalse(ratings.mightHaveRatings(other))
    }

    @Test
    fun `off sends nothing and shows nothing`() {
        settings.enabled = false
        assertEquals(emptyList(), get())
        assertFalse(ratings.isOn())
        assertFalse(ratings.mightHaveRatings(lookup))
        assertEquals(0, requests)
    }

    @Test
    fun `no key sends nothing`() {
        settings.apiKey = null
        assertEquals(emptyList(), get())
        settings.apiKey = "   "
        assertEquals(emptyList(), get())
        assertEquals(0, requests)
    }

    @Test
    fun `the key is sent trimmed`() {
        settings.apiKey = " key "
        get()
        assertEquals(listOf("key"), provider.keys.toList())
    }

    @Test
    fun `a second visit comes from the cache`() {
        val first = get()
        assertEquals(found, first)
        now += ExternalRatingsService.FOUND_TTL_MS - 1
        assertEquals(first, get())
        assertEquals(1, requests)
    }

    @Test
    fun `the cache expires after its TTL`() {
        get()
        now += ExternalRatingsService.FOUND_TTL_MS
        get()
        assertEquals(2, requests)
    }

    @Test
    fun `an unknown title is remembered for a day`() {
        provider.answer = RatingsAnswer.NotFound
        assertEquals(emptyList(), get())
        now += ExternalRatingsService.EMPTY_TTL_MS - 1
        get()
        assertEquals(1, requests)
        now += 1
        get()
        assertEquals(2, requests)
    }

    @Test
    fun `a failure is not repeated for a while, and the cached ratings stay`() {
        val cached = get()
        now += ExternalRatingsService.FOUND_TTL_MS
        provider.answer = RatingsAnswer.Failed
        assertEquals(cached, get(), message = "stale ratings on a failure")
        assertFalse(ratings.canRequest(lookup))
        assertEquals(cached, get())
        assertEquals(2, requests)
        now += ExternalRatingsService.FAILURE_BACKOFF_MS
        assertTrue(ratings.canRequest(lookup))
        get()
        assertEquals(3, requests)
    }

    @Test
    fun `a failure of one title does not hold back another`() {
        provider.answer = RatingsAnswer.Failed
        get()
        provider.answer = RatingsAnswer.Found(found)
        assertEquals(found, get(other))
    }

    @Test
    fun `429 stops every request for Retry-After seconds`() {
        provider.answer = RatingsAnswer.RateLimited(600)
        assertEquals(emptyList(), get())
        provider.answer = RatingsAnswer.Found(found)
        assertFalse(ratings.mightHaveRatings(other))
        assertEquals(emptyList(), get(other))
        now += 599_000
        get(other)
        assertEquals(1, requests)
        now += 1_000
        assertEquals(found, get(other))
        assertEquals(2, requests)
    }

    @Test
    fun `429 without Retry-After waits an hour`() {
        provider.answer = RatingsAnswer.RateLimited(null)
        get()
        now += ExternalRatingsService.DEFAULT_BACKOFF_MS - 1
        get(other)
        assertEquals(1, requests)
    }

    @Test
    fun `a Retry-After of more than a day waits a day`() {
        provider.answer = RatingsAnswer.RateLimited(7L * 24 * 60 * 60)
        get()
        provider.answer = RatingsAnswer.Found(found)
        now += 24L * 60 * 60 * 1000
        assertEquals(found, get(other))
    }

    @Test
    fun `a rejected key is not used again until it changes`() {
        provider.answer = RatingsAnswer.InvalidKey
        get()
        assertFalse(ratings.canRequest(other))
        get(other)
        assertEquals(1, requests)
        settings.apiKey = "new-key"
        assertTrue(ratings.canRequest(other))
        get(other)
        assertEquals(2, requests)
    }

    @Test
    fun `the Test button clears a rejected key that works after all`() {
        provider.answer = RatingsAnswer.InvalidKey
        get()
        provider.keyAnswer = RatingsAnswer.Found(emptyList())
        assertEquals(RatingsKeyStatus.VALID, runBlocking { ratings.checkKey("key") })
        assertTrue(ratings.canRequest(lookup))
    }

    @Test
    fun `the Test button reports each answer`() {
        val cases =
            mapOf(
                RatingsAnswer.Found(emptyList()) to RatingsKeyStatus.VALID,
                RatingsAnswer.InvalidKey to RatingsKeyStatus.INVALID,
                RatingsAnswer.RateLimited(null) to RatingsKeyStatus.RATE_LIMITED,
                RatingsAnswer.NotFound to RatingsKeyStatus.UNREACHABLE,
                RatingsAnswer.Failed to RatingsKeyStatus.UNREACHABLE,
            )
        for ((answer, expected) in cases) {
            provider.keyAnswer = answer
            assertEquals(expected, runBlocking { ratings.checkKey("key") }, message = "$answer")
        }
        assertEquals(RatingsKeyStatus.INVALID, runBlocking { ratings.checkKey("  ") })
    }
}
