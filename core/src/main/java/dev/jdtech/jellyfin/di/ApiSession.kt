package dev.jdtech.jellyfin.di

import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.ServerWithAddressAndUser
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.models.toServerId
import dev.jdtech.jellyfin.models.toUserId
import dev.jdtech.jellyfin.repository.StoredServers
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * What the API client needs to act for the current server and user: the server's current address,
 * and the current user's token and id. All null when the database has no address for the server.
 *
 * CE (#770): and the server's stored type, which picks the adapter ([ActiveServer.type]). Null when
 * the database has no such server.
 */
internal data class ApiSession(
    val serverId: ServerId,
    val baseUrl: String?,
    val accessToken: String?,
    val userId: UserId?,
    val serverType: String? = ServerType.JELLYFIN.value,
)

/** The session the database describes for [serverId], the way the client was always set up. */
internal fun ServerWithAddressAndUser?.toApiSession(serverId: ServerId): ApiSession {
    val type = this?.server?.type
    val address = this?.address ?: return ApiSession(serverId, null, null, null, type)
    return ApiSession(serverId, address.address, user?.accessToken, user?.id, type)
}

/**
 * #899: [this] as the value the app talks to: the server's type, address and id with the user's
 * token and id, put on together.
 */
internal fun ApiSession.toActiveServer(): ActiveServer =
    ActiveServer(
        type = serverType,
        serverId = serverId,
        baseUrl = baseUrl,
        accessToken = accessToken,
        userId = userId,
    )

/**
 * #763: what the Hilt provider of the client did itself before. CE (#250): the session comes from
 * the copy in preferences, which are already in memory, so whichever thread first asks for the
 * client (often Main, through HomeViewModel) does not wait for Room to open. Then
 * [mirrorApiSession] keeps that copy up to date.
 *
 * #899: it puts the session on the [ActiveServerHolder] as one value, type included, before the
 * holder is handed to anything (`ApiModule`).
 */
internal class SavedSessionRestorer(
    private val appPreferences: AppPreferences,
    private val storedServers: StoredServers,
) {
    fun restore(active: ActiveServerHolder) {
        val serverId = appPreferences.currentServerId()
        val saved = appPreferences.savedApiSession()
        val initial =
            when {
                serverId == null -> null
                saved?.serverId == serverId -> saved
                // No copy for this server yet: the first start after updating to a version that
                // keeps one, or the app was stopped between a server switch and the copy. Read Room
                // once, as every start used to.
                else ->
                    runBlocking {
                        storedServers.serverWithAddressAndUser(serverId).toApiSession(serverId)
                    }
            }
        initial?.let { active.switchTo(it.toActiveServer()) }

        mirrorApiSession(active, appPreferences, storedServers, initial)
    }
}

/** The copy of the session kept in preferences, read without touching Room. */
internal fun AppPreferences.savedApiSession(): ApiSession? {
    val serverId = getValue(sessionServerId)?.toServerId() ?: return null
    val userId = getValue(sessionUserId)?.toUserId()
    return ApiSession(
        serverId,
        getValue(sessionBaseUrl),
        getValue(sessionAccessToken),
        userId,
        // CE (#770): a copy from before servers had a type is a Jellyfin server's.
        getValue(sessionServerType) ?: ServerType.JELLYFIN.value,
    )
}

internal fun AppPreferences.saveApiSession(session: ApiSession?) {
    if (savedApiSession() == session) return
    sharedPreferences
        .edit()
        .putString(sessionServerId.backendName, session?.serverId?.asString())
        .putString(sessionBaseUrl.backendName, session?.baseUrl)
        .putString(sessionAccessToken.backendName, session?.accessToken)
        .putString(sessionUserId.backendName, session?.userId?.asString())
        .putString(sessionServerType.backendName, session?.serverType)
        .apply()
}

/**
 * Keeps the copy in preferences equal to what the database says for the current server, whatever
 * changes it: a login, a server, user or address switch, a deleted user or server. Every one of
 * those writes Room, so watching Room covers them all from one place.
 *
 * The client itself is only corrected once, at the first reading: the copy can be behind when the
 * app was stopped right after a change. Later changes are applied to the client by the code that
 * makes them (SetupRepositoryImpl), as before. The correction is skipped when that code has already
 * changed the client since [initial] was applied.
 *
 * #899: the correction replaces the whole value, type included, and only while the value of
 * [initial] is still on ([ActiveServerHolder.switchTo] with the expected value): no check and then
 * a write that a switch could come in between.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun mirrorApiSession(
    active: ActiveServerHolder,
    appPreferences: AppPreferences,
    storedServers: StoredServers,
    initial: ApiSession?,
) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        var first = true
        appPreferences
            .observeCurrentServerId()
            .flatMapLatest { serverId ->
                if (serverId == null) {
                    flowOf(null)
                } else {
                    storedServers.observeServerWithAddressAndUser(serverId).map {
                        it.toApiSession(serverId)
                    }
                }
            }
            .distinctUntilChanged()
            .catch { e -> Timber.e(e, "Failed to watch the current session") }
            .collect { session ->
                if (first) {
                    first = false
                    if (initial != null && session != null && session != initial) {
                        active.switchTo(session.toActiveServer(), initial.toActiveServer())
                    }
                }
                appPreferences.saveApiSession(session)
            }
    }
}
