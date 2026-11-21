package dev.jdtech.jellyfin.film.presentation

/**
 * CE (#775): when a list that falls back to the downloads (Favorites, Media, search) loads again
 * because the server went away or came back.
 *
 * The screen hands every change of state to [onServerAway]; the view model calls [begin] when a
 * load starts. A change loads again only when the list on screen was loaded with the other state,
 * and a load that is running already for the current state is not started twice, which is what
 * keeps this, [ReloadPolicy][dev.jdtech.jellyfin.repository.ReloadPolicy] and the retry of the
 * error page from loading the same list twice.
 */
class ServerAwayReload {
    private var away: Boolean? = null
    private var loadedFor: Boolean? = null
    private var loaded = false

    /**
     * Marks the start of a load, with the state the screen last reported. Returns whether that was
     * the server being away: a load that then succeeds holds the downloads only.
     */
    fun begin(): Boolean {
        loadedFor = away
        loaded = true
        return away == true
    }

    /** Whether a load that is still running was started with the state of now. */
    fun runningIsCurrent(): Boolean = loaded && away != null && loadedFor == away

    /**
     * Records [away] and says whether the list needs a load for it. Not on the first report: the
     * screen's own first load runs with it. Not before anything loaded either.
     */
    fun onServerAway(away: Boolean): Boolean {
        val before = this.away
        this.away = away
        return before != null && loaded && loadedFor != away
    }
}
