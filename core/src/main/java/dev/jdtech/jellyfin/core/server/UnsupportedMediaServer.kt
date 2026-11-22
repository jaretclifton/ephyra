package dev.jdtech.jellyfin.core.server

import dev.jdtech.jellyfin.core.presentation.session.NoServerSession
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackPort
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UnsupportedServerTypeException
import dev.jdtech.jellyfin.domain.ports.UserDataPort
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
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.repository.ResumeThresholds
import java.time.LocalDateTime

/**
 * #770: a server of a type this build has no adapter for, [storedType] as saved. It offers nothing
 * ([ServerCapabilities.NONE]), has no session ([NoServerSession]), and every request throws
 * [UnsupportedServerTypeException], which the screens show as a load error the way they show any
 * other failed load. The URLs, which are not requests, are null as without a server. Downloads
 * still open from Room, and the server can be switched or removed in the settings.
 */
internal class UnsupportedMediaServer(private val storedType: String) : MediaServer {
    private fun unsupported(): Nothing = throw UnsupportedServerTypeException(storedType)

    /** There is no [ServerType] for it. Nothing outside the selector asks. */
    override val type: ServerType
        get() = unsupported()

    override val capabilities: ServerCapabilities = ServerCapabilities.NONE

    override val session: ServerSession = NoServerSession

    override val catalog: CatalogPort =
        object : CatalogPort {
            override suspend fun serverInfo(): ServerInfo = unsupported()

            override suspend fun libraries(): List<FindroidCollection> = unsupported()

            override suspend fun userViews(): List<FindroidCollection> = unsupported()

            override suspend fun item(id: ItemId): FindroidItem? = unsupported()

            override suspend fun movie(id: ItemId): FindroidMovie = unsupported()

            override suspend fun show(id: ItemId): FindroidShow = unsupported()

            override suspend fun season(id: ItemId): FindroidSeason = unsupported()

            override suspend fun episode(id: ItemId): FindroidEpisode = unsupported()

            override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage =
                unsupported()

            override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int =
                unsupported()

            override suspend fun randomItemsWithBackdrop(
                kinds: List<ItemKind>,
                limit: Int,
            ): List<FindroidItem> = unsupported()

            override suspend fun person(id: ItemId): FindroidPerson = unsupported()

            override suspend fun personItems(
                personIds: List<ItemId>,
                kinds: List<ItemKind>?,
                recursive: Boolean,
                limit: Int?,
            ): List<FindroidItem> = unsupported()

            override suspend fun favorites(): List<FindroidItem> = unsupported()

            override suspend fun search(query: String, kinds: List<ItemKind>?): List<FindroidItem> =
                unsupported()

            override suspend fun suggestions(): List<FindroidItem> = unsupported()

            override suspend fun resumeItems(): List<FindroidItem> = unsupported()

            override suspend fun latest(
                parentId: ItemId,
                kinds: List<ItemKind>?,
            ): List<FindroidItem> = unsupported()

            override suspend fun seasons(seriesId: ItemId): List<FindroidSeason> = unsupported()

            override suspend fun nextUp(
                seriesId: ItemId?,
                dateCutoff: LocalDateTime?,
            ): List<FindroidEpisode> = unsupported()

            override suspend fun episodes(
                seriesId: ItemId,
                seasonId: ItemId?,
                fields: Set<EpisodeField>?,
                startItemId: ItemId?,
                limit: Int?,
            ): List<FindroidEpisode> = unsupported()

            override suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem> =
                unsupported()

            override suspend fun extras(
                itemId: ItemId,
                localTrailers: Boolean,
                specialFeatures: Boolean,
            ): List<FindroidMovie> = unsupported()

            override suspend fun collectionsOf(itemId: ItemId): List<FindroidItem> = unsupported()

            override suspend fun themeSongUrl(itemId: ItemId): String? = unsupported()
        }

    override val userData: UserDataPort =
        object : UserDataPort {
            override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) = unsupported()

            override suspend fun setPlayed(itemId: ItemId, played: Boolean) = unsupported()
        }

    override val playback: PlaybackPort =
        object : PlaybackPort {
            override suspend fun sources(request: PlaybackRequest): List<FindroidSource> =
                unsupported()

            override suspend fun streamUrl(itemId: ItemId, mediaSourceId: MediaSourceId): String =
                unsupported()

            override suspend fun segments(itemId: ItemId): List<FindroidSegment> = unsupported()

            override suspend fun trickplayTile(itemId: ItemId, width: Int, index: Int): ByteArray? =
                unsupported()

            override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
                null

            override fun seasonBackdropUrl(seasonId: ItemId): String? = null

            override suspend fun reportStart(report: PlaybackReport) = unsupported()

            override suspend fun reportProgress(report: PlaybackReport) = unsupported()

            override suspend fun reportStopped(report: PlaybackReport) = unsupported()

            override suspend fun announceCapabilities() = unsupported()

            override suspend fun resumeThresholds(): ResumeThresholds? = unsupported()
        }
}
