package dev.jdtech.jellyfin.repository

/**
 * What the download engine reads of the user's settings (#918). Every member is read live on each
 * call, never cached by the caller, so a change in the settings counts at the next download. The
 * app provides the implementation over the preferences.
 */
interface DownloadSettings {
    /** How many downloads run at the same time. */
    val maxConcurrentDownloads: Int

    /** The storage a download goes to, as the index the user picked (a number as text), or none. */
    val downloadStorageIndex: String?

    /** Whether a Dolby Vision item is transcoded on the server for a download. */
    val downloadTranscodeDolbyVision: Boolean

    /** Whether a download may use mobile data. */
    val downloadOverMobileData: Boolean

    /** Whether a download may continue while roaming. */
    val downloadWhenRoaming: Boolean

    /** Whether Smart Downloads is on. */
    val smartDownloads: Boolean

    /** When Smart Downloads was first on, in epoch milliseconds, or 0 when not recorded yet. */
    var smartDownloadsSince: Long

    /** How many episodes Smart Downloads keeps ready. */
    val smartDownloadsKeepReady: Int
}
