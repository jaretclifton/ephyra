package dev.jdtech.jellyfin.models

import android.net.Uri
import java.util.UUID

data class FindroidImages(
    val primary: Uri? = null,
    val backdrop: Uri? = null,
    val logo: Uri? = null,
    val showPrimary: Uri? = null,
    val showBackdrop: Uri? = null,
    val showLogo: Uri? = null,
    // CE (#608): the wide artwork with the title on it. Only the Continue Watching and Next Up
    // rows ask the server for it, so it is null everywhere else.
    val thumb: Uri? = null,
    val showThumb: Uri? = null,
)

fun FindroidMovieDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
    )
}

fun FindroidShowDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
    )
}

fun FindroidSeasonDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
        showPrimary = Uri.Builder().appendEncodedPath("images/$seriesId/primary").build(),
        showBackdrop = Uri.Builder().appendEncodedPath("images/$seriesId/backdrop").build(),
    )
}

fun FindroidEpisodeDto.toLocalFindroidImages(itemId: UUID): FindroidImages {
    return FindroidImages(
        primary = Uri.Builder().appendEncodedPath("images/$itemId/primary").build(),
        backdrop = Uri.Builder().appendEncodedPath("images/$itemId/backdrop").build(),
        showPrimary = Uri.Builder().appendEncodedPath("images/$seriesId/primary").build(),
        showBackdrop = Uri.Builder().appendEncodedPath("images/$seriesId/backdrop").build(),
    )
}
