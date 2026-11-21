package dev.jdtech.jellyfin.models

import java.util.UUID

/**
 * The images of an item: a server URL, or for a download a path relative to the app's files
 * directory ([localImagePath]). A missing image is null, never an empty string.
 */
data class FindroidImages(
    val primary: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val showPrimary: String? = null,
    val showBackdrop: String? = null,
    val showLogo: String? = null,
    // CE (#608): the wide artwork with the title on it. Only the Continue Watching and Next Up
    // rows ask the server for it, so it is null everywhere else.
    val thumb: String? = null,
    val showThumb: String? = null,
)

/**
 * Where a download keeps its [image] (`primary`, `backdrop`) of [itemId], relative to the files
 * directory: `/images/<id>/<image>`, the string that
 * `Uri.Builder().appendEncodedPath("images/<id>/<image>")` gave before #754. It has no scheme,
 * which is how the screens and the player tell it from a server URL.
 */
fun localImagePath(itemId: UUID, image: String): String = "/images/$itemId/$image"

fun FindroidMovieDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
    )
}

fun FindroidShowDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
    )
}

fun FindroidSeasonDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}

fun FindroidEpisodeDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = localImagePath(itemId, "primary"),
        backdrop = localImagePath(itemId, "backdrop"),
        showPrimary = localImagePath(seriesId, "primary"),
        showBackdrop = localImagePath(seriesId, "backdrop"),
    )
}
