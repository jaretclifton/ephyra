package dev.jdtech.jellyfin.domain.userdata

import dev.jdtech.jellyfin.models.ItemId

/**
 * What the sync does with a row marked to be sent, given what the server has (CE #633, #635). See
 * [PlayState.mergeWithServer].
 */
sealed interface SyncMerge {
    /**
     * Another device played the item after this one did, so the server's progress wins: [stored]
     * replaces the row (the server's played flag, position and date, the row's own favourite) and
     * nothing of this device's progress is sent. A favourite changed here is still sent on its own:
     * [favoriteToSend], null when the server has it already.
     */
    data class TakeServer(val stored: PlayState, val favoriteToSend: Boolean?) : SyncMerge

    /**
     * The row is sent as it is: [state]'s played flag, favourite and position, and its last-played
     * date only when it is newer than the server's ([lastPlayedMillisToSend]; null leaves the
     * server's date as it is). Once sent, the row counts as synced while it still holds [state].
     */
    data class SendLocal(val state: PlayState, val lastPlayedMillisToSend: Long?) : SyncMerge
}

/**
 * CE (#633): whether this state's last-played time is newer than [server]'s. Unknown here is never
 * newer; unknown on the server is always older. An equal time is not newer: there is nothing to
 * move.
 */
fun PlayState.playedAfter(server: Long?): Boolean =
    lastPlayedMillis != null && (server == null || lastPlayedMillis > server)

/**
 * The sync's decision for this row, marked to be sent, against [server], what the server has for
 * the item (null when it has nothing):
 * - CE (#635): the server played it later ([server]'s date is newer than this one's; a row without
 *   a date always loses to a server with one): [SyncMerge.TakeServer].
 * - otherwise [SyncMerge.SendLocal]; CE (#633): the date only when newer, so a device that watched
 *   the item later keeps it at the top of Continue Watching.
 */
fun PlayState.mergeWithServer(server: PlayState?): SyncMerge =
    if (server != null && server.playedAfter(lastPlayedMillis)) {
        SyncMerge.TakeServer(
            stored = server.copy(favorite = favorite),
            favoriteToSend = favorite.takeIf { it != server.favorite },
        )
    } else {
        SyncMerge.SendLocal(
            state = this,
            lastPlayedMillisToSend =
                lastPlayedMillis?.takeIf { playedAfter(server?.lastPlayedMillis) },
        )
    }

/**
 * CE (#635): the server's play states to store in the rows of downloads, in [onServer]'s order. The
 * item open in the player here ([playingHere]) is left out: its own reports keep its row current,
 * and the server's value may be a few seconds behind them. Rows marked to be sent are left alone by
 * the store itself.
 */
fun refreshedFromServer(
    onServer: Map<ItemId, PlayState>,
    playingHere: ItemId?,
): Map<ItemId, PlayState> = onServer.filterKeys { it != playingHere }
