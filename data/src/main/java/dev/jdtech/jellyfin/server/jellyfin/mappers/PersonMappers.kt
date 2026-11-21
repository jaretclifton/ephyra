package dev.jdtech.jellyfin.server.jellyfin.mappers

import android.net.Uri
import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidItemPersonImage
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.server.jellyfin.toModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType

/** A person as an item of its own, for the person page. */
internal fun BaseItemDto.toFindroidPerson(baseUrl: String): FindroidPerson {
    return FindroidPerson(
        id = id,
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        images = toFindroidImages(baseUrl),
    )
}

internal fun BaseItemPerson.toFindroidImage(baseUrl: String): FindroidItemPersonImage {
    val baseUri = Uri.parse(baseUrl)
    return FindroidItemPersonImage(
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
internal fun BaseItemPerson.toFindroidPerson(baseUrl: String): FindroidItemPerson {
    return FindroidItemPerson(
        id = id,
        name = name.orEmpty(),
        type = type.toModel(),
        role = role.orEmpty(),
        image = toFindroidImage(baseUrl),
    )
}
