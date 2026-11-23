package dev.jdtech.jellyfin.utils.download

import dev.jdtech.jellyfin.models.UiText

/**
 * CE (#901): the texts the download tests give the engine and the queue. Core's own
 * (`CoreDownloadTexts`) are out of reach here: `core` depends on this module. Framework resources
 * stand in, so a notification the queue builds in a test finds its title and icon; a test checks a
 * text by the id here.
 */
object TestDownloadTexts : DownloadTexts {
    val START_ERROR = android.R.string.httpErrorBadUrl
    val STORAGE_UNAVAILABLE = android.R.string.no
    val NOT_ENOUGH_STORAGE = android.R.string.yes
    val DOWNLOADING_ERROR = android.R.string.cancel

    override fun startError(error: Throwable): UiText = UiText.StringResource(START_ERROR)

    override fun storageUnavailable(): UiText = UiText.StringResource(STORAGE_UNAVAILABLE)

    override fun notEnoughStorage(needed: String, available: String): UiText =
        UiText.StringResource(NOT_ENOUGH_STORAGE, needed, available)

    override fun downloadingError(): UiText = UiText.StringResource(DOWNLOADING_ERROR)

    override val failedNotificationTitle: Int = android.R.string.unknownName

    override val failedNotificationIcon: Int = android.R.drawable.stat_notify_error

    override val progressNotificationIcon: Int = android.R.drawable.stat_sys_download

    override val waiting: Int = android.R.string.untitled

    override val waitingForNetwork: Int = android.R.string.dialog_alert_title
}
