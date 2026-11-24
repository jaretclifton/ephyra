package dev.jdtech.jellyfin.player.local.domain

import android.graphics.Bitmap
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.testing.fakeProxy
import dev.jdtech.jellyfin.testing.kotlinName
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #860: the trickplay loaders ask for the sheets of the version that plays, leave a sheet that
 * failed alone for a while instead of asking again on every scrub frame, and do not crash the
 * player on a sheet too large to decode whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrickplayLoaderTest {

    private val itemId = "0f8fad5b-d9cb-469f-a165-70867728950e".toItemId()
    private val version = "b4ad6ac4d1d0ce1a1e2d5d4e3c2b1a09".toMediaSourceId()

    private var now = 0L

    private class Request(val width: Int, val index: Int, val mediaSourceId: MediaSourceId?)

    private class FakeRepository {
        val requests = mutableListOf<Request>()

        /** What the next request answers: bytes, null, or a throwable to throw. */
        var answer: () -> ByteArray? = { null }

        private val unused =
            fakeProxy(
                MediaRepository::class.java.classLoader,
                arrayOf(MediaRepository::class.java),
            ) { _, method, _ ->
                error("Unexpected call to ${method.kotlinName}")
            }
                as MediaRepository

        val repository: MediaRepository =
            object : MediaRepository by unused {
                override suspend fun getTrickplayData(
                    itemId: ItemId,
                    width: Int,
                    index: Int,
                    mediaSourceId: MediaSourceId?,
                ): ByteArray? {
                    synchronized(requests) { requests += Request(width, index, mediaSourceId) }
                    return answer()
                }
            }

        fun count() = synchronized(requests) { requests.size }
    }

    private val fake = FakeRepository()

    /** One sheet of [columns] x [rows] thumbnails of [width] x [height], as a JPEG. */
    private fun sheet(width: Int, height: Int, columns: Int, rows: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width * columns, height * rows, Bitmap.Config.RGB_565)
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            .toByteArray()
            .also { bitmap.recycle() }
    }

    private fun loader(width: Int = 320, height: Int = 180, columns: Int = 10, rows: Int = 10) =
        TrickplayLoader(
            repository = fake.repository,
            itemId = itemId,
            mediaSourceId = version,
            width = width,
            height = height,
            tileWidth = columns,
            tileHeight = rows,
            // One sheet: no neighbours to fetch ahead.
            thumbnailCount = columns * rows,
            interval = 10_000,
            nowMs = { now },
        )

    @Test
    fun `the sheet is asked for the version that plays`() = runBlocking {
        fake.answer = { sheet(320, 180, 10, 10) }
        val loader = loader()

        assertNotNull(loader.tileAt(0))

        val request = fake.requests.single()
        assertEquals(version, request.mediaSourceId)
        assertEquals(320, request.width)
        assertEquals(0, request.index)
    }

    @Test
    fun `a sheet that failed is not asked for again within the window`() = runBlocking {
        val loader = loader()

        assertNull(loader.tileAt(0))
        assertNull(loader.tileAt(5_000))
        assertNull(loader.tileAt(20_000))
        now = TrickplaySheetFailures.RETRY_AFTER_MS - 1
        assertNull(loader.tileAt(30_000))

        assertEquals(1, fake.count())
    }

    @Test
    fun `a sheet that failed is asked for again after the window, and then shows`() = runBlocking {
        val loader = loader()
        assertNull(loader.tileAt(0))

        fake.answer = { sheet(320, 180, 10, 10) }
        now = TrickplaySheetFailures.RETRY_AFTER_MS
        assertNotNull(loader.tileAt(0))
        assertNotNull(loader.tileAt(10_000))

        assertEquals(2, fake.count())
    }

    @Test
    fun `a failed request counts as a failure too`() = runBlocking {
        fake.answer = { throw IOException("timeout") }
        val loader = loader()

        assertNull(loader.tileAt(0))
        assertNull(loader.tileAt(0))

        assertEquals(1, fake.count())
    }

    @Test
    fun `running out of memory on a sheet costs the preview, not the player`() = runBlocking {
        fake.answer = { throw OutOfMemoryError("sheet") }
        val loader = loader()

        assertNull(loader.tileAt(0))
        assertNull(loader.tileAt(0))

        assertEquals(1, fake.count())
    }

    @Test
    fun `a sheet larger than the limit is decoded sampled down`() = runBlocking {
        // 4x4 thumbnails of 1280x720: 5120x2880, 2.6 times the limit, so decoded at half size.
        fake.answer = { sheet(1280, 720, 4, 4) }
        val loader = loader(width = 1280, height = 720, columns = 4, rows = 4)

        val first = assertNotNull(loader.tileAt(0))
        assertEquals(640, first.width)
        assertEquals(360, first.height)
        // The last cell, at the far corner of the sheet, is still inside the sampled sheet.
        val last = assertNotNull(loader.tileAt(15 * 10_000L))
        assertEquals(640, last.width)
    }

    @Test
    fun `a sheet within the limit is decoded at full size`() = runBlocking {
        fake.answer = { sheet(320, 180, 10, 10) }

        val tile = assertNotNull(loader().tileAt(0))

        assertEquals(320, tile.width)
        assertEquals(180, tile.height)
    }

    @Test
    fun `the sample size is the power of two that brings a sheet within the limit`() {
        assertEquals(1, TrickplayLoader.sheetSampleSize(3200, 1800))
        assertEquals(2, TrickplayLoader.sheetSampleSize(3201, 1800))
        assertEquals(2, TrickplayLoader.sheetSampleSize(6400, 3600))
        assertEquals(4, TrickplayLoader.sheetSampleSize(12800, 7200))
    }

    @Test
    fun `the TV loader asks for the version that plays and leaves a failed sheet alone`() =
        runBlocking {
            val loader =
                TrickplayRegionLoader(
                    repository = fake.repository,
                    itemId = itemId,
                    mediaSourceId = version,
                    tiles = TrickplayTiles(320, 180, 10, 10, 100, 10_000),
                    targetWidth = 320,
                    nowMs = { now },
                )

            assertNull(loader.tileAt(0))
            assertNull(loader.tileAt(10_000))
            assertEquals(1, fake.count())
            assertEquals(version, fake.requests.single().mediaSourceId)

            now = TrickplaySheetFailures.RETRY_AFTER_MS
            assertNull(loader.tileAt(20_000))
            assertEquals(2, fake.count())
        }
}
