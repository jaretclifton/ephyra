package dev.jdtech.jellyfin.utils.download

import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.repository.CurrentServerId
import dev.jdtech.jellyfin.repository.DownloadSettings

/** The download settings of the tests, with the defaults of the app's preferences. */
class TestDownloadSettings(
    override var maxConcurrentDownloads: Int = 2,
    override var downloadStorageIndex: String? = null,
    override var downloadTranscodeDolbyVision: Boolean = true,
    override var downloadOverMobileData: Boolean = false,
    override var downloadWhenRoaming: Boolean = false,
    override var smartDownloads: Boolean = false,
    override var smartDownloadsSince: Long = 0L,
    override var smartDownloadsKeepReady: Int = 3,
) : DownloadSettings

/** The current server of the tests: none until a test sets one. */
class TestCurrentServerId(var id: ServerId? = null) : CurrentServerId {
    override fun get(): ServerId? = id
}
