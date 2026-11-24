package dev.jdtech.jellyfin.player.local.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * CE (#860): the trickplay sheets whose last load failed, and when. A scrub asks for a sheet on
 * every frame, so without this a sheet that failed (a slow server, a missing image, a sheet too
 * large to decode) was asked for again at once, frame after frame. A failed sheet is left alone for
 * [retryAfterMs], then may be tried again. [nowMs] is a monotonic clock in milliseconds.
 */
class TrickplaySheetFailures(
    private val nowMs: () -> Long,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
) {
    private val failedAt = ConcurrentHashMap<Int, Long>()

    /** Whether [sheetIndex] may be loaded now: it did not fail, or failed long enough ago. */
    fun mayTry(sheetIndex: Int): Boolean {
        val at = failedAt[sheetIndex] ?: return true
        if (nowMs() - at < retryAfterMs) return false
        failedAt.remove(sheetIndex, at)
        return true
    }

    fun failed(sheetIndex: Int) {
        failedAt[sheetIndex] = nowMs()
    }

    fun loaded(sheetIndex: Int) {
        failedAt.remove(sheetIndex)
    }

    companion object {
        const val RETRY_AFTER_MS = 30_000L
    }
}
