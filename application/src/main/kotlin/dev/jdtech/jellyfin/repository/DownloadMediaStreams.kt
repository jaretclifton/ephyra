package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.AccountKey
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.MediaStream
import dev.jdtech.jellyfin.models.MediaStreamId
import dev.jdtech.jellyfin.models.ServerId

/**
 * #967: the external subtitles a download keeps with a source.
 *
 * Downloads are stored per server (#718): a query that is of one server takes its [ServerId], one
 * of a user on a server an [AccountKey]. A row is otherwise keyed as Room keys it. One of the roles
 * the download store was split into (#967); `RoomDownloadStore` in `data` implements them all.
 */
interface DownloadMediaStreams {
    suspend fun mediaStreamsOfSource(sourceId: MediaSourceId): List<StoredMediaStream>

    suspend fun mediaStreamByDownloadId(downloadId: Long): StoredMediaStream?

    /** Stores [stream] of source [sourceId] under [id], downloaded to [path]. */
    suspend fun insertMediaStream(
        id: MediaStreamId,
        sourceId: MediaSourceId,
        stream: MediaStream,
        path: String,
    )

    suspend fun setMediaStreamDownloadId(id: MediaStreamId, downloadId: Long)

    suspend fun setMediaStreamPath(id: MediaStreamId, path: String)

    suspend fun deleteMediaStream(id: MediaStreamId)

    suspend fun deleteMediaStreamsOfSource(sourceId: MediaSourceId)
}
