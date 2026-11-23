package dev.jdtech.jellyfin.server.jellyfin.mappers

import android.net.Uri
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.ItemPersonImage
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import dev.jdtech.jellyfin.server.jellyfin.toModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType

/** A person as an item of its own, for the person page. */
internal fun BaseItemDto.toPerson(baseUrl: String): Person {
    return Person(
        id = id.toItemId(),
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        images = toItemImages(baseUrl),
    )
}

internal fun BaseItemPerson.toItemPersonImage(baseUrl: String): ItemPersonImage {
    val baseUri = Uri.parse(baseUrl)
    return ItemPersonImage(
        uri =
            primaryImageTag?.let { tag ->
                baseUri
                    .buildUpon()
                    .appendEncodedPath("items/$id/Images/${ImageType.PRIMARY}")
                    .appendQueryParameter("tag", tag)
                    .build()
                    .toString()
            },
        blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(primaryImageTag),
    )
}

/** A person as the cast or crew of an item. */
internal fun BaseItemPerson.toPerson(baseUrl: String): ItemPerson {
    return ItemPerson(
        id = id.toItemId(),
        name = name.orEmpty(),
        type = type.toModel(),
        role = role.orEmpty(),
        image = toItemPersonImage(baseUrl),
    )
}
