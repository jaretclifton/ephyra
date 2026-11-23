package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.SeriesTrackPreference

/**
 * CE (#596): what a track dialog lists: the [names] of the tracks in order, and the position of the
 * one that plays, -1 when the track type is off.
 */
data class TrackChoices(val names: List<String>, val selected: Int)

/**
 * A track picked by hand, to apply again when an item's stream is loaded: the track [choice]
 * describes, or, when it is null, the track type turned off.
 */
data class TrackPick(val trackType: Int, val choice: StoredChoice?)

fun SeriesTrackPreference.toTrackPick(): TrackPick =
    TrackPick(trackType, if (disabled) null else StoredChoice(language, title, positionInLanguage))

/**
 * Names the tracks a choice was applied to: the item, and how many audio and subtitle tracks it
 * had. The choice is applied once per key, so again when the item gets more tracks: mpv lists an
 * item's subtitle files after the tracks of its stream.
 */
fun trackPreferenceKey(itemId: String, selectableTracks: Int): String = "$itemId:$selectableTracks"

/**
 * CE (#596): the tracks picked by hand in items without a series, such as a movie, for as long as
 * the player is open. A series has its saved choice ([SeriesTrackPreference]); a movie had nothing,
 * so it went back to its default tracks whenever its stream was loaded again: on a quality switch,
 * and on an audio switch while the server transcodes.
 */
class SessionTrackPicks {
    private val picks = HashMap<ItemId, MutableMap<Int, TrackPick>>()

    /** Keeps [pick] for [itemId], in place of an earlier pick of the same track type. */
    fun remember(itemId: ItemId, pick: TrackPick) {
        picks.getOrPut(itemId) { LinkedHashMap() }[pick.trackType] = pick
    }

    /** Drops the pick of [trackType] for [itemId]: the item's own default applies again. */
    fun forget(itemId: ItemId, trackType: Int) {
        picks[itemId]?.remove(trackType)
    }

    fun of(itemId: ItemId): List<TrackPick> = picks[itemId]?.values?.toList().orEmpty()
}
