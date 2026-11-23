package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * For lists: [localSources] comes from one `getLocalSourcesForItems` call (in `data`) for all
 * items.
 */
internal suspend fun BaseItemDto.toItem(
    context: MapperContext,
    localSources: Map<ItemId, List<ItemSource>>,
): Item? {
    return when (type) {
        BaseItemKind.MOVIE -> toMovie(context, localSources)
        BaseItemKind.EPISODE -> toEpisode(context, localSources)
        BaseItemKind.SEASON -> toSeason(context.baseUrl)
        BaseItemKind.SERIES -> toShow(context.baseUrl)
        BaseItemKind.BOX_SET -> toBoxSet(context.baseUrl)
        // CE (#671): a home video or a music video opens and plays as a movie.
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO -> toMovie(context, localSources)
        // CE (#671): what a home-videos library makes of a folder that holds a photo.
        BaseItemKind.FOLDER,
        BaseItemKind.PHOTO_ALBUM -> toFolder(context.baseUrl)
        else -> null
    }
}
