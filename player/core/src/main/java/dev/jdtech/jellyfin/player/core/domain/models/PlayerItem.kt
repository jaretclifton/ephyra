package dev.jdtech.jellyfin.player.core.domain.models

import android.net.Uri
import android.os.Parcelable
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler

@Parcelize
@TypeParceler<ItemId, ItemIdParceler>
@TypeParceler<ItemId?, NullableItemIdParceler>
@TypeParceler<MediaSourceId, MediaSourceIdParceler>
data class PlayerItem(
    val name: String,
    val itemId: ItemId,
    val mediaSourceId: MediaSourceId,
    val playbackPosition: Long,
    val mediaSourceUri: String = "",
    /** True when [mediaSourceUri] is a server-side transcode (an HLS manifest). */
    val isTranscoded: Boolean = false,
    /** CE (#728): the server's play session of a transcode, which the playback reports name. */
    val playSessionId: String? = null,
    /**
     * CE (#596): the file's audio streams when the server transcodes, and the one this stream was
     * requested with ([ServerAudioTrack.index]). Empty for a stream that plays directly: the player
     * lists and selects its tracks itself.
     */
    val audioTracks: List<ServerAudioTrack> = emptyList(),
    val audioStreamIndex: Int? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val indexNumberEnd: Int? = null,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val chapters: List<PlayerChapter> = emptyList(),
    val trickplayInfo: TrickplayInfo? = null,
    /** Show name for episodes, shown in the media notification. */
    val seriesName: String? = null,
    /** Series of an episode, the key for remembering the audio and subtitle choice. */
    val seriesId: ItemId? = null,
    /** CE (R10): season of an episode, for the phone player's episode sheet. */
    val seasonId: ItemId? = null,
    /** Server URL, or a path relative to filesDir for downloaded items. */
    val artworkUri: Uri? = null,
) : Parcelable
