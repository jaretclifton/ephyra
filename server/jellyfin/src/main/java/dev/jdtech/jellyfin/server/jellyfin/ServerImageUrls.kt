package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.asString

/**
 * The URL of the thumbnail the server extracted for chapter [chapterIndex] of [itemId], on the
 * server at [baseUrl] (a trailing slash is dropped). Null when no server is set.
 */
internal fun chapterImageUrl(
    baseUrl: String,
    itemId: ItemId,
    chapterIndex: Int,
    tag: String,
): String? {
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    return "$base/Items/${itemId.asString()}/Images/Chapter/$chapterIndex?tag=$tag"
}

/**
 * The URL of the backdrop of [seasonId] on the server at [baseUrl] (a trailing slash is dropped),
 * without a tag. Null when no server is set.
 */
internal fun seasonBackdropUrl(baseUrl: String, seasonId: ItemId): String? {
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    return "$base/items/${seasonId.asString()}/Images/Backdrop/0"
}
