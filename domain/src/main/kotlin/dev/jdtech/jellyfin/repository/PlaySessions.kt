package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import java.util.concurrent.ConcurrentHashMap

/**
 * The play session the server opened for each item it transcodes. The stop report names it, which
 * is what makes the server end the transcode: without it ffmpeg runs on until the server's own
 * timer ends it, a minute after the last segment was fetched.
 */
class PlaySessions {
    /**
     * CE (#909): the session and the version of each item as one value, written in one go, so a
     * report never takes the session of one stream and the version of another.
     */
    private val streams = ConcurrentHashMap<ItemId, PlayingStream>()

    /**
     * Records the session of the stream that plays [itemId] now (see the repository's
     * `setPlaySession`). A source that plays directly has no transcode to end, so its session is
     * not kept. A new stream for the same item (a quality switch) replaces the session: the stream
     * it replaces is left to the server's timer, the server has no call to end it.
     *
     * CE (#909): [mediaSourceId] is the version that stream is of, which every report names, so the
     * server shows the version that plays, not the item's first one. `null` names none.
     */
    fun onPlaybackInfo(
        itemId: ItemId,
        playSessionId: String?,
        transcodes: Boolean,
        mediaSourceId: MediaSourceId? = null,
    ) {
        val session = playSessionId?.takeIf { transcodes && it.isNotBlank() }
        if (session == null && mediaSourceId == null) streams.remove(itemId)
        else streams[itemId] = PlayingStream(session, mediaSourceId)
    }

    /**
     * The stream that plays [itemId] as far as the reports need it, or `null` when nothing was
     * recorded. Kept after a stop: the player can go back to an item it left, on the stream it
     * already has. A stop report reads it once and names both.
     */
    fun streamOf(itemId: ItemId): PlayingStream? = streams[itemId]

    /** The transcode session of [itemId], or `null` when it plays directly. */
    fun sessionOf(itemId: ItemId): String? = streamOf(itemId)?.playSessionId

    /** CE (#909): the version that plays [itemId], or `null` when none was recorded. */
    fun sourceOf(itemId: ItemId): MediaSourceId? = streamOf(itemId)?.mediaSourceId

    /**
     * CE (#599): what a start or progress report of [itemId] says it plays. [transcoded] is whether
     * the stream playing is the server's transcode.
     *
     * The server never offers a direct stream (a remux): a source it cannot direct-play gets a
     * transcoding URL, remux or not, and its dashboard tells a remux from the transcode's own
     * details, which it keeps only while the reports say Transcode. So a transcode is reported as
     * such, never as DirectStream. The server accepts Transcode only together with the play session
     * of a running transcode job, and takes it for DirectPlay otherwise; a start sent before the
     * player fetched the stream is therefore DirectPlay until the first progress report.
     */
    fun playReport(itemId: ItemId, transcoded: Boolean): PlayReport {
        val stream = streamOf(itemId)
        val session = stream?.playSessionId
        return if (transcoded && session != null) {
            PlayReport(transcoded = true, session, stream.mediaSourceId)
        } else {
            PlayReport(transcoded = false, null, stream?.mediaSourceId)
        }
    }
}

/**
 * What the reports of an item name of the stream that plays it: its transcode session (`null` when
 * it plays directly) and, CE (#909), its version.
 */
data class PlayingStream(val playSessionId: String?, val mediaSourceId: MediaSourceId?)

/**
 * What a start or progress report says (#757): [transcoded] is Transcode with [playSessionId], else
 * DirectPlay without a session. The server adapter turns it into its play method. CE (#909):
 * [mediaSourceId] is the version that plays, `null` when none was recorded.
 */
data class PlayReport(
    val transcoded: Boolean,
    val playSessionId: String?,
    val mediaSourceId: MediaSourceId? = null,
)
