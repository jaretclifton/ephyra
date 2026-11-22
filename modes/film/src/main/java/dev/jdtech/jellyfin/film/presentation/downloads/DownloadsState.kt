package dev.jdtech.jellyfin.film.presentation.downloads

import dev.jdtech.jellyfin.core.presentation.downloader.DownloadProgress
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadStatus
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import java.util.UUID

data class ActiveDownload(
    val item: FindroidItem,
    val progress: DownloadProgress,
    val downloadId: Long?,
    /** Bytes downloaded so far, or -1 if unknown. */
    val bytesDownloaded: Long = -1L,
    /** Total bytes for the download, or -1 if unknown. */
    val totalBytes: Long = -1L,
    /** True when [totalBytes] is an estimate (a transcode of unknown length). */
    val totalBytesEstimated: Boolean = false,
    /** True when the server is transcoding this download (e.g. Dolby Vision). */
    val isTranscode: Boolean = false,
    /** Transfer rate in bytes/sec, or 0 if not yet computed. */
    val bytesPerSecond: Long = 0L,
    /** User-facing failure reason, only set when progress.status == FAILED. */
    val errorText: UiText? = null,
)

/**
 * CE (#546): what the Downloads header counts. [active] is everything still on its way (queued,
 * downloading, waiting for the network, or just finished), [failed] the downloads that wait for
 * Retry.
 */
data class QueueCounts(val active: Int, val failed: Int)

fun List<ActiveDownload>.queueCounts(): QueueCounts {
    val failed = count { it.progress.status == DownloadStatus.FAILED }
    return QueueCounts(active = size - failed, failed = failed)
}

enum class DownloadSortOrder {
    NAME,
    DATE,
    SIZE,
}

data class DownloadsState(
    val queueItems: List<ActiveDownload> = emptyList(),
    val sortOrder: DownloadSortOrder = DownloadSortOrder.DATE,
    val sections: List<CollectionSection> = emptyList(),
    val itemSizes: Map<UUID, Long> = emptyMap(),
    /** Number of downloaded episodes per show id. */
    val episodeCounts: Map<UUID, Int> = emptyMap(),
    /** Bytes taken by downloaded movies and shows, for the storage bar segments. */
    val moviesBytes: Long = 0L,
    val showsBytes: Long = 0L,
    val storageUsedBytes: Long = 0L,
    val storageFreeBytes: Long = 0L,
    /** Size of the volume downloads are stored on, or 0 if unknown. */
    val storageTotalBytes: Long = 0L,
    val storageIsExternal: Boolean = false,
    val isLoading: Boolean = false,
    val error: Exception? = null,
    /**
     * CE (#866): the user whose played state and progress [sections] carry. The downloads are the
     * same for every user of the server, their user data is not. Null before the first load, or
     * when unknown.
     */
    val userId: UUID? = null,
) {
    /**
     * CE (#866): what the screen may show while [userId] is signed in: the queue and the storage,
     * which belong to no user, but none of the downloaded items with another user's played state
     * and progress, only a loading list until the new user's arrive. Null [userId] (no session at
     * hand, as in previews) shows everything.
     */
    fun shownTo(userId: UUID?): DownloadsState =
        if (userId == null || this.userId == null || this.userId == userId) {
            this
        } else {
            copy(sections = emptyList(), isLoading = true, error = null, userId = userId)
        }
}
