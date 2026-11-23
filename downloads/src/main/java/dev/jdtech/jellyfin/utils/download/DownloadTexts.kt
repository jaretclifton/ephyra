package dev.jdtech.jellyfin.utils.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.models.UiText

/**
 * CE (#901): what the download code shows the user from resources that stay in `core`. Most are
 * upstream's strings, whose translations Weblate writes into `core`; the reasons a download could
 * not start are shared with the load errors and setup; the words for a waiting download are the
 * Downloads screen's; the icons are the app's. `downloads` cannot depend on `core` (`core` depends
 * on it), so `core` implements this (`CoreDownloadTexts`). The strings only the download code shows
 * live in this module.
 */
interface DownloadTexts {
    /** Why a download could not start because of [error]. Never the exception's own text. */
    fun startError(error: Throwable): UiText

    /** No storage to download to is mounted. */
    fun storageUnavailable(): UiText

    /** The download does not fit: [needed] and [available] are formatted sizes. */
    fun notEnoughStorage(needed: String, available: String): UiText

    /** A download failed for a reason the user cannot act on. */
    fun downloadingError(): UiText

    /** The title of the notification of a download that failed. */
    @get:StringRes val failedNotificationTitle: Int

    /** The icon of the notification of a download that failed. */
    @get:DrawableRes val failedNotificationIcon: Int

    /** The icon of the notification of the downloads in progress. */
    @get:DrawableRes val progressNotificationIcon: Int

    /** A download that waits for a free slot: the same words as on the Downloads screen. */
    @get:StringRes val waiting: Int

    /** A download that waits for the network: the same words as on the Downloads screen. */
    @get:StringRes val waitingForNetwork: Int
}
