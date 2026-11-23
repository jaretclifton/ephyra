package dev.jdtech.jellyfin.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.core.presentation.downloader.SmartDownloads
import dev.jdtech.jellyfin.core.server.forStoredType
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.ServerUserData
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPort
import dev.jdtech.jellyfin.domain.ports.UserDataSyncPorts
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.UserDataEntity
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.serverExceptionOrNull
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.PlaybackEvents
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    val database: ServerDatabaseDao,
    /** CE (#770): by server type; each server's users go through the ports of its type. */
    private val userDataSyncPorts: Map<ServerType, @JvmSuppressWildcards UserDataSyncPorts>,
    private val smartDownloads: SmartDownloads,
    private val playbackEvents: PlaybackEvents,
    private val dataChanges: DataChangeEvents,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val servers = database.getServers()
            var hadFailures = false
            var changed = false

            for (server in servers) {
                val serverWithAddressesAndUsers =
                    database.getServerWithAddressesAndUsers(server.id) ?: continue
                val serverAddress =
                    serverWithAddressesAndUsers.addresses.firstOrNull {
                        it.id == server.currentServerAddressId
                    } ?: continue
                // CE (#770): a server of a type this build cannot talk to keeps its rows marked,
                // for a build that can. Not a failure: retrying would not change it.
                val ports = userDataSyncPorts.forStoredType(server.type)
                if (ports == null) {
                    Timber.w("No adapter for server type %s, not syncing it", server.type)
                    continue
                }
                for (user in serverWithAddressesAndUsers.users) {
                    // Only the rows still marked to be synced, and the downloads whose progress
                    // the server may have moved on: without either, no item is loaded, no client
                    // is built and no request is made (#250).
                    val pending = database.getAllUserDataToBeSynced(server.id, user.id)
                    val refresh =
                        refreshesDownloads(
                            hasDownloads =
                                database.getSyncedUserDataItemIds(server.id, user.id).isNotEmpty(),
                            playingHere = playbackEvents.playingItemId.value,
                        )
                    if (pending.isEmpty() && !refresh) continue

                    val port = ports.forUser(serverAddress.address, user.accessToken, user.id)
                    var ok = true
                    if (pending.isNotEmpty()) {
                        val outcome = syncUserData(port, user.id, pending)
                        if (!outcome.allOk) ok = false
                        if (outcome.sent > 0 || outcome.tookServer > 0) changed = true
                    }
                    // CE (#635): after sending, so the server's values include what was just sent.
                    if (ok && refresh && refreshFromServer(port, server.id, user.id) > 0) {
                        changed = true
                    }
                    // A write that failed while this run was sending keeps its row marked, and
                    // asking for a sync during a run does nothing: run again for those. CE (#635):
                    // also for a row marked during a run that only refreshed.
                    val stillMarked = database.getAllUserDataToBeSynced(server.id, user.id)
                    if (needsAnotherRun(sent = pending, stillMarked = stillMarked)) ok = false
                    if (!ok) hadFailures = true
                }
            }

            // CE (#633): what the screens show changed (Continue Watching, positions), so the
            // screen on display reloads, as after a playback stop. Also after a partly failed run.
            // CE (#635): also when the server's progress replaced a row here.
            if (changed) dataChanges.onSynced()

            if (hadFailures) {
                Result.retry()
            } else {
                // Progress watched offline has reached the server, so what to remove can be told.
                // Without completed downloads this is a single query and no requests.
                try {
                    smartDownloads.checkAll()
                } catch (e: Exception) {
                    Timber.e(e, "Smart Downloads check after sync failed")
                }
                Result.success()
            }
        }
    }

    /**
     * CE (#635): logs [message] with [e]'s stack trace, as a warning when [warn]. A refused login
     * ([isRefusedLogin]) is one line instead: a user signed out on the server, or whose login
     * expired, gets one on every run for as long as it stays on this device, and the trace tells
     * nothing more.
     */
    private fun logFailure(e: Exception, message: String, warn: Boolean = false) {
        when {
            isRefusedLogin(e) -> Timber.w("%s: the server refused this user's login (401)", message)
            warn -> Timber.w(e, message)
            else -> Timber.e(e, message)
        }
    }

    /**
     * CE (#633): how a run for one user went: whether to retry, and how many rows were sent. CE
     * (#635): and how many rows took the server's newer progress instead.
     */
    private data class SyncOutcome(val allOk: Boolean, val sent: Int, val tookServer: Int = 0)

    /**
     * CE (#635): copies the server's user data into the rows of [userId]'s downloads from
     * [serverId], so a download resumes offline where it was left on any device (web, TV), not
     * where it was when it was downloaded. Uses [UserDataSyncPort.userDataOf] (#633).
     *
     * Rows waiting to be sent are never touched, see [ServerDatabaseDao.refreshUserDataFromServer].
     * A failure only leaves the rows as they were, so it does not make the sync retry. Returns how
     * many rows changed.
     */
    private suspend fun refreshFromServer(
        port: UserDataSyncPort,
        serverId: String,
        userId: UserId,
    ): Int {
        val itemIds = database.getSyncedUserDataItemIds(serverId, userId)
        if (itemIds.isEmpty()) return 0
        var changed = 0
        try {
            val onServer = port.userDataOf(itemIds)
            for (userData in serverUserDataToStore(onServer, playbackEvents.playingItemId.value)) {
                changed +=
                    database.refreshUserDataFromServer(
                        userId = userId,
                        itemId = userData.itemId,
                        played = userData.played,
                        favorite = userData.favorite,
                        playbackPositionTicks = userData.playbackPositionTicks,
                        lastPlayedDate = userData.lastPlayedMillis(),
                    )
            }
            Timber.d("Refreshed the user data of ${itemIds.size} downloads, $changed changed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(
                e,
                "Could not refresh the user data of downloads from the server",
                warn = true,
            )
        }
        return changed
    }

    /**
     * CE (#635): [row] was played here before another device played the item ([server] is newer),
     * so the server's progress wins: it is copied into the row and nothing of the phone's progress
     * is sent. A favourite changed here is still sent, on its own. A session the server still has
     * playing for this device is ended at the server's own position, or its idle check would end it
     * later with the position it last heard from here (#584). Returns whether the row took the
     * server's progress.
     */
    private suspend fun keepServerProgress(
        port: UserDataSyncPort,
        userId: UserId,
        row: UserDataEntity,
        server: ServerUserData,
        leftPlaying: ItemId?,
    ): Boolean {
        Timber.d(
            "Item ${row.itemId.asString()} was played later elsewhere, keeping the server's progress"
        )
        if (endsStaleSession(row.itemId, leftPlaying, playbackEvents.playingItemId.value)) {
            port.reportStopped(row.itemId, server.playbackPositionTicks)
        }
        if (row.favorite != server.favorite) {
            port.setFavorite(row.itemId, row.favorite)
        }
        val took =
            database.takeServerProgress(
                userId = userId,
                itemId = row.itemId,
                played = server.played,
                playbackPositionTicks = server.playbackPositionTicks,
                lastPlayedDate = server.lastPlayedMillis(),
                sentPlayed = row.played,
                sentFavorite = row.favorite,
                sentPlaybackPositionTicks = row.playbackPositionTicks,
                sentLastPlayedDate = row.lastPlayedDate,
            ) > 0
        database.deleteUserDataIfOrphaned(userId, row.itemId)
        return took
    }

    private suspend fun syncUserData(
        port: UserDataSyncPort,
        userId: UserId,
        items: List<UserDataEntity>,
    ): SyncOutcome {
        // The item the server still has "playing" for this device: its stop report did not get
        // through. Asked first, so an unreachable server ends the run after one request.
        val leftPlaying =
            try {
                port.itemLeftPlaying()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.serverStatus() == null) {
                    Timber.e(e, "Failed to sync user data: the server was not reached")
                    return SyncOutcome(allOk = false, sent = 0)
                }
                logFailure(e, "Could not read the playback session", warn = true)
                null
            }

        // CE (#633): what the server has now, so a row only sends its LastPlayedDate when it is
        // newer: another device that watched the item later keeps it at the top of Continue
        // Watching. Read before anything is sent, also before a stale session's stop below, which
        // sets the server's date to now. Without the answer nothing is sent: the run is retried,
        // rather than sending dates that may be older than the server's.
        val onServer =
            try {
                port.userDataOf(items.map { it.itemId })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(e, "Failed to sync user data: the server's user data could not be read")
                return SyncOutcome(allOk = false, sent = 0)
            }

        var allOk = true
        var sent = 0
        var tookServer = 0
        for (userData in items) {
            try {
                val server = onServer[userData.itemId]
                if (
                    server != null &&
                        serverIsNewer(userData.lastPlayedDate, server.lastPlayedMillis())
                ) {
                    if (keepServerProgress(port, userId, userData, server, leftPlaying)) {
                        tookServer++
                    }
                    continue
                }

                if (
                    endsStaleSession(
                        rowItemId = userData.itemId,
                        leftPlayingOnServer = leftPlaying,
                        playingHere = playbackEvents.playingItemId.value,
                    )
                ) {
                    // End it with the real position, or the server's idle check ends it minutes
                    // later with the last position it heard, over what is written below (#584).
                    Timber.d(
                        "Ending the server's playback session of ${userData.itemId.asString()}"
                    )
                    port.reportStopped(userData.itemId, userData.playbackPositionTicks)
                }

                port.putUserData(
                    ServerUserData(
                        itemId = userData.itemId,
                        played = userData.played,
                        favorite = userData.favorite,
                        playbackPositionTicks = userData.playbackPositionTicks,
                        // CE (#633): moves the item up in Continue Watching on every device. Null
                        // leaves the server's date as it is.
                        lastPlayedDate =
                            userData.lastPlayedDate
                                ?.takeIf { isNewerThanServer(it, server?.lastPlayedMillis()) }
                                ?.let(::localDateTimeOf),
                    )
                )
                sent++

                database.clearUserDataToBeSynced(
                    userId = userId,
                    itemId = userData.itemId,
                    played = userData.played,
                    favorite = userData.favorite,
                    playbackPositionTicks = userData.playbackPositionTicks,
                    lastPlayedDate = userData.lastPlayedDate,
                )
                // A row kept only for this sync, because its download was deleted first.
                database.deleteUserDataIfOrphaned(userId, userData.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = e.serverStatus()
                if (status == null) {
                    // The server was not reached: the other rows would fail the same way, each
                    // after its own timeout.
                    Timber.e(e, "Failed to sync user data, leaving the rest for the next run")
                    return SyncOutcome(allOk = false, sent = sent, tookServer = tookServer)
                }
                when (afterStatus(status)) {
                    AfterStatus.DROP -> {
                        // The item is gone from the server: there is nothing to send, ever.
                        // Without this the row of a deleted download would be retried for good.
                        Timber.w(
                            "Item ${userData.itemId.asString()} is gone from the server, dropping it"
                        )
                        database.setUserDataToBeSynced(userId, userData.itemId, false)
                        database.deleteUserDataIfOrphaned(userId, userData.itemId)
                    }
                    AfterStatus.LEAVE ->
                        // Refused, and a retry in 30 seconds would be refused again. The row
                        // stays marked for the next sync: app start, foreground, the next write.
                        Timber.w("The server refused ${userData.itemId.asString()} with $status")
                    AfterStatus.RETRY -> {
                        Timber.e(e, "Failed to sync user data")
                        allOk = false
                    }
                }
            }
        }
        return SyncOutcome(allOk = allOk, sent = sent, tookServer = tookServer)
    }
}

/**
 * CE (#633): the server's last played date in epoch milliseconds, as the database keeps it and the
 * sync compares it; null when the server has none. #762: the port carries the server's local
 * date-time, the conversion stays here.
 */
internal fun ServerUserData.lastPlayedMillis(zone: ZoneId = ZoneId.systemDefault()): Long? =
    lastPlayedDate?.let {
        epochMillisOf(it, zone)
    }

/**
 * CE (#633): whether a local last-played time [local] is newer than the server's [server], both in
 * epoch milliseconds. Unknown locally is never newer; unknown on the server is always older. An
 * equal time is not newer: there is nothing to move.
 */
internal fun isNewerThanServer(local: Long?, server: Long?): Boolean =
    local != null && (server == null || local > server)

/**
 * CE (#635): whether the server's last-played time [server] is newer than the local one [local]:
 * another device played the item after this one did. The other side of [isNewerThanServer]; an
 * equal time is not newer.
 */
internal fun serverIsNewer(local: Long?, server: Long?): Boolean =
    isNewerThanServer(local = server, server = local)

/**
 * CE (#633): the port, as the SDK under it, reads and writes dates as local date-times of this
 * device's time zone, and the database keeps epoch milliseconds, which do not depend on it.
 */
internal fun localDateTimeOf(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)

/** CE (#633): the reverse of [localDateTimeOf]. */
internal fun epochMillisOf(dateTime: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    dateTime.atZone(zone).toInstant().toEpochMilli()

/**
 * CE (#635): the server's user data to store in the rows of downloads. The item open in the player
 * here ([playingHere]) is left out: its own reports keep its row current, and the server's value
 * may be a few seconds behind them.
 */
internal fun serverUserDataToStore(
    onServer: Map<ItemId, ServerUserData>,
    playingHere: ItemId?,
): List<ServerUserData> = onServer.values.filter { it.itemId != playingHere }

/**
 * CE (#635): whether a run refreshes the downloads' user data from the server: only with downloads,
 * and not while the player here has an item open ([playingHere]): its own reports keep that item
 * current, and a refresh would read the server for nothing. The player is closed before it reports
 * the stop, so the run that sends the stop refreshes.
 */
internal fun refreshesDownloads(hasDownloads: Boolean, playingHere: ItemId?): Boolean =
    hasDownloads && playingHere == null

/**
 * Whether the sync of [rowItemId] has to end a playback session first: the server still has the
 * item playing for this device ([leftPlayingOnServer]) while the player here has closed it. With
 * the item open in the player here ([playingHere]) the session is alive and reports for itself.
 */
internal fun endsStaleSession(
    rowItemId: ItemId,
    leftPlayingOnServer: ItemId?,
    playingHere: ItemId?,
): Boolean = leftPlayingOnServer == rowItemId && playingHere != rowItemId

internal enum class AfterStatus {
    DROP,
    LEAVE,
    RETRY,
}

/** CE (#635): whether [e] is the server refusing the user's login: signed out there, or expired. */
internal fun isRefusedLogin(e: Throwable): Boolean = e.serverStatus() == HTTP_UNAUTHORIZED

/** The error status the server answered with, or null when it did not answer. */
private fun Throwable.serverStatus(): Int? =
    (serverExceptionOrNull() as? ServerException.HttpStatus)?.status

/** What an error status from the server means for the row that was sent. */
internal fun afterStatus(status: Int): AfterStatus =
    when {
        status == HTTP_NOT_FOUND -> AfterStatus.DROP
        status >= HTTP_SERVER_ERROR -> AfterStatus.RETRY
        else -> AfterStatus.LEAVE
    }

/**
 * Whether rows were marked or changed while the run was sending [sent]: those were not sent, or not
 * in their current state. A row that is still marked exactly as it was sent failed in this run, and
 * that failure decides for itself.
 */
internal fun needsAnotherRun(
    sent: List<UserDataEntity>,
    stillMarked: List<UserDataEntity>,
): Boolean = stillMarked.any { it !in sent }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_SERVER_ERROR = 500
