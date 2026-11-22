package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.server.jellyfin.mappers.MapperContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * #756: on the IO dispatcher, with the SDK's failures translated to [ServerException] (see
 * [asServerException]); anything else leaves as it is. The block runs in a scope of its own, so a
 * failing child (search, extras) reaches the translation instead of failing the dispatcher's scope
 * with the SDK's exception.
 */
internal suspend fun <T> call(block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.IO) {
        try {
            coroutineScope(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e.asServerException() ?: e
        }
    }

/** The user the client is signed in as; an [IllegalStateException] when nobody is. */
internal val JellyfinApi.currentUserId: UUID
    get() =
        userId
            ?: throw IllegalStateException(
                "No authenticated user. userId is null — session may have expired."
            )

/** The base URL of the current server, empty when none is set. */
internal fun JellyfinApi.baseUrl(): String = api.baseUrl.orEmpty()

/**
 * What the mappers need from the current server. Built at each mapping, never kept: the base URL
 * changes when the user switches server.
 */
internal fun JellyfinApi.mapperContext() =
    MapperContext(
        baseUrl = baseUrl(),
        streamUrl = { id, source ->
            try {
                videoApi.getVideoStreamUrl(id, static = true, mediaSourceId = source)
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        },
    )
