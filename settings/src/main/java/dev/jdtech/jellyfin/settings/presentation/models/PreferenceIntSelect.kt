package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

/**
 * CE (#837): a choice from a fixed list for an [Int] preference, such as a streaming quality in
 * bits per second. [options] are the stored values with their labels, in the order they are shown.
 */
data class PreferenceIntSelect(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val backendPreference: PreferenceBackend<Int>,
    val options: List<Pair<Int, UiText>>,
    val value: Int = 0,
) : Preference
