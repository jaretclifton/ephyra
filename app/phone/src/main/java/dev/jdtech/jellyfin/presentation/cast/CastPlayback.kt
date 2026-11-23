package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.runtime.staticCompositionLocalOf
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.MediaSourceId

/**
 * CE (#626): where a Play button sends its item while a Cast receiver is connected. The screens ask
 * this first and open the local player only when it says no.
 */
fun interface CastPlayback {
    /**
     * Plays the item on the connected receiver. Returns `false` when there is none (always, in a
     * build without Cast), and the caller plays the item on the device.
     *
     * CE (#638): [shuffle] plays a series or season in random order, as the local player does.
     */
    fun play(
        itemId: ItemId,
        itemKind: ItemKind,
        startFromBeginning: Boolean,
        mediaSourceId: MediaSourceId?,
        shuffle: Boolean,
    ): Boolean
}

val LocalCastPlayback = staticCompositionLocalOf { CastPlayback { _, _, _, _, _ -> false } }
