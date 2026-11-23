package dev.jdtech.jellyfin.ratings.mdblist

import dev.jdtech.jellyfin.domain.ratings.ExternalIdType
import dev.jdtech.jellyfin.domain.ratings.ExternalRating
import dev.jdtech.jellyfin.domain.ratings.RatedKind
import dev.jdtech.jellyfin.domain.ratings.RatingSource
import dev.jdtech.jellyfin.domain.ratings.RatingsLookup
import dev.jdtech.jellyfin.ratings.RatingsAnswer
import dev.jdtech.jellyfin.ratings.RatingsProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

/**
 * CE (#331): the two MDBList calls the app makes, a title's ratings and the key check, from the API
 * reference at https://api.mdblist.com/docs/.
 *
 * The key goes in the `apikey` query parameter, as the API asks, and only to [baseUrl]: redirects
 * are not followed, so it can't be passed on to another host. The client has an OkHttp of its own,
 * with nothing of the media server's client, so the key passes no other interceptor or logger.
 * Nothing here logs a URL, since that holds the key; log lines name the status only.
 */
class MdbListClient(
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    newHttpClient: () -> OkHttpClient = ::OkHttpClient,
) : RatingsProvider {
    // Built on first use, off the main thread (see get), not when the page's view model is made.
    private val httpClient by lazy {
        newHttpClient()
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** `GET /{provider}/{type}/{id}/`: the title with its `ratings`. */
    override suspend fun title(lookup: RatingsLookup, apiKey: String): RatingsAnswer {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment(lookup.idType.pathSegment)
                .addPathSegment(lookup.kind.pathSegment)
                .addPathSegment(lookup.id)
                // The documented route ends in a slash.
                .addPathSegment("")
                .addQueryParameter("apikey", apiKey)
                .build()
        return get(url) { body -> parseRatings(body)?.let { RatingsAnswer.Found(it) } }
    }

    /** `GET /user`: answers 200 for a key that works. Found carries no ratings here. */
    override suspend fun checkKey(apiKey: String): RatingsAnswer {
        val url =
            baseUrl.newBuilder().addPathSegment("user").addQueryParameter("apikey", apiKey).build()
        return get(url) { body -> if (isObject(body)) RatingsAnswer.Found(emptyList()) else null }
    }

    private suspend fun get(url: HttpUrl, onSuccess: (String) -> RatingsAnswer?): RatingsAnswer =
        withContext(Dispatchers.IO) { request(url, onSuccess) }

    private suspend fun request(
        url: HttpUrl,
        onSuccess: (String) -> RatingsAnswer?,
    ): RatingsAnswer {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return try {
            httpClient.newCall(request).await().use { response ->
                when (response.code) {
                    200 ->
                        onSuccess(response.body.string())
                            ?: RatingsAnswer.Failed.also {
                                Timber.w("MDBList: unexpected response body")
                            }
                    401,
                    403 -> RatingsAnswer.InvalidKey
                    404 -> RatingsAnswer.NotFound
                    429 ->
                        RatingsAnswer.RateLimited(
                            response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf {
                                it >= 0
                            }
                        )
                    else ->
                        RatingsAnswer.Failed.also { Timber.w("MDBList: HTTP %d", response.code) }
                }
            }
        } catch (e: IOException) {
            // The message of a timeout or a refused connection names no URL; still, keep it out.
            Timber.w("MDBList: request failed (%s)", e.javaClass.simpleName)
            RatingsAnswer.Failed
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mdblist.com/"
        private const val TIMEOUT_SECONDS = 10L

        private val json = Json { ignoreUnknownKeys = true }

        /** The `provider` of MDBList's route. */
        private val ExternalIdType.pathSegment: String
            get() =
                when (this) {
                    ExternalIdType.TMDB -> "tmdb"
                    ExternalIdType.IMDB -> "imdb"
                    ExternalIdType.TVDB -> "tvdb"
                }

        /** The `type` of MDBList's route. */
        private val RatedKind.pathSegment: String
            get() =
                when (this) {
                    RatedKind.MOVIE -> "movie"
                    RatedKind.SHOW -> "show"
                }

        /** The site of an MDBList `source`, for the sites the pages show; null for the others. */
        internal fun sourceOf(name: String): RatingSource? =
            when (name.lowercase()) {
                "imdb" -> RatingSource.IMDB
                "tomatoes" -> RatingSource.ROTTEN_TOMATOES
                "metacritic" -> RatingSource.METACRITIC
                "letterboxd" -> RatingSource.LETTERBOXD
                else -> null
            }

        private fun isObject(body: String): Boolean =
            try {
                json.parseToJsonElement(body) is JsonObject
            } catch (_: Exception) {
                false
            }

        /**
         * The `ratings` of a title body, keeping the sites in [RatingSource] that have a score.
         * MDBList lists every source and leaves `value` null (or 0) when the site has no score for
         * the title. Null when the body is no object or `ratings` is not a list.
         */
        internal fun parseRatings(body: String): List<ExternalRating>? {
            val root =
                try {
                    json.parseToJsonElement(body) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: return null
            val ratings = root["ratings"] ?: return emptyList()
            if (ratings !is JsonArray) return if (ratings is JsonPrimitive) emptyList() else null
            return ratings
                .mapNotNull { element ->
                    val rating = element as? JsonObject ?: return@mapNotNull null
                    val source =
                        (rating["source"] as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?.let(::sourceOf) ?: return@mapNotNull null
                    val value =
                        (rating["value"] as? JsonPrimitive)?.floatOrNull ?: return@mapNotNull null
                    if (value <= 0f || value.isNaN()) return@mapNotNull null
                    ExternalRating(source, value)
                }
                .distinctBy { it.source }
                .sortedBy { it.source.ordinal }
        }
    }
}

/** Runs the call on OkHttp's own threads and cancels it with the coroutine. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }
    )
}
