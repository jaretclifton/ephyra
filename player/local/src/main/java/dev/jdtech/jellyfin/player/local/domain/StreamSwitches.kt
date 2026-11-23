package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.utils.LoadErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * CE (#726): the quality switches and the audio switches of a transcode. Each asks the server for
 * new streams for the whole queue ([rebuildQueue]) and swaps them in, so a second switch can start
 * while the first still waits on the server.
 *
 * One switch runs at a time: a new one cancels the one in flight and asks for streams with both
 * choices, so the latest choice wins for quality and for audio, whatever order the server answers
 * in. A pick of audio that has not reached the player yet ([pendingAudio]) is passed on to the
 * switch that cancels it; each request is made with the latest cap ([wantedBitrate]). A switch that
 * fails shows the cap of the streams that play again ([playingBitrate]) and undoes what the picks
 * of that switch stored ([undoStores]), each in the setting it was stored in, not by storing the
 * cap that plays: the setting is the one of the network in use (CE #837), and the streams that play
 * may have been asked for on the other network. A cancelled switch (a newer one, or the player
 * closing) changes nothing.
 *
 * CE (#837): when the player moves on to an item whose stream was asked for on the other network,
 * [followBitrate] asks for the queue again under the cap of the network in use now, without storing
 * it: it is the setting already. A pick it cancels stays stored. [playingAt] tells the switches the
 * cap an item plays at.
 *
 * Main thread only, like the player: [scope] must run its coroutines there.
 */
class StreamSwitches(
    private val scope: CoroutineScope,
    /** The cap the streams that play were requested with: the setting when the player opened. */
    playingBitrate: Int,
    private val host: Host,
) {
    interface Host {
        /** The player's queue, in its order. */
        fun queue(): List<PlayerItem>

        /** The item that plays and its audio track, as [recreate] takes them. */
        fun playing(): Playing

        /** Forgets an item that left the queue while it got new streams ([rebuildQueue]). */
        fun forget(itemId: ItemId)

        /**
         * [item] with new streams, under the cap [bitrate] (0 = original). [pickedAudio] is the
         * audio stream picked for it, [playingAudio] the track it plays now, each only for the item
         * it is for.
         */
        suspend fun recreate(
            item: PlayerItem,
            bitrate: Int,
            playingAudio: StoredChoice?,
            pickedAudio: Int?,
        ): PlayerItem

        /** Shows [bitrate] as the quality that plays. */
        fun showBitrate(bitrate: Int)

        /**
         * Stores [bitrate] as the quality setting (CE #837: the one of the network in use), and
         * returns how to put back what it replaced, in that same setting.
         */
        fun storeBitrate(bitrate: Int): () -> Unit

        /** Swaps [rebuilt] in, the queue with new streams. Called without suspending after it. */
        fun swapIn(rebuilt: List<PlayerItem>)

        /** The switch failed: the streams that play keep playing. */
        fun failed(error: Exception)
    }

    /** The item that plays when a switch starts, and its audio track (null: unnamed or none). */
    data class Playing(val itemId: ItemId?, val audio: StoredChoice?)

    /** An audio stream picked for [itemId] that is not in the player yet. */
    private class AudioPick(
        val itemId: ItemId,
        val streamIndex: Int,
        /** Waits for what the pick needs first (the series' choice to be saved). */
        val before: suspend () -> Unit,
    )

    private var playingBitrate = playingBitrate
    private var wantedBitrate = playingBitrate
    /** CE (#837): how to undo what the picks of the switch on its way stored, oldest first. */
    private val undoStores = mutableListOf<() -> Unit>()
    private var pendingAudio: AudioPick? = null
    private var job: Job? = null

    /** Switches to [bitrate], 0 for the original quality. */
    fun selectBitrate(bitrate: Int) {
        if (bitrate == wantedBitrate) {
            // CE (#837): no new streams, but the pick is stored: the network in use may have
            // another setting than the one the streams were asked for on. It belongs to the
            // switch on its way, if there is one.
            val undo = host.storeBitrate(bitrate)
            if (job?.isActive == true) undoStores += undo
            return
        }
        wantedBitrate = bitrate
        host.showBitrate(bitrate)
        undoStores += host.storeBitrate(bitrate)
        restart()
    }

    /**
     * CE (#837): asks for the queue again under [bitrate], the setting of the network in use now,
     * because the item that plays was asked for under another cap. Nothing is stored, not even when
     * it fails.
     */
    fun followBitrate(bitrate: Int) {
        // That switch is on its way already.
        if (bitrate == wantedBitrate && job?.isActive == true) return
        // A pick this cancels stays stored: it was the user's choice for its network.
        undoStores.clear()
        wantedBitrate = bitrate
        host.showBitrate(bitrate)
        restart()
    }

    /**
     * CE (#837): the item that plays was asked for under [bitrate] and needs no new streams (it was
     * queued after a network change): a failed switch goes back to it, and the quality shown
     * follows it unless a switch is on its way, which decides that.
     */
    fun playingAt(bitrate: Int) {
        playingBitrate = bitrate
        if (job?.isActive == true || wantedBitrate == bitrate) return
        wantedBitrate = bitrate
        host.showBitrate(bitrate)
    }

    /**
     * Switches [itemId], which plays the audio stream [playingIndex], to the stream [streamIndex].
     * [before] runs in the switch before the streams are requested.
     */
    fun selectAudio(
        itemId: ItemId,
        streamIndex: Int,
        playingIndex: Int?,
        before: suspend () -> Unit = {},
    ) {
        val pending = pendingAudio?.takeIf { it.itemId == itemId }
        // Back to the track that plays: only a pick on its way has to be undone.
        if (streamIndex == playingIndex && pending == null) return
        pendingAudio = AudioPick(itemId, streamIndex, before)
        restart()
    }

    private fun restart() {
        job?.cancel()
        val bitrate = wantedBitrate
        val audio = pendingAudio
        val playing = host.playing()
        job = scope.launch {
            val rebuilt =
                try {
                    audio?.before?.invoke()
                    rebuildQueue(host::queue, host::forget) { item ->
                        host.recreate(
                            item,
                            bitrate = bitrate,
                            playingAudio = playing.audio.takeIf { item.itemId == playing.itemId },
                            pickedAudio = audio?.streamIndex?.takeIf { item.itemId == audio.itemId },
                        )
                    }
                } catch (e: CancellationException) {
                    // A newer switch took over, or the player closed: it owns the setting.
                    throw e
                } catch (e: Exception) {
                    // A request that failed after the cancel is the cancelled switch's too.
                    ensureActive()
                    pendingAudio = null
                    if (wantedBitrate != playingBitrate) {
                        wantedBitrate = playingBitrate
                        host.showBitrate(playingBitrate)
                    }
                    undoStores.asReversed().forEach { it() }
                    undoStores.clear()
                    host.failed(e)
                    return@launch
                }
            // The last stream may come in after the cancel.
            ensureActive()
            host.swapIn(rebuilt)
            playingBitrate = bitrate
            pendingAudio = null
            undoStores.clear()
        }
    }
}

/**
 * CE (#825): what the toast of a failed switch says. The reason in plain words, never the
 * exception's own text (#377), which holds status lines, hosts and class names.
 */
fun switchFailureText(error: Throwable): UiText = LoadErrorText.reason(error)
