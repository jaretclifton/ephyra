package dev.jdtech.jellyfin.repository

/**
 * #918: the Next Up limit set on the home screen, for the repository's Next Up request. `core`
 * implements it over the saved preference, read again on every [get].
 */
fun interface NextUpMaxDays {
    /** Days since an episode was last played for it to stay in Next Up; 0 is no limit. */
    fun get(): Int
}
