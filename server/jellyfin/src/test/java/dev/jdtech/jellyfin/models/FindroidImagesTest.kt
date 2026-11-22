package dev.jdtech.jellyfin.models

import android.app.Application
import android.net.Uri
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidImages
import dev.jdtech.jellyfin.server.jellyfin.toItemId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for android.net.Uri, which the mapper builds the URLs with.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FindroidImagesTest {
    private val base = "https://jf.example"
    private val seriesId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val episodeId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val otherId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun dto(
        kind: BaseItemKind,
        backdropTags: List<String>? = null,
        logoTag: String? = null,
        backdropItemId: UUID? = null,
        logoItemId: UUID? = null,
    ) =
        BaseItemDto(
            id = episodeId,
            type = kind,
            seriesId = seriesId,
            seriesPrimaryImageTag = "poster",
            parentBackdropImageTags = backdropTags,
            parentBackdropItemId = backdropItemId,
            parentLogoImageTag = logoTag,
            parentLogoItemId = logoItemId,
            imageTags = mapOf(ImageType.PRIMARY to "own"),
        )

    @Test
    fun `episode of a series with backdrop and logo uses the series own tags`() {
        val images = dto(BaseItemKind.EPISODE, listOf("bd", "bd2"), "lg").toFindroidImages(base)

        assertEquals("$base/items/$seriesId/Images/Backdrop/0?tag=bd", images.showBackdrop)
        assertEquals("$base/items/$seriesId/Images/Logo?tag=lg", images.showLogo)
        assertEquals("$base/items/$seriesId/Images/Primary?tag=poster", images.showPrimary)
    }

    @Test
    fun `season of a series with backdrop and logo uses the series own tags`() {
        val images = dto(BaseItemKind.SEASON, listOf("bd"), "lg").toFindroidImages(base)

        assertEquals("$base/items/$seriesId/Images/Backdrop/0?tag=bd", images.showBackdrop)
        assertEquals("$base/items/$seriesId/Images/Logo?tag=lg", images.showLogo)
    }

    @Test
    fun `episode of a series without backdrop or logo leaves them null despite a poster`() {
        val images = dto(BaseItemKind.EPISODE).toFindroidImages(base)

        assertNull(images.showBackdrop)
        assertNull(images.showLogo)
        assertEquals("$base/items/$seriesId/Images/Primary?tag=poster", images.showPrimary)
        assertEquals("$base/items/$episodeId/Images/Primary?tag=own", images.primary)
    }

    @Test
    fun `season of a series without backdrop or logo leaves them null despite a poster`() {
        val images = dto(BaseItemKind.SEASON, backdropTags = emptyList()).toFindroidImages(base)

        assertNull(images.showBackdrop)
        assertNull(images.showLogo)
    }

    @Test
    fun `an image inherited from another item is addressed by its owner`() {
        val images =
            dto(
                    BaseItemKind.EPISODE,
                    listOf("bd"),
                    "lg",
                    backdropItemId = otherId,
                    logoItemId = otherId,
                )
                .toFindroidImages(base)

        assertEquals("$base/items/$otherId/Images/Backdrop/0?tag=bd", images.showBackdrop)
        assertEquals("$base/items/$otherId/Images/Logo?tag=lg", images.showLogo)
    }

    // #608
    @Test
    fun `an episode names its series thumb, a movie its own`() {
        val episode =
            dto(BaseItemKind.EPISODE).copy(seriesThumbImageTag = "th").toFindroidImages(base)
        assertEquals("$base/items/$seriesId/Images/Thumb?tag=th", episode.showThumb)
        assertNull(episode.thumb)

        val movie =
            BaseItemDto(
                    id = otherId,
                    type = BaseItemKind.MOVIE,
                    imageTags = mapOf(ImageType.THUMB to "mt"),
                )
                .toFindroidImages(base)
        assertEquals("$base/items/$otherId/Images/Thumb?tag=mt", movie.thumb)
        assertNull(movie.showThumb)
    }

    @Test
    fun `a thumb inherited from a parent is addressed by its owner`() {
        val images =
            dto(BaseItemKind.SEASON)
                .copy(parentThumbImageTag = "pt", parentThumbItemId = otherId)
                .toFindroidImages(base)
        assertEquals("$base/items/$otherId/Images/Thumb?tag=pt", images.showThumb)
    }

    @Test
    fun `without thumb tags there is no thumb`() {
        val images = dto(BaseItemKind.EPISODE, listOf("bd"), "lg").toFindroidImages(base)
        assertNull(images.thumb)
        assertNull(images.showThumb)
    }

    // #754: the stored images of a download are the strings the Uri they replaced printed.
    @Test
    fun `a downloaded movie's images are the paths the Uri builder gave`() {
        val images =
            FindroidMovieDto(
                    id = episodeId,
                    serverId = null,
                    name = "Movie",
                    originalTitle = null,
                    overview = "",
                    runtimeTicks = 0L,
                    premiereDate = null,
                    communityRating = null,
                    officialRating = null,
                    status = "",
                    productionYear = null,
                    endDate = null,
                    chapters = null,
                )
                .toLocalFindroidImages(episodeId)

        assertEquals("/images/$episodeId/primary", images.primary)
        assertEquals(oldLocalUri("images/$episodeId/primary"), images.primary)
        assertEquals(oldLocalUri("images/$episodeId/backdrop"), images.backdrop)
        assertNull(images.logo)
    }

    @Test
    fun `a downloaded episode names its show's images by the series id`() {
        assertEquals(
            oldLocalUri("images/$seriesId/primary"),
            localImagePath(seriesId.toItemId(), "primary"),
        )
        assertEquals(
            oldLocalUri("images/$seriesId/backdrop"),
            localImagePath(seriesId.toItemId(), "backdrop"),
        )
    }

    /** How the local images were built before #754. */
    private fun oldLocalUri(path: String) = Uri.Builder().appendEncodedPath(path).build().toString()
}
