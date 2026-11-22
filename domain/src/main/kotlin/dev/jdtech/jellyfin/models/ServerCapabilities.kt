package dev.jdtech.jellyfin.models

/**
 * #769: what a server can do of the features that not every server type has. Where a flag is off,
 * the app does not offer the feature and does not ask the server for it. A Jellyfin server has them
 * all ([ALL]).
 */
data class ServerCapabilities(
    /** Quick Connect at login. Whether the admin turned it on is still asked of the server. */
    val quickConnect: Boolean,
    /** The users a server lists on its login screen. */
    val publicUsers: Boolean,
    /** The text a server shows under its login form. */
    val loginDisclaimer: Boolean,
    /** Intro, outro, recap, preview and commercial segments: skip buttons and their settings. */
    val mediaSegments: Boolean,
    /** Seek previews and their settings. */
    val trickplay: Boolean,
    /** A series' theme song on its page, and the setting for it. */
    val themeSongs: Boolean,
    /** "More like this" on detail pages. */
    val similarItems: Boolean,
    /** Local trailers and special features on detail pages. */
    val extras: Boolean,
    /** The collections an item is part of ("Included in") on detail pages. */
    val itemCollections: Boolean,
    /** The server's own resume thresholds; without them the defaults apply. */
    val serverResumeThresholds: Boolean,
    /** The user's "play next episode automatically" setting on the server. */
    val autoPlayNextSetting: Boolean,
    /** The socket that keeps the app's session listed on the server. */
    val sessionSocket: Boolean,
    /** Renaming this device on the server. */
    val deviceRename: Boolean,
    /** A download transcoded by the server down to a chosen bitrate. */
    val cappedDownloadTranscode: Boolean,
) {
    companion object {
        /** Every feature: a Jellyfin server. */
        val ALL =
            ServerCapabilities(
                quickConnect = true,
                publicUsers = true,
                loginDisclaimer = true,
                mediaSegments = true,
                trickplay = true,
                themeSongs = true,
                similarItems = true,
                extras = true,
                itemCollections = true,
                serverResumeThresholds = true,
                autoPlayNextSetting = true,
                sessionSocket = true,
                deviceRename = true,
                cappedDownloadTranscode = true,
            )
    }
}
