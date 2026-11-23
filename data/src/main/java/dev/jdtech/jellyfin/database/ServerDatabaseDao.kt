package dev.jdtech.jellyfin.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import dev.jdtech.jellyfin.models.EpisodeEntity
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceEntity
import dev.jdtech.jellyfin.models.ItemTrickplayInfoEntity
import dev.jdtech.jellyfin.models.MediaStreamEntity
import dev.jdtech.jellyfin.models.MovieEntity
import dev.jdtech.jellyfin.models.PendingDownloadDto
import dev.jdtech.jellyfin.models.SeasonEntity
import dev.jdtech.jellyfin.models.SeasonEpisodeCounts
import dev.jdtech.jellyfin.models.SegmentEntity
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.ServerWithAddresses
import dev.jdtech.jellyfin.models.ServerWithAddressesAndUsers
import dev.jdtech.jellyfin.models.ShowEntity
import dev.jdtech.jellyfin.models.StoredItemDate
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/*
 * CE (#718): which stored rows belong to the server `:serverId`. One row per item id, whichever
 * server stored it: Jellyfin derives ids from the media's type and path, so a second server over
 * the same paths (a reinstalled server, added again) has the same ids. Movies and episodes carry
 * the server. A show or a season has none of its own that holds (the first server to store it wins
 * the row): it belongs to the servers whose episodes it holds, or to the show's server while it
 * holds none.
 */
private const val SHOW_OF_SERVER =
    "(EXISTS (SELECT 1 FROM episodes WHERE episodes.seriesId = shows.id " +
        "AND episodes.serverId = :serverId) OR (shows.serverId = :serverId " +
        "AND NOT EXISTS (SELECT 1 FROM episodes WHERE episodes.seriesId = shows.id)))"

private const val SEASON_OF_SERVER =
    "(EXISTS (SELECT 1 FROM episodes WHERE episodes.seasonId = seasons.id " +
        "AND episodes.serverId = :serverId) OR (NOT EXISTS (SELECT 1 FROM episodes " +
        "WHERE episodes.seasonId = seasons.id) AND EXISTS (SELECT 1 FROM shows " +
        "WHERE shows.id = seasons.seriesId AND shows.serverId = :serverId)))"

/** A source belongs to the server of its movie or episode. */
private const val SOURCE_OF_SERVER =
    "(sources.itemId IN (SELECT id FROM movies WHERE serverId = :serverId) " +
        "OR sources.itemId IN (SELECT id FROM episodes WHERE serverId = :serverId))"

/*
 * CE (#775): rows with a finished download, what Item.isDownloaded() means: a LOCAL source
 * whose file is complete (a `.download` file is still being written, #454). The downloader stores
 * the rows before the file, so an unfinished or abandoned download has rows too. A show counts with
 * an episode of :serverId that is downloaded.
 */
private const val MOVIE_DOWNLOADED =
    "EXISTS (SELECT 1 FROM sources WHERE sources.itemId = movies.id " +
        "AND sources.type = 'LOCAL' AND sources.path NOT LIKE '%.download')"

private const val EPISODE_DOWNLOADED =
    "EXISTS (SELECT 1 FROM sources WHERE sources.itemId = episodes.id " +
        "AND sources.type = 'LOCAL' AND sources.path NOT LIKE '%.download')"

private const val SHOW_DOWNLOADED =
    "EXISTS (SELECT 1 FROM episodes WHERE episodes.seriesId = shows.id " +
        "AND episodes.serverId = :serverId AND $EPISODE_DOWNLOADED)"

@Dao
interface ServerDatabaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertServer(server: Server)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServerAddress(address: ServerAddress)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertUser(user: User)

    @Update suspend fun updateServer(server: Server)

    @Query("SELECT * FROM servers WHERE id = :id") suspend fun getServer(id: String): Server?

    @Query("SELECT * FROM users WHERE id = :id") suspend fun getUser(id: UserId): User?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddresses(id: String): ServerWithAddresses

    @Query("SELECT * FROM serverAddresses WHERE id = :id")
    suspend fun getAddress(id: UUID): ServerAddress

    @Query("SELECT * FROM users WHERE serverId = :serverId")
    suspend fun getUsers(serverId: String): List<User>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressesAndUsers(id: String): ServerWithAddressesAndUsers?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getServerWithAddressAndUser(id: String): ServerWithAddressAndUser?

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :id")
    fun observeServerWithAddressAndUser(id: String): Flow<ServerWithAddressAndUser?>

    @Transaction
    @Query("SELECT * FROM servers")
    suspend fun getServersWithAddresses(): List<ServerWithAddresses>

    @Query("SELECT * FROM servers") suspend fun getServers(): List<Server>

    @Query("SELECT COUNT(*) FROM servers") suspend fun getServersCount(): Int

    @Query("SELECT COUNT(*) FROM servers") fun observeServersCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM users WHERE serverId = :serverId")
    fun observeUsersCount(serverId: String): Flow<Int>

    @Query("SELECT * FROM servers WHERE id = :id") fun observeServer(id: String): Flow<Server?>

    @Query("SELECT currentUserId FROM servers WHERE id = :id")
    fun observeServerCurrentUserId(id: String): Flow<UserId?>

    @Query("DELETE FROM servers WHERE id = :id") suspend fun deleteServer(id: String)

    @Query("DELETE FROM users WHERE id = :id") suspend fun deleteUser(id: UserId)

    @Query("UPDATE users SET primaryImageTag = :tag WHERE id = :id")
    suspend fun updateUserPrimaryImageTag(id: UserId, tag: String?)

    @Query("DELETE FROM serverAddresses WHERE id = :id") suspend fun deleteServerAddress(id: UUID)

    @Query("UPDATE servers SET currentUserId = :userId WHERE id = :serverId")
    suspend fun updateServerCurrentUser(serverId: String, userId: UserId)

    // CE (#299): profile PIN.
    @Query("UPDATE servers SET currentUserId = NULL WHERE id = :serverId")
    suspend fun clearServerCurrentUser(serverId: String)

    // CE (#330): a PIN, or no lock (null hash), resets the lock type to `pin`.
    @Query("UPDATE users SET pinHash = :hash, pinSalt = :salt, lockType = 'pin' WHERE id = :id")
    suspend fun updateUserPin(id: UserId, hash: String?, salt: String?)

    @Query("UPDATE users SET pinHash = :hash, pinSalt = :salt, lockType = 'pattern' WHERE id = :id")
    suspend fun updateUserPattern(id: UserId, hash: String, salt: String)

    @Query("UPDATE users SET pinOnStart = :pinOnStart WHERE id = :id")
    suspend fun updateUserPinOnStart(id: UserId, pinOnStart: Boolean)

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    fun observeServerCurrentUser(serverId: String): Flow<User?>

    @Query(
        "SELECT * FROM users WHERE id = (SELECT currentUserId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentUser(serverId: String): User?

    @Query(
        "SELECT * FROM serverAddresses WHERE id = (SELECT currentServerAddressId FROM servers WHERE id = :serverId)"
    )
    suspend fun getServerCurrentAddress(serverId: String): ServerAddress?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMovie(movie: MovieEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: ItemSourceEntity)

    @Query("SELECT * FROM movies WHERE id = :id") suspend fun getMovie(id: ItemId): MovieEntity

    @Query("SELECT * FROM sources WHERE itemId = :itemId")
    suspend fun getSources(itemId: ItemId): List<ItemSourceEntity>

    /** One query for a whole list. Callers chunk [itemIds] (see [getLocalSourcesForItems]). */
    @Query("SELECT * FROM sources WHERE itemId IN (:itemIds)")
    suspend fun getSourcesForItems(itemIds: List<ItemId>): List<ItemSourceEntity>

    /** CE (#718): [getSourcesForItems], only the downloads of [serverId]. */
    @Query("SELECT * FROM sources WHERE itemId IN (:itemIds) AND $SOURCE_OF_SERVER")
    suspend fun getSourcesForItemsOfServer(
        itemIds: List<ItemId>,
        serverId: String,
    ): List<ItemSourceEntity>

    /** CE (#718): [getSources], only when the item is stored for [serverId]. */
    @Query("SELECT * FROM sources WHERE itemId = :itemId AND $SOURCE_OF_SERVER")
    suspend fun getSourcesOfServer(itemId: ItemId, serverId: String): List<ItemSourceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sources)") suspend fun hasSources(): Boolean

    /** Emits again whenever the sources table changes. */
    @Query("SELECT EXISTS(SELECT 1 FROM sources)") fun observeHasSources(): Flow<Boolean>

    @Query("SELECT * FROM sources WHERE downloadId = :downloadId")
    suspend fun getSourceByDownloadId(downloadId: Long): ItemSourceEntity?

    @Query("SELECT * FROM sources WHERE type = 'LOCAL' AND path LIKE '%.download'")
    suspend fun getActiveDownloadSources(): List<ItemSourceEntity>

    @Query("SELECT * FROM sources WHERE type = 'LOCAL' AND path NOT LIKE '%.download'")
    suspend fun getCompletedDownloadSources(): List<ItemSourceEntity>

    /** The item's finished downloads: a `.download` file is still being written (#454). */
    @Query(
        "SELECT * FROM sources WHERE itemId = :itemId AND path NOT LIKE '%.download' " +
            "AND $SOURCE_OF_SERVER"
    )
    suspend fun getCompletedSources(itemId: ItemId, serverId: String): List<ItemSourceEntity>

    @Query(
        "SELECT DISTINCT episodes.seriesId FROM episodes " +
            "JOIN sources ON sources.itemId = episodes.id " +
            "WHERE sources.type = 'LOCAL' AND sources.path NOT LIKE '%.download' " +
            "AND episodes.serverId = :serverId"
    )
    suspend fun getSeriesIdsWithCompletedDownloads(serverId: String): List<ItemId>

    @Query("UPDATE sources SET downloadId = :downloadId WHERE id = :id")
    suspend fun setSourceDownloadId(id: String, downloadId: Long)

    @Query("UPDATE sources SET path = :path WHERE id = :id")
    suspend fun setSourcePath(id: String, path: String)

    @Query("UPDATE sources SET path = :path, downloadedAt = :downloadedAt WHERE id = :id")
    suspend fun setSourceDownloaded(id: String, path: String, downloadedAt: Long)

    @Query("UPDATE sources SET smartQueued = 0 WHERE itemId = :itemId")
    suspend fun clearSourceSmartQueued(itemId: ItemId)

    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDownload(pendingDownload: PendingDownloadDto)

    @Query("UPDATE pending_downloads SET smartQueued = 0 WHERE itemId = :itemId")
    suspend fun clearPendingDownloadSmartQueued(itemId: ItemId)

    @Query("DELETE FROM pending_downloads WHERE itemId = :itemId")
    suspend fun deletePendingDownload(itemId: ItemId)

    @Query("SELECT * FROM pending_downloads ORDER BY addedAt ASC")
    suspend fun getPendingDownloads(): List<PendingDownloadDto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesTrackPreference(preference: SeriesTrackPreferenceDto)

    @Query("SELECT * FROM seriesTrackPreferences WHERE userId = :userId AND seriesId = :seriesId")
    suspend fun getSeriesTrackPreferences(
        userId: UserId,
        seriesId: ItemId,
    ): List<SeriesTrackPreferenceDto>

    @Query("DELETE FROM movies WHERE id = :id") suspend fun deleteMovie(id: ItemId)

    @Query(
        "UPDATE userdata SET playbackPositionTicks = :playbackPositionTicks WHERE itemId = :itemId AND userid = :userId"
    )
    suspend fun setPlaybackPositionTicks(
        itemId: ItemId,
        userId: UserId,
        playbackPositionTicks: Long,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaStream(mediaStream: MediaStreamEntity)

    @Query("SELECT * FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun getMediaStreamsBySourceId(sourceId: String): List<MediaStreamEntity>

    @Query("SELECT * FROM mediastreams WHERE sourceId IN (:sourceIds)")
    suspend fun getMediaStreamsForSources(sourceIds: List<String>): List<MediaStreamEntity>

    @Query("SELECT * FROM mediastreams WHERE downloadId = :downloadId")
    suspend fun getMediaStreamByDownloadId(downloadId: Long): MediaStreamEntity?

    @Query("UPDATE mediastreams SET downloadId = :downloadId WHERE id = :id")
    suspend fun setMediaStreamDownloadId(id: UUID, downloadId: Long)

    @Query("UPDATE mediastreams SET path = :path WHERE id = :id")
    suspend fun setMediaStreamPath(id: UUID, path: String)

    @Query("DELETE FROM mediastreams WHERE id = :id") suspend fun deleteMediaStream(id: UUID)

    @Query("DELETE FROM mediastreams WHERE sourceId = :sourceId")
    suspend fun deleteMediaStreamsBySourceId(sourceId: String)

    /** CE (#637): the server's runtime of a downloaded movie or episode; null when not stored. */
    @Query(
        "SELECT runtimeTicks FROM movies WHERE id = :itemId " +
            "UNION ALL SELECT runtimeTicks FROM episodes WHERE id = :itemId LIMIT 1"
    )
    suspend fun getRuntimeTicks(itemId: ItemId): Long?

    @Query("UPDATE userdata SET played = :played WHERE userId = :userId AND itemId = :itemId")
    suspend fun setPlayed(userId: UserId, itemId: ItemId, played: Boolean)

    @Query("UPDATE userdata SET favorite = :favorite WHERE userId = :userId AND itemId = :itemId")
    suspend fun setFavorite(userId: UserId, itemId: ItemId, favorite: Boolean)

    /** CE (#633): when the item was last played, in epoch milliseconds; null when unknown. */
    @Query(
        "UPDATE userdata SET lastPlayedDate = :lastPlayedDate WHERE userId = :userId AND itemId = :itemId"
    )
    suspend fun setLastPlayedDate(userId: UserId, itemId: ItemId, lastPlayedDate: Long?)

    /**
     * CE (#633): sets when the item was last played only when that is unknown, as the server does
     * when an item is marked played without a date.
     */
    @Query(
        "UPDATE userdata SET lastPlayedDate = COALESCE(lastPlayedDate, :now) " +
            "WHERE userId = :userId AND itemId = :itemId"
    )
    suspend fun setLastPlayedDateIfUnknown(userId: UserId, itemId: ItemId, now: Long)

    @Query("SELECT * FROM movies ORDER BY name ASC") suspend fun getMovies(): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getMoviesByServerId(serverId: String): List<MovieEntity>

    @Query("SELECT COUNT(*) FROM movies WHERE serverId = :serverId")
    suspend fun countMoviesByServerId(serverId: String): Int

    /** Whichever server stored it; what serves the current server is [getMovieOfServer]. */
    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieOrNull(id: ItemId): MovieEntity?

    @Query("SELECT * FROM movies WHERE id = :id AND serverId = :serverId")
    suspend fun getMovieOfServer(id: ItemId, serverId: String): MovieEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertShow(show: ShowEntity)

    @Query("SELECT * FROM shows WHERE id = :id") suspend fun getShow(id: ItemId): ShowEntity

    @Query("SELECT * FROM shows WHERE id = :id AND $SHOW_OF_SERVER")
    suspend fun getShowOfServer(id: ItemId, serverId: String): ShowEntity?

    @Query("SELECT * FROM shows ORDER BY name ASC") suspend fun getShows(): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE $SHOW_OF_SERVER ORDER BY name ASC")
    suspend fun getShowsByServerId(serverId: String): List<ShowEntity>

    @Query("SELECT COUNT(*) FROM shows WHERE $SHOW_OF_SERVER")
    suspend fun countShowsByServerId(serverId: String): Int

    @Query("DELETE FROM shows WHERE id = :id") suspend fun deleteShow(id: ItemId)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeason(show: SeasonEntity)

    @Query("SELECT * FROM seasons WHERE id = :id") suspend fun getSeason(id: ItemId): SeasonEntity

    @Query("SELECT * FROM seasons WHERE id = :id AND $SEASON_OF_SERVER")
    suspend fun getSeasonOfServer(id: ItemId, serverId: String): SeasonEntity?

    /** Every server's: what serves the current server is [getSeasonsByShowIdOfServer]. */
    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY indexNumber ASC")
    suspend fun getSeasonsByShowId(seriesId: ItemId): List<SeasonEntity>

    @Query(
        "SELECT * FROM seasons WHERE seriesId = :seriesId AND $SEASON_OF_SERVER " +
            "ORDER BY indexNumber ASC"
    )
    suspend fun getSeasonsByShowIdOfServer(seriesId: ItemId, serverId: String): List<SeasonEntity>

    @Query("DELETE FROM seasons WHERE id = :id") suspend fun deleteSeason(id: ItemId)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisode(episode: EpisodeEntity)

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: ItemId): EpisodeEntity

    /** Whichever server stored it; what serves the current server is [getEpisodeOfServer]. */
    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeOrNull(id: ItemId): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id = :id AND serverId = :serverId")
    suspend fun getEpisodeOfServer(id: ItemId, serverId: String): EpisodeEntity?

    @Query(
        "SELECT * FROM episodes WHERE seriesId = :seriesId AND serverId = :serverId " +
            "ORDER BY parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getEpisodesByShowIdOfServer(seriesId: ItemId, serverId: String): List<EpisodeEntity>

    /** Every server's: what serves the current server is [getEpisodesBySeasonIdOfServer]. */
    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY indexNumber ASC")
    suspend fun getEpisodesBySeasonId(seasonId: ItemId): List<EpisodeEntity>

    @Query(
        "SELECT * FROM episodes WHERE seasonId = :seasonId AND serverId = :serverId " +
            "ORDER BY indexNumber ASC"
    )
    suspend fun getEpisodesBySeasonIdOfServer(
        seasonId: ItemId,
        serverId: String,
    ): List<EpisodeEntity>

    /** CE (#379): every stored item of every server and user, which may own downloaded artwork. */
    @Query(
        "SELECT id FROM movies UNION SELECT id FROM shows UNION SELECT id FROM seasons " +
            "UNION SELECT id FROM episodes"
    )
    suspend fun getAllItemIds(): List<ItemId>

    /**
     * CE (#252): per season of [seriesId], how many episodes are stored and how many of them are
     * downloaded, in one query. Downloaded means what `Item.isDownloaded()` means: a LOCAL source
     * that is not a partial `.download` file. CE (#718): only the episodes of [serverId].
     */
    @Query(
        "SELECT episodes.seasonId AS seasonId, COUNT(*) AS total, " +
            "SUM(CASE WHEN EXISTS (SELECT 1 FROM sources WHERE sources.itemId = episodes.id " +
            "AND sources.type = 'LOCAL' AND sources.path NOT LIKE '%.download') " +
            "THEN 1 ELSE 0 END) AS downloaded " +
            "FROM episodes WHERE episodes.seriesId = :seriesId " +
            "AND episodes.serverId = :serverId GROUP BY episodes.seasonId"
    )
    suspend fun getSeasonEpisodeCounts(
        seriesId: ItemId,
        serverId: String,
    ): List<SeasonEpisodeCounts>

    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: ItemId)

    @Query("DELETE FROM episodes WHERE seasonId = :seasonId")
    suspend fun deleteEpisodesBySeasonId(seasonId: ItemId)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: SegmentEntity)

    @Query("DELETE FROM segments WHERE itemId = :itemId") suspend fun deleteSegments(itemId: ItemId)

    /** CE (#693): the item's segments become [segments], without rows from an earlier download. */
    @Transaction
    suspend fun replaceSegments(itemId: ItemId, segments: List<SegmentEntity>) {
        deleteSegments(itemId)
        segments.forEach { insertSegment(it) }
    }

    @Query("SELECT * FROM segments WHERE itemId = :itemId ORDER BY startTicks")
    suspend fun getSegments(itemId: ItemId): List<SegmentEntity>

    @Query("SELECT * FROM seasons") suspend fun getSeasons(): List<SeasonEntity>

    @Query("SELECT * FROM episodes") suspend fun getEpisodes(): List<EpisodeEntity>

    @Query("SELECT * FROM userdata WHERE itemId = :itemId AND userId = :userId")
    suspend fun getUserData(itemId: ItemId, userId: UserId): UserDataEntity?

    @Transaction
    suspend fun getUserDataOrCreateNew(itemId: ItemId, userId: UserId): UserDataEntity {
        var userData = getUserData(itemId, userId)

        // Create user data when there is none
        if (userData == null) {
            userData =
                UserDataEntity(
                    userId = userId,
                    itemId = itemId,
                    played = false,
                    favorite = false,
                    playbackPositionTicks = 0L,
                )
            insertUserData(userData)
        }

        return userData
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserData(userData: UserDataEntity)

    @Query("DELETE FROM userdata WHERE itemId = :itemId") suspend fun deleteUserData(itemId: ItemId)

    /**
     * Removes the user data of an item whose download is deleted, except rows that still have to
     * reach the server: those stay until the sync has sent them.
     */
    @Query("DELETE FROM userdata WHERE itemId = :itemId AND toBeSynced = 0")
    suspend fun deleteSyncedUserData(itemId: ItemId)

    /** Removes a synced row whose download is gone. Does nothing while the item is stored. */
    @Query(
        "DELETE FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 0 " +
            "AND itemId NOT IN (SELECT id FROM movies) AND itemId NOT IN (SELECT id FROM episodes)"
    )
    suspend fun deleteUserDataIfOrphaned(userId: UserId, itemId: ItemId)

    @Query("SELECT * FROM userdata WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1")
    suspend fun getUserDataToBeSynced(userId: UserId, itemId: ItemId): UserDataEntity?

    /**
     * The user data rows of [userId] that still have to reach the server: those of the downloaded
     * movies and episodes of [serverId], which are the items the sync covers, and those of movies
     * and episodes whose download was deleted before the sync ran. CE (#718): or whose stored copy
     * another server's download of the same id took over.
     */
    @Query(
        "SELECT * FROM userdata WHERE userId = :userId AND toBeSynced = 1 AND (" +
            "itemId IN (SELECT id FROM movies WHERE serverId = :serverId) OR " +
            "itemId IN (SELECT id FROM episodes WHERE serverId = :serverId) OR (" +
            "itemId NOT IN (SELECT id FROM movies WHERE serverId = :serverId) " +
            "AND itemId NOT IN (SELECT id FROM episodes WHERE serverId = :serverId) " +
            "AND itemId NOT IN (SELECT id FROM seasons) AND itemId NOT IN (SELECT id FROM shows)))"
    )
    suspend fun getAllUserDataToBeSynced(serverId: String, userId: UserId): List<UserDataEntity>

    @Query(
        "UPDATE userdata SET toBeSynced = :toBeSynced WHERE itemId = :itemId AND userId = :userId"
    )
    suspend fun setUserDataToBeSynced(userId: UserId, itemId: ItemId, toBeSynced: Boolean)

    /** Whether any movie or episode row, stored or not, still has to reach the server. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM userdata WHERE toBeSynced = 1 " +
            "AND itemId NOT IN (SELECT id FROM seasons) AND itemId NOT IN (SELECT id FROM shows))"
    )
    suspend fun hasUserDataToBeSynced(): Boolean

    /**
     * Marks a row as still to be sent to the server. Returns 1 when this call marked it, 0 when it
     * was marked already or there is no row (the item is not stored).
     */
    @Query(
        "UPDATE userdata SET toBeSynced = 1 " +
            "WHERE itemId = :itemId AND userId = :userId AND toBeSynced = 0"
    )
    suspend fun markUserDataToBeSynced(userId: UserId, itemId: ItemId): Int

    /**
     * CE (#635): runs [write] on the row of [itemId] and marks it to be sent, in one transaction.
     * The refresh from the server ([refreshUserDataFromServer]) only touches rows that are not
     * marked, so it cannot land between the write and the mark and replace what was just written.
     * Returns what [markUserDataToBeSynced] returns.
     */
    @Transaction
    suspend fun writeAndMarkToBeSynced(
        userId: UserId,
        itemId: ItemId,
        write: suspend ServerDatabaseDao.() -> Unit,
    ): Int {
        write()
        return markUserDataToBeSynced(userId, itemId)
    }

    /**
     * CE (#635): the downloaded movies and episodes of [serverId] whose user data of [userId] has
     * reached the server, so the server's values may replace it. Rows still to be sent are left
     * out: those are newer than what the server has.
     */
    @Query(
        "SELECT itemId FROM userdata WHERE userId = :userId AND toBeSynced = 0 AND (" +
            "itemId IN (SELECT id FROM movies WHERE serverId = :serverId) OR " +
            "itemId IN (SELECT id FROM episodes WHERE serverId = :serverId))"
    )
    suspend fun getSyncedUserDataItemIds(serverId: String, userId: UserId): List<ItemId>

    /** CE (#635): whether any downloaded movie or episode has user data the server may refresh. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM userdata WHERE toBeSynced = 0 " +
            "AND (itemId IN (SELECT id FROM movies) OR itemId IN (SELECT id FROM episodes)))"
    )
    suspend fun hasSyncedUserData(): Boolean

    /**
     * CE (#635): writes the server's user data into a downloaded item's row, so it resumes offline
     * where it was left on any device. A row marked to be sent in the meantime keeps its own
     * values: the condition is in the update itself, so no write can slip in between. Returns 1
     * when the row changed, 0 when it already held these values or is marked.
     */
    @Query(
        "UPDATE userdata SET played = :played, favorite = :favorite, " +
            "playbackPositionTicks = :playbackPositionTicks, lastPlayedDate = :lastPlayedDate " +
            "WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 0 " +
            "AND (played != :played OR favorite != :favorite " +
            "OR playbackPositionTicks != :playbackPositionTicks " +
            "OR lastPlayedDate IS NOT :lastPlayedDate)"
    )
    suspend fun refreshUserDataFromServer(
        userId: UserId,
        itemId: ItemId,
        played: Boolean,
        favorite: Boolean,
        playbackPositionTicks: Long,
        lastPlayedDate: Long?,
    ): Int

    /**
     * CE (#635): another device played the item after this one did, so its progress replaces the
     * row's and the row counts as sent. Only while the row still holds what the sync read
     * (`sent*`), as [clearUserDataToBeSynced] does: a write in between keeps the row marked. The
     * row's favourite is kept; the sync sends it on its own. Returns 1 when the row was taken over.
     */
    @Query(
        "UPDATE userdata SET played = :played, playbackPositionTicks = :playbackPositionTicks, " +
            "lastPlayedDate = :lastPlayedDate, toBeSynced = 0 " +
            "WHERE userId = :userId AND itemId = :itemId AND toBeSynced = 1 " +
            "AND played = :sentPlayed " +
            "AND favorite = :sentFavorite AND playbackPositionTicks = :sentPlaybackPositionTicks " +
            "AND lastPlayedDate IS :sentLastPlayedDate"
    )
    suspend fun takeServerProgress(
        userId: UserId,
        itemId: ItemId,
        played: Boolean,
        playbackPositionTicks: Long,
        lastPlayedDate: Long?,
        sentPlayed: Boolean,
        sentFavorite: Boolean,
        sentPlaybackPositionTicks: Long,
        sentLastPlayedDate: Long?,
    ): Int

    /**
     * Marks a row as sent, but only while it still holds the values that were sent: a write that
     * failed during the sync keeps the row marked.
     */
    @Query(
        "UPDATE userdata SET toBeSynced = 0 WHERE itemId = :itemId AND userId = :userId " +
            "AND played = :played AND favorite = :favorite " +
            "AND playbackPositionTicks = :playbackPositionTicks " +
            "AND lastPlayedDate IS :lastPlayedDate"
    )
    suspend fun clearUserDataToBeSynced(
        userId: UserId,
        itemId: ItemId,
        played: Boolean,
        favorite: Boolean,
        playbackPositionTicks: Long,
        lastPlayedDate: Long?,
    )

    // CE (#775): the lists Room answers while the server is away, see StoredLists. Only the rows of
    // :serverId (#718) and the user data of :userId, and only what has a finished download.

    @Query(
        "SELECT * FROM movies WHERE serverId = :serverId AND $MOVIE_DOWNLOADED ORDER BY name ASC"
    )
    suspend fun getDownloadedMoviesOfServer(serverId: String): List<MovieEntity>

    @Query("SELECT * FROM shows WHERE $SHOW_DOWNLOADED ORDER BY name ASC")
    suspend fun getDownloadedShowsOfServer(serverId: String): List<ShowEntity>

    @Query("SELECT COUNT(*) FROM movies WHERE serverId = :serverId AND $MOVIE_DOWNLOADED")
    suspend fun countDownloadedMoviesOfServer(serverId: String): Int

    @Query("SELECT COUNT(*) FROM shows WHERE $SHOW_DOWNLOADED")
    suspend fun countDownloadedShowsOfServer(serverId: String): Int

    @Query(
        "SELECT * FROM movies WHERE serverId = :serverId AND $MOVIE_DOWNLOADED AND id IN " +
            "(SELECT itemId FROM userdata WHERE userId = :userId AND favorite = 1) ORDER BY name ASC"
    )
    suspend fun getFavoriteMoviesOfServer(serverId: String, userId: UserId): List<MovieEntity>

    @Query(
        "SELECT * FROM shows WHERE $SHOW_DOWNLOADED AND id IN " +
            "(SELECT itemId FROM userdata WHERE userId = :userId AND favorite = 1) ORDER BY name ASC"
    )
    suspend fun getFavoriteShowsOfServer(serverId: String, userId: UserId): List<ShowEntity>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId AND $EPISODE_DOWNLOADED AND id IN " +
            "(SELECT itemId FROM userdata WHERE userId = :userId AND favorite = 1) " +
            "ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun getFavoriteEpisodesOfServer(serverId: String, userId: UserId): List<EpisodeEntity>

    /** [pattern] is a LIKE pattern with `\` as its escape character. */
    @Query(
        "SELECT * FROM movies WHERE serverId = :serverId AND $MOVIE_DOWNLOADED " +
            "AND name LIKE :pattern ESCAPE '\\' ORDER BY name ASC"
    )
    suspend fun searchMoviesOfServer(serverId: String, pattern: String): List<MovieEntity>

    @Query(
        "SELECT * FROM shows WHERE $SHOW_DOWNLOADED AND name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY name ASC"
    )
    suspend fun searchShowsOfServer(serverId: String, pattern: String): List<ShowEntity>

    @Query(
        "SELECT * FROM episodes WHERE serverId = :serverId AND $EPISODE_DOWNLOADED " +
            "AND name LIKE :pattern ESCAPE '\\' " +
            "ORDER BY seriesName ASC, parentIndexNumber ASC, indexNumber ASC"
    )
    suspend fun searchEpisodesOfServer(serverId: String, pattern: String): List<EpisodeEntity>

    @Query("SELECT * FROM userdata WHERE userId = :userId")
    suspend fun getUserDataOfUser(userId: UserId): List<UserDataEntity>

    /** When each movie and show of [serverId] was downloaded: a show's latest episode. */
    @Query(
        "SELECT movies.id AS itemId, MAX(sources.downloadedAt) AS date FROM movies " +
            "JOIN sources ON sources.itemId = movies.id WHERE movies.serverId = :serverId " +
            "GROUP BY movies.id UNION ALL " +
            "SELECT episodes.seriesId AS itemId, MAX(sources.downloadedAt) AS date FROM episodes " +
            "JOIN sources ON sources.itemId = episodes.id WHERE episodes.serverId = :serverId " +
            "GROUP BY episodes.seriesId"
    )
    suspend fun getDownloadDatesOfServer(serverId: String): List<StoredItemDate>

    /** When [userId] last played an episode of each show of [serverId]. */
    @Query(
        "SELECT episodes.seriesId AS itemId, MAX(userdata.lastPlayedDate) AS date FROM episodes " +
            "JOIN userdata ON userdata.itemId = episodes.id AND userdata.userId = :userId " +
            "WHERE episodes.serverId = :serverId GROUP BY episodes.seriesId"
    )
    suspend fun getShowPlayedDatesOfServer(serverId: String, userId: UserId): List<StoredItemDate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrickplayInfo(trickplayInfoDto: ItemTrickplayInfoEntity)

    @Query("SELECT * FROM trickplayInfos WHERE sourceId = :sourceId")
    suspend fun getTrickplayInfo(sourceId: String): ItemTrickplayInfoEntity?
}
