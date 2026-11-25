package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.Segment

/**
 * #967: what is played or downloaded of an item besides its metadata: its sources, its segments,
 * its trickplay sheets and its chapter images. One of the roles the repository was split into; a
 * download's copy is used when there is one.
 */
interface PlaybackMedia {
    /**
     * Resolves the sources for downloading an item. Always asks for the original file, except —
     * when [transcodeDolbyVision] is set — Dolby Vision files, which the server then transcodes to
     * a device-compatible H.264 copy so they play offline. Every non-DV file is still the original
     * regardless of the flag.
     *
     * [maxBitrate] (bits/sec) is the user-selected download quality cap. Files whose bitrate
     * exceeds it get a progressive server-side transcode down to the cap; files under it still
     * download as the original. Null = original quality for everything.
     */
    suspend fun getMediaSources(
        itemId: ItemId,
        includePath: Boolean = false,
        transcodeDolbyVision: Boolean = false,
        maxBitrate: Int? = null,
    ): List<ItemSource>

    /**
     * Resolves the sources to play an item. Unlike [getMediaSources] (which always asks for the
     * original file and is shared with the downloader), this sends an honest device profile so the
     * server transcodes anything the player cannot direct-play — notably Dolby Vision on hardware
     * without a working DV pipeline.
     *
     * When a downloaded copy exists and [mediaSourceId] is null or names it, the server is not
     * asked: the player plays the local copy then anyway, and waiting on the server only delays it.
     *
     * CE (#580): [audioStreamIndex] names the audio stream of [mediaSourceId] that a transcode has
     * to carry, as a transcode carries one. The server only takes it together with the source's id.
     * It means nothing for a source that plays directly or from a download.
     *
     * CE (#766): [target] is who plays the stream and [maxBitrate] the cap in bits per second (null
     * is none). The caller decides both; the repository reads no player preference.
     */
    suspend fun getPlaybackSources(
        itemId: ItemId,
        target: PlaybackTarget,
        mediaSourceId: MediaSourceId? = null,
        audioStreamIndex: Int? = null,
        maxBitrate: Int? = null,
    ): List<ItemSource>

    suspend fun getSegments(itemId: ItemId): List<Segment>

    /**
     * CE (#693): the item's segments as the server has them now, not the ones stored with a
     * download. A new download saves these.
     */
    suspend fun getServerSegments(itemId: ItemId): List<Segment> = getSegments(itemId)

    /**
     * Sheet [index] of the trickplay images [width] wide of [itemId]. CE (#860): [mediaSourceId] is
     * the version that is played, whose sheets these are, from a download or the server; null keeps
     * what it did before: any downloaded version's sheets, else the server's for the item.
     */
    suspend fun getTrickplayData(
        itemId: ItemId,
        width: Int,
        index: Int,
        mediaSourceId: MediaSourceId? = null,
    ): ByteArray?

    /** The URL of the thumbnail the server extracted for a chapter. Null when no server is set. */
    fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String?
}
