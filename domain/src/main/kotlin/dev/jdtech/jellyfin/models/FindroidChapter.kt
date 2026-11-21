package dev.jdtech.jellyfin.models

import kotlinx.serialization.Serializable

@Serializable
data class FindroidChapter(
    /** The start position. */
    val startPosition: Long,
    /** The name. */
    val name: String? = null,
    /** The Jellyfin image tag for the chapter thumbnail, if one was extracted server-side. */
    val imageTag: String? = null,
)
