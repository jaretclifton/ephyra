package dev.jdtech.jellyfin.server.jellyfin.mappers

import android.net.Uri
import dev.jdtech.jellyfin.models.ItemImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

internal fun BaseItemDto.toItemImages(baseUrl: String): ItemImages {
    val baseUri = Uri.parse(baseUrl)
    val primary =
        imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            baseUri
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.PRIMARY}")
                .appendQueryParameter("tag", tag)
                .build()
                .toString()
        }
    val backdrop =
        backdropImageTags?.firstOrNull()?.let { tag ->
            baseUri
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.BACKDROP}/0")
                .appendQueryParameter("tag", tag)
                .build()
                .toString()
        }
    val logo =
        imageTags?.get(ImageType.LOGO)?.let { tag ->
            baseUri
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.LOGO}")
                .appendQueryParameter("tag", tag)
                .build()
                .toString()
        }
    val showPrimary = seriesPrimaryImageTag?.let { tag ->
        baseUri
            .buildUpon()
            .appendEncodedPath("items/$seriesId/Images/${ImageType.PRIMARY}")
            .appendQueryParameter("tag", tag)
            .build()
            .toString()
    }
    // The series' own backdrop and logo. The parent item id names the owner of the image (the
    // series unless the server inherited it from further up). No tag means the series has none,
    // so these stay null and callers fall back to the episode or season image.
    val showBackdrop =
        parentBackdropImageTags?.firstOrNull()?.let { tag ->
            baseUri
                .buildUpon()
                .appendEncodedPath(
                    "items/${parentBackdropItemId ?: seriesId}/Images/${ImageType.BACKDROP}/0"
                )
                .appendQueryParameter("tag", tag)
                .build()
                .toString()
        }
    val showLogo = parentLogoImageTag?.let { tag ->
        baseUri
            .buildUpon()
            .appendEncodedPath("items/${parentLogoItemId ?: seriesId}/Images/${ImageType.LOGO}")
            .appendQueryParameter("tag", tag)
            .build()
            .toString()
    }
    val thumb =
        imageTags?.get(ImageType.THUMB)?.let { tag ->
            baseUri
                .buildUpon()
                .appendEncodedPath("items/$id/Images/${ImageType.THUMB}")
                .appendQueryParameter("tag", tag)
                .build()
                .toString()
        }
    // The series' thumb. The server names it for an episode; for anything else below a series it
    // is the parent's, which is only sent when the item has no thumb of its own.
    val showThumb =
        seriesThumbImageTag
            ?.takeIf { seriesId != null }
            ?.let { tag ->
                baseUri
                    .buildUpon()
                    .appendEncodedPath("items/$seriesId/Images/${ImageType.THUMB}")
                    .appendQueryParameter("tag", tag)
                    .build()
                    .toString()
            }
            ?: parentThumbImageTag?.let { tag ->
                baseUri
                    .buildUpon()
                    .appendEncodedPath(
                        "items/${parentThumbItemId ?: seriesId}/Images/${ImageType.THUMB}"
                    )
                    .appendQueryParameter("tag", tag)
                    .build()
                    .toString()
            }

    return ItemImages(
        primary = primary,
        backdrop = backdrop,
        logo = logo,
        showPrimary = showPrimary,
        showBackdrop = showBackdrop,
        showLogo = showLogo,
        thumb = thumb,
        showThumb = showThumb,
    )
}
