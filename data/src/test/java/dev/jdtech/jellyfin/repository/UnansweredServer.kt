package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserDataPort
import dev.jdtech.jellyfin.domain.ports.UserImage
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.UserId
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * #758: a server that never answers, for the tests of what the repository makes of Room while the
 * server is away. Every request fails with [failure]: by default [ServerException.Unreachable],
 * what the Jellyfin adapter throws for a client without an address, the way these tests reached no
 * server before the adapter left `data` (see its SdkErrorsTest). The URLs it would build are null,
 * as the adapter's are without an address. Signed in as [userId].
 */
internal class UnansweredServer(
    var userId: UserId?,
    var failure: () -> Exception = { ServerException.Unreachable() },
) : MediaServer {
    override val type = ServerType.JELLYFIN

    /** #769: as a Jellyfin server, so the repository asks for everything it would ask Jellyfin. */
    override val capabilities = ServerCapabilities.ALL

    private fun fail(): Nothing = throw failure()

    override val session =
        object : ServerSession {
            override val baseUrl: String? = null
            override val userId: UserId?
                get() = this@UnansweredServer.userId

            override val accessToken: String? = null
            override val unauthorized: Flow<String> = emptyFlow()
            override val serverReturned: Flow<Unit> = emptyFlow()
            override var requestWatcher: RequestWatcher? = null
            override val probeTimeoutMs: Long = ServerSession.MAX_PROBE_TIMEOUT_MS

            override fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
                this@UnansweredServer.userId = userId
            }

            override suspend fun probe(): Boolean? = null

            override suspend fun isTokenValid(): Boolean = fail()

            override fun keepAlive(): Flow<Nothing> = emptyFlow()

            override fun userImageUrl(userId: UserId, tag: String): String? = null

            override suspend fun updateDeviceName(name: String) = fail()

            override suspend fun autoPlayNextEpisode(): Boolean? = fail()

            override suspend fun currentUserImage(): UserImage = fail()
        }

    override val catalog =
        object : CatalogPort {
            override suspend fun serverInfo(): ServerInfo = fail()

            override suspend fun libraries(): List<FindroidCollection> = fail()

            override suspend fun userViews(): List<FindroidCollection> = fail()

            override suspend fun item(id: ItemId): FindroidItem? = fail()

            override suspend fun movie(id: ItemId): FindroidMovie = fail()

            override suspend fun show(id: ItemId): FindroidShow = fail()

            override suspend fun season(id: ItemId): FindroidSeason = fail()

            override suspend fun episode(id: ItemId): FindroidEpisode = fail()

            override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage = fail()

            override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int = fail()

            override suspend fun randomItemsWithBackdrop(
                kinds: List<ItemKind>,
                limit: Int,
            ): List<FindroidItem> = fail()

            override suspend fun person(id: ItemId): FindroidPerson = fail()

            override suspend fun personItems(
                personIds: List<ItemId>,
                kinds: List<ItemKind>?,
                recursive: Boolean,
                limit: Int?,
            ): List<FindroidItem> = fail()

            override suspend fun favorites(): List<FindroidItem> = fail()

            override suspend fun search(query: String, kinds: List<ItemKind>?): List<FindroidItem> =
                fail()

            override suspend fun suggestions(): List<FindroidItem> = fail()

            override suspend fun resumeItems(): List<FindroidItem> = fail()

            override suspend fun latest(
                parentId: ItemId,
                kinds: List<ItemKind>?,
            ): List<FindroidItem> = fail()

            override suspend fun seasons(seriesId: ItemId): List<FindroidSeason> = fail()

            override suspend fun nextUp(
                seriesId: ItemId?,
                dateCutoff: LocalDateTime?,
            ): List<FindroidEpisode> = fail()

            override suspend fun episodes(
                seriesId: ItemId,
                seasonId: ItemId?,
                fields: Set<EpisodeField>?,
                startItemId: ItemId?,
                limit: Int?,
            ): List<FindroidEpisode> = fail()

            override suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem> = fail()

            override suspend fun extras(
                itemId: ItemId,
                localTrailers: Boolean,
                specialFeatures: Boolean,
            ): List<FindroidMovie> = fail()

            override suspend fun collectionsOf(itemId: ItemId): List<FindroidItem> = fail()

            override suspend fun themeSongUrl(itemId: ItemId): String? = fail()
        }

    override val userData =
        object : UserDataPort {
            override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) = fail()

            override suspend fun setPlayed(itemId: ItemId, played: Boolean) = fail()
        }

    override val playback =
        object : PlaybackPort {
            override suspend fun sources(request: PlaybackRequest): List<FindroidSource> = fail()

            override suspend fun streamUrl(itemId: ItemId, mediaSourceId: MediaSourceId): String =
                fail()

            override suspend fun segments(itemId: ItemId): List<FindroidSegment> = fail()

            override suspend fun trickplayTile(
                itemId: ItemId,
                width: Int,
                index: Int,
                mediaSourceId: MediaSourceId?,
            ): ByteArray? = fail()

            override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
                null

            override fun seasonBackdropUrl(seasonId: ItemId): String? = null

            override suspend fun reportStart(report: PlaybackReport) = fail()

            override suspend fun reportProgress(report: PlaybackReport) = fail()

            override suspend fun reportStopped(report: PlaybackReport) = fail()

            override suspend fun announceCapabilities() = fail()

            override suspend fun resumeThresholds(): ResumeThresholds? = fail()
        }
}
