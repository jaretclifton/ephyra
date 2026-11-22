package dev.jdtech.jellyfin.settings.presentation.models

import dev.jdtech.jellyfin.models.ServerCapabilities

/**
 * #769: a feature that not every server has. A [Preference] or [PreferenceGroup] about one is
 * hidden while the server in use lacks it, as [Preference.supportedDeviceTypes] hides one on the
 * other app variant.
 */
enum class ServerFeature {
    MEDIA_SEGMENTS,
    TRICKPLAY,
    THEME_SONGS;

    fun isIn(capabilities: ServerCapabilities): Boolean =
        when (this) {
            MEDIA_SEGMENTS -> capabilities.mediaSegments
            TRICKPLAY -> capabilities.trickplay
            THEME_SONGS -> capabilities.themeSongs
        }
}

/** Whether the server in use has what this is about. */
fun ServerFeature?.isShownFor(capabilities: ServerCapabilities): Boolean =
    this?.isIn(capabilities) ?: true
