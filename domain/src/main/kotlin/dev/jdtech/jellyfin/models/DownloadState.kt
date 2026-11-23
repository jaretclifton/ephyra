package dev.jdtech.jellyfin.models

/**
 * Where a source stands as a download. Nothing of it is stored: it is read from the source's type
 * and path ([ItemSource.downloadState]), so a stored row keeps the shape it has.
 */
enum class DownloadState {
    /** A source of the server: no download. */
    NONE,

    /** A local source whose file still has the partial name: queued, running, paused or failed. */
    DOWNLOADING,

    /** A local source under its final name: it plays without the server. */
    FINISHED,
}

/**
 * The names of a download's files. A download is written as `<final path>.download` and renamed to
 * `<final path>` when it is finished, so the name of the file says which of the two it is. This is
 * the one place that knows the suffix; readers use [ItemSource.downloadState] or [isPartial].
 */
object DownloadFiles {
    const val PARTIAL_SUFFIX = ".download"

    /** The SQL `LIKE` pattern of a partial name, for the queries that filter rows by it. */
    const val PARTIAL_LIKE = "%$PARTIAL_SUFFIX"

    /** True when [path] is the name of a download that is not finished. */
    fun isPartial(path: String): Boolean = path.endsWith(PARTIAL_SUFFIX)

    /** The name a finished download has: [path] without the partial suffix, if it has one. */
    fun finalPath(path: String): String = path.removeSuffix(PARTIAL_SUFFIX)

    /** The name a download has while it is written: [finalPath] with the partial suffix. */
    fun partialPath(finalPath: String): String = finalPath + PARTIAL_SUFFIX
}

/** The download state of this source, from its type and path. */
val ItemSource.downloadState: DownloadState
    get() =
        when {
            type != ItemSourceType.LOCAL -> DownloadState.NONE
            DownloadFiles.isPartial(path) -> DownloadState.DOWNLOADING
            else -> DownloadState.FINISHED
        }
