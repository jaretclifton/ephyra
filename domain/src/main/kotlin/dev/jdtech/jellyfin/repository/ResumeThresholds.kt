package dev.jdtech.jellyfin.repository

/**
 * CE (#637): the server's resume settings (Dashboard → Playback → Resume), which decide what a
 * playback stop does to the position and played state. The server applies them to every stop it
 * receives; the app applies the same ones to its local copy, so a download resumes offline where
 * the server would resume it.
 */
data class ResumeThresholds(
    /** A stop before this percentage keeps no position. */
    val minResumePct: Int,
    /** A stop after this percentage marks the item played. */
    val maxResumePct: Int,
    /** An item shorter than this, in seconds, is marked played instead of keeping a position. */
    val minResumeDurationSeconds: Int,
) {
    companion object {
        /** Jellyfin's defaults, used until the server's own are known. */
        val DEFAULT = ResumeThresholds(5, 90, 300)
    }
}
