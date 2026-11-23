package dev.jdtech.jellyfin.player.local.engine.mpv

/**
 * #869: the position of the item [MPVPlayer] is leaving, while it still names that item.
 *
 * A move to another playlist entry forgets the file's position at once, but the player's current
 * item only changes when mpv reports its new `playlist-current-pos`, some 100 ms later. In between
 * the player named the item it was leaving at position 0 (the next item's start), and a progress
 * report sent then told the server that item was at 0. Until the index moves, the player reports
 * the last position mpv had for the item instead, as ExoPlayer, which changes the item at once,
 * never shows the item left at another position.
 *
 * Kept by the playlist entry itself ([T] is compared by identity), not by its index, so an entry
 * inserted or removed before it meanwhile does not lose it.
 */
internal class MpvItemLeftPosition<T : Any> {
    private var item: T? = null
    private var positionMs: Long? = null

    /**
     * mpv is asked to play [newItem] while the player names [currentItem], at [positionMs] (`null`
     * when mpv never had a position for it). A reload of the current entry keeps nothing.
     */
    fun onLoad(newItem: T?, currentItem: T?, positionMs: Long?) {
        if (currentItem == null || newItem === currentItem || positionMs == null) {
            clear()
            return
        }
        item = currentItem
        this.positionMs = positionMs
    }

    /** The player names another item, failed or starts over: the position no longer applies. */
    fun clear() {
        item = null
        positionMs = null
    }

    /** The position to report while the player names [currentItem], or `null` to read mpv's. */
    fun positionMs(currentItem: T?): Long? = positionMs?.takeIf {
        currentItem != null && currentItem === item
    }
}
