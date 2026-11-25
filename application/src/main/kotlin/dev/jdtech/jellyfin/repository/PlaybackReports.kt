package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId

/**
 * #967: what is reported to the server while an item plays: its play session, the start, the
 * position and the stop. One of the roles the repository was split into; the local player's
 * reporter and Cast use it. A stop also writes the resume position to Room.
 */
interface PlaybackReports {
    /**
     * Records the play session of the stream that plays [itemId] now, which the start, progress and
     * stop reports of [itemId] name: it is the one whose transcode the stop report ends.
     * [transcodes] is whether that stream is the server's transcode.
     *
     * CE (#626): Cast asks with the receiver's device profile, not the phone's. CE (#728): the
     * local player calls this for each stream it loads. `PlaybackMedia.getPlaybackSources` records
     * nothing: a quality or audio switch that fails after asking for the playing item's new stream
     * keeps the old stream, whose session the reports must keep naming.
     *
     * CE (#909): [mediaSourceId] is the version the stream is of; the reports name it, so the
     * server shows the version that plays.
     */
    fun setPlaySession(
        itemId: ItemId,
        playSessionId: String?,
        transcodes: Boolean,
        mediaSourceId: MediaSourceId? = null,
    ) {}

    /**
     * Report that [itemId] started playing at [positionTicks], the player's position when the start
     * is sent (#868); `null` sends no position. [transcoded] says whether the stream playing is the
     * server's transcode of it (`PlayerItem.isTranscoded`), which the server shows as the play
     * method.
     */
    suspend fun postPlaybackStart(itemId: ItemId, positionTicks: Long?, transcoded: Boolean)

    /**
     * Report that [itemId] stopped playing at [positionTicks]. What the local database stores
     * follows the server's rules (see `PlayStop.of` in `domain`): from the stored runtime of a
     * downloaded item and the server's resume settings, or from [playedPercentage] when no runtime
     * is stored. `null`, for an item left before its duration was known, leaves the local database
     * as it is and only tells the server.
     */
    suspend fun postPlaybackStop(itemId: ItemId, positionTicks: Long, playedPercentage: Int?)

    /** Report the position of [itemId]; [transcoded] as for [postPlaybackStart]. */
    suspend fun postPlaybackProgress(
        itemId: ItemId,
        positionTicks: Long,
        isPaused: Boolean,
        transcoded: Boolean,
    )
}
