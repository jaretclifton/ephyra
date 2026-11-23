package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.utils.download.DownloadTexts
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What a download that could not start tells the user. Never the exception's own text: it can hold
 * URLs, hosts or stack fragments. CE (#530): the Jellyfin SDK wraps a refused or unreachable
 * connection in its own exception, so the cause chain is searched too; that read "Unknown error".
 */
internal fun downloadErrorText(e: Throwable): UiText =
    when {
        e is UnknownHostException || e is ConnectException ->
            UiText.StringResource(CoreR.string.download_error_server_unreachable)
        e is SocketTimeoutException -> UiText.StringResource(CoreR.string.download_error_timeout)
        LoadErrorText.isConnectionError(e) ->
            UiText.StringResource(CoreR.string.download_error_server_unreachable)
        e is IOException -> UiText.StringResource(CoreR.string.download_error_network)
        else -> UiText.StringResource(CoreR.string.unknown_error)
    }

/**
 * CE (#901): the texts of core's resources that the download module shows. They stay here: most are
 * upstream's strings (Weblate), the reasons a download could not start share their strings with the
 * load errors and setup, and the waiting words are the Downloads screen's.
 */
object CoreDownloadTexts : DownloadTexts {
    override fun startError(error: Throwable): UiText = downloadErrorText(error)

    override fun storageUnavailable(): UiText =
        UiText.StringResource(CoreR.string.storage_unavailable)

    override fun notEnoughStorage(needed: String, available: String): UiText =
        UiText.StringResource(CoreR.string.not_enough_storage, needed, available)

    override fun downloadingError(): UiText = UiText.StringResource(CoreR.string.downloading_error)

    override val failedNotificationTitle: Int = CoreR.string.download_failed

    override val failedNotificationIcon: Int = CoreR.drawable.ic_x

    override val progressNotificationIcon: Int = CoreR.drawable.ic_download

    override val waiting: Int = CoreR.string.download_queued

    override val waitingForNetwork: Int = CoreR.string.download_waiting_for_network
}
