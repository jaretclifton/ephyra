package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId

/**
 * What the mappers need from the server they map for. Build one per call: the base URL changes when
 * the user switches server.
 */
internal class MapperContext(
    val baseUrl: String,
    /**
     * The static stream URL of a file source; only called when a mapper is asked to include paths.
     */
    val streamUrl: suspend (itemId: ItemId, mediaSourceId: MediaSourceId) -> String,
)
