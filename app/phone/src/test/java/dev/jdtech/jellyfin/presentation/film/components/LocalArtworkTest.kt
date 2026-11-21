package dev.jdtech.jellyfin.presentation.film.components

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.jdtech.jellyfin.models.localImagePath
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #754: the image a download stored loads from its path in `FindroidImages`, as the poster cards (a
 * `Uri` model) and the header artwork (a `String` model) hand it to Coil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalArtworkTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val imageLoader = ImageLoader(context)
    private val itemId = UUID.fromString("44444444-4444-4444-4444-444444444444")

    /** An image as ImagesDownloaderWorker saves it with a download. */
    private fun storeImage(image: String) {
        File(context.filesDir, "images/$itemId/$image").apply {
            parentFile?.mkdirs()
            outputStream().use {
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun load(data: Any) = runBlocking {
        imageLoader.execute(ImageRequest.Builder(context).data(data).build())
    }

    @Test
    fun `a stored poster loads from its path`() {
        storeImage("primary")
        val resolved = localImagePath(itemId, "primary").resolveIn(context)

        assertIs<SuccessResult>(load(resolved))
        assertIs<SuccessResult>(load(resolved.toString()))
    }

    @Test
    fun `a path without a stored file fails, so the next source is tried`() {
        val resolved = localImagePath(itemId, "backdrop").resolveIn(context)

        assertIs<ErrorResult>(load(resolved))
        assertIs<ErrorResult>(load(resolved.toString()))
    }

    @Test
    fun `a server URL is left as it is`() {
        val url = "https://server/items/$itemId/Images/Primary?tag=p"

        assertEquals(url, url.resolveIn(context).toString())
    }
}
