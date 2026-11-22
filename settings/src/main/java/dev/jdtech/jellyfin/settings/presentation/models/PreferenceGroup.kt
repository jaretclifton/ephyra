package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.StringRes

data class PreferenceGroup(
    @param:StringRes val nameStringResource: Int? = null,
    val preferences: List<Preference>,
    /** #769: the server feature the whole group is about; hidden while the server lacks it. */
    val serverFeature: ServerFeature? = null,
)
