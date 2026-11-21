package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.File
import java.util.UUID

data class FindroidSource(
    val id: String,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val size: Long,
    val mediaStreams: List<FindroidMediaStream>,
    val downloadId: Long? = null,
    /**
     * True when [path] points at a server-side transcode rather than the original file: an HLS
     * manifest on the playback path, or a progressive stream on the download path (Dolby Vision
     * conversion or a user-picked quality cap).
     */
    val transcoded: Boolean = false,
    /** CE (#510): for the media info sheet. A downloaded source has neither. */
    val container: String? = null,
    val bitrate: Int? = null,
    /** CE (#580): the audio stream the server plays, and puts in a transcode, for this source. */
    val defaultAudioStreamIndex: Int? = null,
    /**
     * CE (#728): the play session of a transcode on the playback path, which the playback reports
     * name once the player loads it. Null for anything else.
     */
    val playSessionId: String? = null,
)

suspend fun FindroidSourceDto.toFindroidSource(
    serverDatabaseDao: ServerDatabaseDao
): FindroidSource = toFindroidSource(serverDatabaseDao.getMediaStreamsBySourceId(id))

/** Maps with already-fetched [streams], so a list of sources costs no query per source. */
fun FindroidSourceDto.toFindroidSource(streams: List<FindroidMediaStreamDto>): FindroidSource {
    return FindroidSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams = streams.map { it.toFindroidMediaStream() },
        downloadId = downloadId,
    )
}

/**
 * The downloaded (local) sources of every item in [itemIds], keyed by item id, in two queries
 * however long the list is (none when it is empty). Items without a download are absent.
 */
suspend fun ServerDatabaseDao.getLocalSourcesForItems(
    itemIds: Collection<UUID>
): Map<UUID, List<FindroidSource>> =
    localSourcesForItems(itemIds) { ids -> getSourcesForItems(ids) }

/**
 * CE (#718): [getLocalSourcesForItems], only the downloads stored for [serverId]: another server's
 * download of the same item id is not this server's item downloaded.
 */
suspend fun ServerDatabaseDao.getLocalSourcesForItems(
    itemIds: Collection<UUID>,
    serverId: String,
): Map<UUID, List<FindroidSource>> =
    localSourcesForItems(itemIds) { ids -> getSourcesForItemsOfServer(ids, serverId) }

private suspend fun ServerDatabaseDao.localSourcesForItems(
    itemIds: Collection<UUID>,
    read: suspend (List<UUID>) -> List<FindroidSourceDto>,
): Map<UUID, List<FindroidSource>> {
    if (itemIds.isEmpty()) return emptyMap()
    val dtos = itemIds.distinct().chunked(SQL_CHUNK).flatMap { read(it) }
    if (dtos.isEmpty()) return emptyMap()
    val streams =
        dtos
            .map { it.id }
            .chunked(SQL_CHUNK)
            .flatMap { getMediaStreamsForSources(it) }
            .groupBy { it.sourceId }
    return dtos
        .groupBy { it.itemId }
        .mapValues { (_, sources) -> sources.map { it.toFindroidSource(streams[it.id].orEmpty()) } }
}

// Stays under SQLite's historical limit of 999 bound variables per statement.
private const val SQL_CHUNK = 500

enum class FindroidSourceType {
    REMOTE,
    LOCAL,
}
