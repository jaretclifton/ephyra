package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.Movie

/**
 * #967: what a detail page shows besides the item itself: "More like this", the extras, the
 * collections that hold it and a show's theme song. One of the roles the repository was split into.
 * All of it is online only, so each default is "none".
 */
interface ItemExtras {
    /**
     * CE (#332): up to [limit] items like [itemId], for "More like this". The detail pages load it
     * after they are shown. Offline there is nothing to compare with, so the default is empty.
     */
    suspend fun getSimilarItems(itemId: ItemId, limit: Int = SIMILAR_ITEMS_LIMIT): List<Item> =
        emptyList()

    /**
     * CE (#332): the extras of [itemId] — its local trailers, then its special features (behind the
     * scenes, deleted scenes, featurettes…) — as movies the player can play. Each kind is one
     * request, made only when asked for. Online only; the default is empty.
     */
    suspend fun getExtras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie> = emptyList()

    /**
     * CE (#332): the collections (box sets) that contain [itemId], as jellyfin-web's "Included in"
     * finds them (`/Items/{id}/Collections`, Jellyfin 12 and later). Online only; the default is
     * empty.
     */
    suspend fun getItemCollections(itemId: ItemId): List<Item> = emptyList()

    /**
     * CE (#141): a stream URL for the first theme song of [itemId] (a series), or null when the
     * server has none. Offline there is no theme music, so the default is null.
     */
    suspend fun getThemeSongUrl(itemId: ItemId): String? = null
}

/** CE (#332): how many items a "More like this" row shows. */
const val SIMILAR_ITEMS_LIMIT = 12
