package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.Item

sealed interface DownloaderAction {
    /** [maxBitrate] = download quality cap in bits/sec, null for original quality. */
    data class Download(val item: Item, val maxBitrate: Int? = null) : DownloaderAction

    data class DeleteDownload(val item: Item) : DownloaderAction

    data class CancelDownload(val item: Item) : DownloaderAction
}
