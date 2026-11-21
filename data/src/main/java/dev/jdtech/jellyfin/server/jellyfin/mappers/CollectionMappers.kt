package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidFolder
import org.jellyfin.sdk.model.api.BaseItemDto

// The items that hold other items: a name and images, little else.

internal fun BaseItemDto.toFindroidBoxSet(baseUrl: String): FindroidBoxSet {
    return FindroidBoxSet(id = id, name = name.orEmpty(), images = toFindroidImages(baseUrl))
}

internal fun BaseItemDto.toFindroidCollection(baseUrl: String): FindroidCollection? {
    val type = CollectionType.fromString(collectionType?.serialName)

    if (type !in CollectionType.supported) {
        return null
    }

    return FindroidCollection(
        id = id,
        name = name.orEmpty(),
        type = type,
        images = toFindroidImages(baseUrl),
    )
}

internal fun BaseItemDto.toFindroidFolder(baseUrl: String): FindroidFolder {
    return FindroidFolder(
        id = id,
        name = name.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        unplayedItemCount = userData?.unplayedItemCount,
        images = toFindroidImages(baseUrl),
    )
}
