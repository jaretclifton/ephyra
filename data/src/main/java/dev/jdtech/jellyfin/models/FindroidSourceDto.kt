package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.File
import java.util.UUID

@Entity(tableName = "sources", indices = [Index("itemId")])
data class FindroidSourceDto(
    @PrimaryKey val id: String,
    val itemId: UUID,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val downloadId: Long? = null,
    /**
     * The download-quality bitrate cap (bits/sec) this source was requested with, or null for
     * original quality. Persisted so a resume after process death re-resolves the *same* URL kind —
     * resuming a partial transcode with an original-file URL (or vice versa) would append
     * mismatched bytes.
     */
    val downloadMaxBitrate: Int? = null,
    /** Epoch ms at which the download finished; null while in flight and for older rows. */
    val downloadedAt: Long? = null,
    /**
     * True when Smart Downloads queued this download itself, which is the only kind it may delete
     * later. Rows from before the column existed default to false (user-owned).
     */
    @ColumnInfo(defaultValue = "0") val smartQueued: Boolean = false,
)

fun FindroidSource.toFindroidSourceDto(itemId: UUID, path: String): FindroidSourceDto {
    return FindroidSourceDto(
        id = id,
        itemId = itemId,
        name = name,
        type = FindroidSourceType.LOCAL,
        path = path,
    )
}

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
