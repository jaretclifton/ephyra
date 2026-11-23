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
 */
internal class MpvItemLeftPosition {
    private var index = -1
    private var positionMs: Long? = null

    /**
     * mpv is asked to play entry [newIndex] while the player names [currentIndex], at [positionMs]
     * (`null` when mpv never had a position for it). A reload of the current entry keeps nothing.
     */
    fun onLoad(newIndex: Int, currentIndex: Int, positionMs: Long?) {
        if (newIndex == currentIndex || positionMs == null) {
            clear()
            return
        }
        index = currentIndex
        this.positionMs = positionMs
    }

    /** The player names another item, failed or starts over: the position no longer applies. */
    fun clear() {
        index = -1
        positionMs = null
    }

    /** The position to report while the player names [currentIndex], or `null` to read mpv's. */
    fun positionMs(currentIndex: Int): Long? = positionMs?.takeIf { currentIndex == index }
}
