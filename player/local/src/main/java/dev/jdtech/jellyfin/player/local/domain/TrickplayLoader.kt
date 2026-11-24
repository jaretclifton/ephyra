package dev.jdtech.jellyfin.player.local.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayTileLoader
import dev.jdtech.jellyfin.repository.MediaRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Lazily decodes trickplay sprite-sheets on demand and crops out the right cell for the requested
 * playback position. Sprite-sheets are decoded once and held in an LRU cache, keyed by sheet index,
 * so seeks within a sheet are essentially free and seeks across sheets only re-decode once.
 *
 * The cache is three sheets: the one being scrubbed and the one on either side, which are loaded
 * ahead so crossing a sheet boundary doesn't wait for the network (#615). Sheets are decoded as
 * RGB_565: a sheet of 10x10 thumbnails at 320x180 is 3200x1800 pixels, about 11.5 MB that way and
 * twice that as ARGB_8888, and a JPEG has no alpha to lose.
 *
 * CE (#860): the sheets are those of [mediaSourceId], the version that plays (null: the item's
 * own). A sheet larger than [MAX_SHEET_PIXELS] is decoded sampled down, so its tiles come out
 * smaller instead of the decode running out of memory; a sheet that fails is not asked for again
 * for [TrickplaySheetFailures.RETRY_AFTER_MS], by [nowMs].
 */
class TrickplayLoader(
    private val repository: MediaRepository,
    private val itemId: ItemId,
    private val mediaSourceId: MediaSourceId?,
    private val width: Int,
    private val height: Int,
    tileWidth: Int,
    tileHeight: Int,
    thumbnailCount: Int,
    interval: Int,
    /** Max number of decoded sprite-sheets to keep in memory. */
    cacheSize: Int = DEFAULT_CACHE_SIZE,
    /** A monotonic clock in milliseconds, for how long a failed sheet is left alone. */
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** The most pixels a sheet is decoded with; a larger one is sampled down. */
    private val maxSheetPixels: Long = MAX_SHEET_PIXELS,
    /** Copies one cell out of a decoded sheet. A parameter only so tests can fail it. */
    private val cropTile: (sheet: Bitmap, x: Int, y: Int, width: Int, height: Int) -> Bitmap =
        { sheet, x, y, w, h ->
            Bitmap.createBitmap(sheet, x, y, w, h)
        },
) : TrickplayTileLoader {

    private val tiles =
        TrickplayTiles(width, height, tileWidth, tileHeight, thumbnailCount, interval)

    // We deliberately do not call recycle() in entryRemoved. tileAt holds a reference to
    // the evicted bitmap and reads from it on Dispatchers.Default after the LruCache lookup;
    // recycling on the main thread mid-read would crash with
    // "Canvas: trying to use a recycled bitmap". Bitmap pixels are GC-managed, so we let the
    // GC reclaim evicted entries.
    private val sheetCache: LruCache<Int, Sheet> = LruCache(cacheSize)

    private val failures = TrickplaySheetFailures(nowMs)

    // The loads in flight, by sheet index, so two scrub frames that want the same sheet share one
    // download. A load runs in [loaderScope], not in the caller's job: the seek bar cancels the
    // job of every frame it has moved past, and a download cancelled with it would start again on
    // the next frame, so a sheet could take many attempts to arrive (#615).
    private val loads = ConcurrentHashMap<Int, Deferred<Sheet?>>()

    /** Runs the sheet loads; cancelled by [release]. */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun tileAt(positionMs: Long): Bitmap? {
        val location = tiles.locate(positionMs) ?: return null
        val sheetIndex = location.sheetIndex

        val sheet = loadSheet(sheetIndex) ?: return null
        prefetchNeighbours(sheetIndex)

        // A sheet decoded sampled down has its cells sampled down by as much.
        val bitmap = sheet.bitmap
        val scale = sheet.sampleSize
        val offsetX = location.offsetX / scale
        val offsetY = location.offsetY / scale
        val tileWidth = (width / scale).coerceAtLeast(1)
        val tileHeight = (height / scale).coerceAtLeast(1)
        // Defensive bounds check — a corrupt/short sheet shouldn't crash the player.
        if (offsetX + tileWidth > bitmap.width || offsetY + tileHeight > bitmap.height) {
            Timber.w(
                "Trickplay tile out of bounds: sheet=$sheetIndex tile=${location.index} " +
                    "offset=($offsetX,$offsetY) sheet=${bitmap.width}x${bitmap.height}"
            )
            return null
        }

        return withContext(Dispatchers.Default) {
            // createBitmap copies the pixels into a fresh bitmap. The cropped tile is
            // independent of the cached sheet, so it survives sheet eviction.
            try {
                cropTile(bitmap, offsetX, offsetY, tileWidth, tileHeight)
            } catch (e: OutOfMemoryError) {
                // Memory is short: drop the sheet and leave it alone for the window, so the
                // next move event neither crops nor logs again (#860).
                Timber.e(e, "No memory for trickplay tile ${location.index}")
                sheetCache.remove(sheetIndex)
                failures.failed(sheetIndex)
                null
            }
        }
    }

    /**
     * CE (#615): fetches the sheets next to [sheetIndex] in the background. Only called while the
     * user scrubs, so playback that is never scrubbed downloads nothing.
     */
    private fun prefetchNeighbours(sheetIndex: Int) {
        for (neighbour in tiles.neighbourSheets(sheetIndex)) {
            if (sheetCache.get(neighbour) == null && failures.mayTry(neighbour)) {
                sheetLoad(neighbour).start()
            }
        }
    }

    private suspend fun loadSheet(sheetIndex: Int): Sheet? {
        // A sheet that failed a moment ago would fail again: every scrub frame asked anew (#860).
        if (!failures.mayTry(sheetIndex)) return null
        sheetCache
            .get(sheetIndex)
            ?.takeIf { !it.bitmap.isRecycled }
            ?.let {
                return it
            }
        if (sheetIndex < 0 || sheetIndex >= tiles.sheetCount) return null
        return try {
            sheetLoad(sheetIndex).await()
        } catch (e: CancellationException) {
            // The caller was cancelled: pass it on. Otherwise the loader was released.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** The load of [sheetIndex] that is in flight, or a new one. Lazy: await or start it. */
    private fun sheetLoad(sheetIndex: Int): Deferred<Sheet?> =
        loads.computeIfAbsent(sheetIndex) {
            loaderScope.async(start = CoroutineStart.LAZY) {
                try {
                    val sheet =
                        repository
                            .getTrickplayData(itemId, width, sheetIndex, mediaSourceId)
                            ?.let(::decodeSheet)
                    if (sheet == null) {
                        Timber.w("No trickplay sheet $sheetIndex")
                        failures.failed(sheetIndex)
                    } else {
                        sheetCache.put(sheetIndex, sheet)
                        failures.loaded(sheetIndex)
                    }
                    sheet
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load trickplay sheet $sheetIndex")
                    failures.failed(sheetIndex)
                    null
                } catch (e: OutOfMemoryError) {
                    Timber.e(e, "No memory for trickplay sheet $sheetIndex")
                    failures.failed(sheetIndex)
                    null
                } finally {
                    loads.remove(sheetIndex)
                }
            }
        }

    /** The sheet in [bytes], sampled down to at most [maxSheetPixels]; null if not an image. */
    private fun decodeSheet(bytes: ByteArray): Sheet? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = sheetSampleSize(bounds.outWidth, bounds.outHeight, maxSheetPixels)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let {
            Sheet(it, sampleSize)
        }
    }

    /** A decoded sheet, [sampleSize] times smaller each way than the server's image. */
    private class Sheet(val bitmap: Bitmap, val sampleSize: Int)

    override fun release() {
        loaderScope.cancel()
        sheetCache.evictAll()
    }

    companion object {
        const val DEFAULT_CACHE_SIZE = 3

        /**
         * The most pixels a sheet is decoded with: a 10x10 sheet of 320-wide thumbnails of any
         * shape up to portrait 9:16 (320x569 tiles, 3200x5690), so every sheet at Jellyfin's
         * default width keeps its full size: 16:9 (3200x1800, 11.5 MB as RGB_565), 4:3 (3200x2400)
         * and portrait (about 37 MB). A larger sheet, such as 10x10 of 640 or 1280 wide, is sampled
         * down to fit (#860).
         */
        const val MAX_SHEET_PIXELS = 3200L * 5760L

        /** The power of two that brings a [width] x [height] sheet within [maxPixels]. */
        fun sheetSampleSize(width: Int, height: Int, maxPixels: Long = MAX_SHEET_PIXELS): Int {
            var sampleSize = 1
            while ((width / sampleSize).toLong() * (height / sampleSize) > maxPixels) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
