package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.ItemFilter
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter as SdkItemFilter

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
