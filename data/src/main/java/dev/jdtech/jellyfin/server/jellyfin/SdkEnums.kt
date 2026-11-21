package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.MediaStreamType
import dev.jdtech.jellyfin.models.SubtitleDeliveryMethod
import dev.jdtech.jellyfin.models.VideoRangeType
import org.jellyfin.sdk.model.api.MediaStreamType as SdkMediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod as SdkSubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType as SdkVideoRangeType

// Conversions from the SDK's enums to the app's, by constant name. SdkEnumsTest fails when the SDK
// has a constant the app's enum lacks.

internal fun SdkMediaStreamType.toModel(): MediaStreamType = MediaStreamType.valueOf(name)

internal fun SdkVideoRangeType.toModel(): VideoRangeType = VideoRangeType.valueOf(name)

internal fun SdkSubtitleDeliveryMethod.toModel(): SubtitleDeliveryMethod =
    SubtitleDeliveryMethod.valueOf(name)
