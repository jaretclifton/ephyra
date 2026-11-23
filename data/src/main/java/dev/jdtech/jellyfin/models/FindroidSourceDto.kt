package dev.jdtech.jellyfin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.File

@Entity(tableName = "sources", indices = [Index("itemId")])
data class FindroidSourceDto(
    @PrimaryKey val id: String,
    val itemId: ItemId,
    val name: String,
    val type: ItemSourceType,
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

fun ItemSource.toFindroidSourceDto(itemId: ItemId, path: String): FindroidSourceDto {
    return FindroidSourceDto(
        id = id,
        itemId = itemId,
        name = name,
        type = ItemSourceType.LOCAL,
        path = path,
    )
}

suspend fun FindroidSourceDto.toItemSource(serverDatabaseDao: ServerDatabaseDao): ItemSource =
    toItemSource(serverDatabaseDao.getMediaStreamsBySourceId(id))

/** Maps with already-fetched [streams], so a list of sources costs no query per source. */
fun FindroidSourceDto.toItemSource(streams: List<FindroidMediaStreamDto>): ItemSource {
    return ItemSource(
        id = id,
        name = name,
        type = type,
        path = path,
        size = File(path).length(),
        mediaStreams = streams.map { it.toMediaStream() },
        downloadId = downloadId,
    )
}

/**
 * The downloaded (local) sources of every item in [itemIds], keyed by item id, in two queries
 * however long the list is (none when it is empty). Items without a download are absent.
 */
suspend fun ServerDatabaseDao.getLocalSourcesForItems(
    itemIds: Collection<ItemId>
): Map<ItemId, List<ItemSource>> = localSourcesForItems(itemIds) { ids -> getSourcesForItems(ids) }

/**
 * CE (#718): [getLocalSourcesForItems], only the downloads stored for [serverId]: another server's
 * download of the same item id is not this server's item downloaded.
 */
suspend fun ServerDatabaseDao.getLocalSourcesForItems(
    itemIds: Collection<ItemId>,
    serverId: String,
): Map<ItemId, List<ItemSource>> =
    localSourcesForItems(itemIds) { ids -> getSourcesForItemsOfServer(ids, serverId) }

private suspend fun ServerDatabaseDao.localSourcesForItems(
    itemIds: Collection<ItemId>,
    read: suspend (List<ItemId>) -> List<FindroidSourceDto>,
): Map<ItemId, List<ItemSource>> {
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
        .mapValues { (_, sources) -> sources.map { it.toItemSource(streams[it.id].orEmpty()) } }
}

// Stays under SQLite's historical limit of 999 bound variables per statement.
private const val SQL_CHUNK = 500
