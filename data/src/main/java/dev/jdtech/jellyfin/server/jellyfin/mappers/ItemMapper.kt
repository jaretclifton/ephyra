package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** For lists: [localSources] comes from one [getLocalSourcesForItems] call for all items. */
internal suspend fun BaseItemDto.toFindroidItem(
    context: MapperContext,
    localSources: Map<UUID, List<FindroidSource>>,
): FindroidItem? {
    return when (type) {
        BaseItemKind.MOVIE -> toFindroidMovie(context, localSources)
        BaseItemKind.EPISODE -> toFindroidEpisode(context, localSources)
        BaseItemKind.SEASON -> toFindroidSeason(context.baseUrl)
        BaseItemKind.SERIES -> toFindroidShow(context.baseUrl)
        BaseItemKind.BOX_SET -> toFindroidBoxSet(context.baseUrl)
        // CE (#671): a home video or a music video opens and plays as a movie.
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO -> toFindroidMovie(context, localSources)
        // CE (#671): what a home-videos library makes of a folder that holds a photo.
        BaseItemKind.FOLDER,
        BaseItemKind.PHOTO_ALBUM -> toFindroidFolder(context.baseUrl)
        else -> null
    }
}
