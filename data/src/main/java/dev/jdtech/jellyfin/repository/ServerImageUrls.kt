package dev.jdtech.jellyfin.repository

import java.util.UUID

/**
 * The URL of the thumbnail the server extracted for chapter [chapterIndex] of [itemId], on the
 * server at [baseUrl] (a trailing slash is dropped). Null when no server is set.
 */
internal fun chapterImageUrl(
    baseUrl: String,
    itemId: UUID,
    chapterIndex: Int,
    tag: String,
): String? {
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    return "$base/Items/$itemId/Images/Chapter/$chapterIndex?tag=$tag"
}

/**
 * The URL of the backdrop of [seasonId] on the server at [baseUrl] (a trailing slash is dropped),
 * without a tag. Null when no server is set.
 */
internal fun seasonBackdropUrl(baseUrl: String, seasonId: UUID): String? {
    val base = baseUrl.trimEnd('/')
    if (base.isEmpty()) return null
    return "$base/items/$seasonId/Images/Backdrop/0"
}
