package dev.jdtech.jellyfin.domain.userdata

/**
 * The play state of one item for one user (#898): whether it was played, whether it is a favourite,
 * where to resume and when it was last played. The rules that change it are here and nowhere else;
 * the repository and the sync read a row, ask this class what to write and what to send, and do the
 * I/O. Times are parameters, in epoch milliseconds: nothing here reads a clock.
 *
 * Every method returns the state to write. The local database holds one for each downloaded item
 * (`UserDataEntity` in `data`); for an item without a row there is nothing to write.
 */
data class PlayState(
    val played: Boolean,
    val favorite: Boolean,
    val positionTicks: Long,
    /** When the item was last played, in epoch milliseconds; null when unknown. */
    val lastPlayedMillis: Long?,
) {
    /**
     * A played mark at [at]. CE (#633): the last-played date is set only when unknown, as the
     * server does for a played mark without a date. The position stays as it is.
     */
    fun markedPlayed(at: Long): PlayState =
        copy(played = true, lastPlayedMillis = lastPlayedMillis ?: at)

    /**
     * An unplayed mark taken at [markedAt], the time before the request is sent (CE #635): a
     * request that fails only after a timeout must not make the mark look later than a play
     * elsewhere meanwhile.
     *
     * The last-played date is kept until the server has the mark: the sync sends the mark, which
     * cannot clear the server's date, and with it the sync sees the mark is not older than the play
     * it undoes. A state without one gets [markedAt], or it would always count as older. See
     * [afterUnplayedReport] for what follows the request.
     */
    fun markedUnplayed(markedAt: Long): PlayState =
        copy(played = false, lastPlayedMillis = lastPlayedMillis ?: markedAt)

    /**
     * After the request of an unplayed mark: when it [reached] the server, the last-played date is
     * cleared, as the server clears its own for an unplayed mark (CE #633). Otherwise nothing is
     * written (null): the date stays for the sync.
     */
    fun afterUnplayedReport(reached: Boolean): PlayState? =
        if (reached) copy(lastPlayedMillis = null) else null

    /** A favourite mark, or its removal. Nothing else changes. */
    fun withFavorite(favorite: Boolean): PlayState = copy(favorite = favorite)

    /**
     * A progress report at [positionTicks], made at [at]. CE (#633): the date is sent along if this
     * progress has to be synced later.
     */
    fun progressed(positionTicks: Long, at: Long): PlayState =
        copy(positionTicks = positionTicks, lastPlayedMillis = at)

    /**
     * A playback stop at [at] that writes [stop] (see [PlayStop.of]): its position, its played flag
     * when it has one, and the date (CE #633), which is sent along if the stop has to be synced
     * later.
     */
    fun stopped(stop: PlayStop, at: Long): PlayState =
        copy(
            positionTicks = stop.positionTicks,
            played = stop.played ?: played,
            lastPlayedMillis = at,
        )
}
