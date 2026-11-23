package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ItemId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells screens when the player has finished reporting an item. That happens after the player has
 * closed, so later than the moment the screen underneath it resumes.
 */
class PlaybackEvents(private val dataChanges: DataChangeEvents = DataChangeEvents()) {
    private val _stopped =
        MutableSharedFlow<ItemId>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    /** Emits the id of the item whose playback stop has been reported. */
    val stopped: SharedFlow<ItemId> = _stopped.asSharedFlow()

    private val _playingItemId = MutableStateFlow<ItemId?>(null)
    /** The item the local player has open right now, or null when no player is active. */
    val playingItemId: StateFlow<ItemId?> = _playingItemId.asStateFlow()

    fun onStopped(itemId: ItemId) {
        dataChanges.playbackPending = false
        dataChanges.onChanged()
        _stopped.tryEmit(itemId)
    }

    /**
     * CE (#846): the player left an item that owes no stop report (closed or moved on before the
     * item loaded). No stop event follows, so screens must not wait for one: [ReloadPolicy] would
     * otherwise skip every reload on resume until some later stop is reported.
     */
    fun onLeftWithoutStop() {
        dataChanges.playbackPending = false
    }

    fun onPlaying(itemId: ItemId?) {
        if (itemId != null) dataChanges.playbackPending = true
        _playingItemId.value = itemId
    }
}
