package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.BoxSet
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.Folder
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import org.jellyfin.sdk.model.api.BaseItemDto

// The items that hold other items: a name and images, little else.

internal fun BaseItemDto.toBoxSet(baseUrl: String): BoxSet {
    return BoxSet(id = id.toItemId(), name = name.orEmpty(), images = toItemImages(baseUrl))
}

internal fun BaseItemDto.toLibrary(baseUrl: String): Library? {
    val type = CollectionType.fromString(collectionType?.serialName)

    if (type !in CollectionType.supported) {
        return null
    }

    return Library(
        id = id.toItemId(),
        name = name.orEmpty(),
        type = type,
        images = toItemImages(baseUrl),
    )
}

internal fun BaseItemDto.toFolder(baseUrl: String): Folder {
    return Folder(
        id = id.toItemId(),
        name = name.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        unplayedItemCount = userData?.unplayedItemCount,
        images = toItemImages(baseUrl),
    )
}
