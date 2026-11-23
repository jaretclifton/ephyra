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
    private val sessions = ConcurrentHashMap<ItemId, String>()
    private val sources = ConcurrentHashMap<ItemId, MediaSourceId>()

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
        if (session != null) sessions[itemId] = session else sessions.remove(itemId)
        if (mediaSourceId != null) sources[itemId] = mediaSourceId else sources.remove(itemId)
    }

    /**
     * CE (#909): the version that plays [itemId], or `null` when none was recorded. Kept after a
     * stop, like the session.
     */
    fun sourceOf(itemId: ItemId): MediaSourceId? = sources[itemId]

    /**
     * The transcode session of [itemId], or `null` when it plays directly. Kept after a stop: the
     * player can go back to an item it left, on the stream it already has.
     */
    fun sessionOf(itemId: ItemId): String? = sessions[itemId]

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
        val session = sessionOf(itemId)
        return if (transcoded && session != null) PlayReport(transcoded = true, session)
        else PlayReport(transcoded = false, null)
    }
}

/**
 * What a start or progress report says (#757): [transcoded] is Transcode with [playSessionId], else
 * DirectPlay without a session. The server adapter turns it into its play method.
 */
data class PlayReport(val transcoded: Boolean, val playSessionId: String?)
