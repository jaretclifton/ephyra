package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.VideoRangeType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter as SdkItemFilter
import org.jellyfin.sdk.model.api.MediaStreamType as SdkMediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod as SdkSubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType as SdkVideoRangeType

// Conversions from the SDK's enums to the app's, by constant name. SdkEnumsTest fails when the SDK
// has a constant the app's enum lacks.

/** Maps by name: [PersonKind] has a counterpart of every SDK constant (see SdkEnumsTest). */
internal fun org.jellyfin.sdk.model.api.PersonKind.toModel(): PersonKind = PersonKind.valueOf(name)

internal fun SdkMediaStreamType.toModel(): MediaStreamType = MediaStreamType.valueOf(name)

internal fun SdkVideoRangeType.toModel(): VideoRangeType = VideoRangeType.valueOf(name)

internal fun SdkSubtitleDeliveryMethod.toModel(): SubtitleDeliveryMethod =
    SubtitleDeliveryMethod.valueOf(name)

// The other way: what the app asks the server for.

fun ItemFilter.toSdk(): SdkItemFilter =
    when (this) {
        ItemFilter.IS_UNPLAYED -> SdkItemFilter.IS_UNPLAYED
    }

fun EpisodeField.toSdk(): ItemFields =
    when (this) {
        EpisodeField.OVERVIEW -> ItemFields.OVERVIEW
        EpisodeField.CAN_DOWNLOAD -> ItemFields.CAN_DOWNLOAD
        EpisodeField.CHAPTERS -> ItemFields.CHAPTERS
        EpisodeField.TRICKPLAY -> ItemFields.TRICKPLAY
    }

/** No filters is sent as no parameter, as before. */
fun Set<ItemFilter>.toSdkFilters(): List<SdkItemFilter>? =
    takeIf { it.isNotEmpty() }?.map { it.toSdk() }
