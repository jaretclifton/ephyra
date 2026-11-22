package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.CatalogPort.Companion.SEARCH_LIMIT
import dev.jdtech.jellyfin.domain.ports.CatalogPort.Companion.SEARCH_LIMIT_PER_KIND
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
import dev.jdtech.jellyfin.models.FindroidBoxSet
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerException
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.ServerType
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.repository.ResumeThresholds
import dev.jdtech.jellyfin.repository.stopUserData
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A media server in memory (#771), for the tests of anything above the ports: it presents [library]
 * as an adapter would, and passes the same contract suites as the Jellyfin adapter
 * (`FakeMediaServerContractTest`), which is what keeps it honest.
 *
 * Like an adapter, it knows nothing about downloads: items carry only their remote sources. A test
 * that needs a downloaded item puts the fake under the repository together with a database.
 *
 * Every port call is listed in [calls] by its name (`item`, `search`, `sources`, …). Thread-safe
 * enough for a repository that calls on the IO dispatcher.
 */
class FakeMediaServer(
    private val library: FakeLibrary = ContractLibrary,
    override val capabilities: ServerCapabilities = ServerCapabilities.ALL,
    override val type: ServerType = ServerType.JELLYFIN,
) : MediaServer {
    /** The user data the server keeps, which [userData] and the reports change. */
    private data class Marks(val played: Boolean, val favorite: Boolean, val positionTicks: Long)

    private val marks: MutableMap<ItemId, Marks> =
        Collections.synchronizedMap(
            library.items.associateTo(mutableMapOf()) {
                it.id to Marks(it.played, it.favorite, it.playbackPositionTicks)
            }
        )

    @Volatile private var failure: ServerException? = null
    @Volatile private var failingCalls: Set<String>? = null

    private val callLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reportLog: MutableList<Pair<ReportKind, PlaybackReport>> =
        Collections.synchronizedList(mutableListOf())

    /** The names of the port calls made, in order. */
    val calls: List<String>
        get() = synchronized(callLog) { callLog.toList() }

    enum class ReportKind {
        START,
        PROGRESS,
        STOPPED,
    }

    /** What was reported, in order, with its kind. */
    val reported: List<Pair<ReportKind, PlaybackReport>>
        get() = synchronized(reportLog) { reportLog.toList() }

    /** What was reported, in order. */
    val reports: List<PlaybackReport>
        get() = reported.map { it.second }

    /**
     * Make the next calls fail the way a server does: every call, or only those named in [only]
     * (the names of [calls]). Null makes them succeed again.
     */
    fun failWith(error: ServerException?, only: Set<String>? = null) {
        failure = error
        failingCalls = only
    }

    private fun <T> answer(name: String, block: () -> T): T {
        callLog += name
        val error = failure
        if (error != null && failingCalls.let { it == null || name in it }) throw error
        return block()
    }

    private suspend fun <T> call(name: String, block: suspend () -> T): T {
        callLog += name
        val error = failure
        if (error != null && failingCalls.let { it == null || name in it }) throw error
        return block()
    }

    // The library as the user sees it now.

    private fun notFound(): Nothing = throw ServerException.HttpStatus(404)

    private fun stored(id: ItemId): FindroidItem? =
        library.items.firstOrNull { it.id == id } ?: library.libraries.firstOrNull { it.id == id }

    private fun episodesOf(seriesId: ItemId): List<FindroidEpisode> =
        library.items
            .filterIsInstance<FindroidEpisode>()
            .filter { it.seriesId == seriesId }
            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
            .map { current(it) as FindroidEpisode }

    private fun current(item: FindroidItem): FindroidItem {
        val mark = marks[item.id]
        return when (item) {
            is FindroidMovie ->
                mark?.let {
                    item.copy(
                        played = it.played,
                        favorite = it.favorite,
                        playbackPositionTicks = it.positionTicks,
                    )
                } ?: item
            is FindroidEpisode ->
                mark?.let {
                    item.copy(
                        played = it.played,
                        favorite = it.favorite,
                        playbackPositionTicks = it.positionTicks,
                    )
                } ?: item
            is FindroidShow -> {
                val unplayed = episodesOf(item.id).count { !it.played }
                item.copy(
                    played = unplayed == 0,
                    favorite = mark?.favorite ?: item.favorite,
                    unplayedItemCount = unplayed,
                )
            }
            is FindroidSeason -> {
                val unplayed =
                    library.items.filterIsInstance<FindroidEpisode>().count {
                        it.seasonId == item.id && marks[it.id]?.played != true
                    }
                item.copy(
                    played = unplayed == 0,
                    favorite = mark?.favorite ?: item.favorite,
                    unplayedItemCount = unplayed,
                )
            }
            is FindroidBoxSet -> mark?.let { item.copy(favorite = it.favorite) } ?: item
            else -> item
        }
    }

    private fun currentItem(id: ItemId): FindroidItem = current(stored(id) ?: notFound())

    private fun kindOf(item: FindroidItem): ItemKind =
        when (item) {
            is FindroidMovie -> ItemKind.MOVIE
            is FindroidShow -> ItemKind.SERIES
            is FindroidSeason -> ItemKind.SEASON
            is FindroidEpisode -> ItemKind.EPISODE
            is FindroidBoxSet -> ItemKind.BOX_SET
            is FindroidCollection -> ItemKind.COLLECTION_FOLDER
            else -> ItemKind.UNKNOWN
        }

    /** The direct children of [id], or of the user's root (the libraries) for null. */
    private fun childrenOf(id: ItemId?): List<FindroidItem> {
        if (id == null) return library.libraries
        library.children[id]?.let { ids ->
            return ids.mapNotNull(::stored)
        }
        val parent = stored(id) ?: notFound()
        return when (parent) {
            is FindroidShow ->
                library.items
                    .filterIsInstance<FindroidSeason>()
                    .filter { it.seriesId == id }
                    .sortedBy { it.indexNumber }
            is FindroidSeason ->
                library.items
                    .filterIsInstance<FindroidEpisode>()
                    .filter { it.seasonId == id }
                    .sortedBy { it.indexNumber }
            else -> emptyList()
        }
    }

    private fun descendantsOf(id: ItemId?): List<FindroidItem> =
        childrenOf(id).flatMap { listOf(it) + descendantsOf(it.id) }.distinctBy { it.id }

    private fun select(query: ItemQuery): List<FindroidItem> {
        val candidates =
            when {
                query.parentId == null && query.recursive -> library.items
                query.recursive -> descendantsOf(query.parentId)
                else -> childrenOf(query.parentId)
            }
        val comparator: Comparator<FindroidItem> =
            when (query.sortBy) {
                SortBy.NAME -> compareBy { it.name.lowercase() }
                else -> compareBy<FindroidItem> { it.name.lowercase() }
            }
        return candidates
            .map(::current)
            .filter { query.kinds == null || kindOf(it) in query.kinds!! }
            .filter { ItemFilter.IS_UNPLAYED !in query.filters || !it.played }
            .sortedWith(
                if (query.sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
            )
    }

    private inner class Catalog : CatalogPort {
        override suspend fun serverInfo(): ServerInfo = call("serverInfo") { library.server }

        override suspend fun libraries(): List<FindroidCollection> =
            call("libraries") { library.libraries }

        override suspend fun userViews(): List<FindroidCollection> =
            call("userViews") { library.libraries }

        override suspend fun item(id: ItemId): FindroidItem? = call("item") { currentItem(id) }

        override suspend fun movie(id: ItemId): FindroidMovie =
            call("movie") { currentItem(id) as? FindroidMovie ?: notFound() }

        override suspend fun show(id: ItemId): FindroidShow =
            call("show") { currentItem(id) as? FindroidShow ?: notFound() }

        override suspend fun season(id: ItemId): FindroidSeason =
            call("season") { currentItem(id) as? FindroidSeason ?: notFound() }

        override suspend fun episode(id: ItemId): FindroidEpisode =
            call("episode") { currentItem(id) as? FindroidEpisode ?: notFound() }

        override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage =
            call("items") {
                val all = select(query)
                val page = all.drop(query.startIndex ?: 0).take(query.limit ?: Int.MAX_VALUE)
                ItemsPage(page, totalRecordCount = if (includeTotal) all.size else null)
            }

        override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int =
            call("itemCount") {
                select(query).count { nameLessThan == null || it.name < nameLessThan }
            }

        override suspend fun randomItemsWithBackdrop(
            kinds: List<ItemKind>,
            limit: Int,
        ): List<FindroidItem> =
            call("randomItemsWithBackdrop") {
                library.items
                    .filter { kindOf(it) in kinds && it.images.backdrop != null }
                    .map(::current)
                    .shuffled()
                    .take(limit)
            }

        override suspend fun person(id: ItemId): FindroidPerson =
            call("person") { library.people.firstOrNull { it.id == id } ?: notFound() }

        override suspend fun personItems(
            personIds: List<ItemId>,
            kinds: List<ItemKind>?,
            recursive: Boolean,
            limit: Int?,
        ): List<FindroidItem> =
            call("personItems") {
                personIds
                    .flatMap { library.personItems[it].orEmpty() }
                    .distinct()
                    .mapNotNull(::stored)
                    .map(::current)
                    .filter { kinds == null || kindOf(it) in kinds }
                    .take(limit ?: Int.MAX_VALUE)
            }

        override suspend fun favorites(): List<FindroidItem> =
            call("favorites") {
                library.items.map(::current).filter {
                    it.favorite &&
                        kindOf(it) in listOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.EPISODE)
                }
            }

        override suspend fun search(query: String, kinds: List<ItemKind>?): List<FindroidItem> =
            call("search") {
                fun matching(kind: ItemKind, limit: Int) =
                    library.items
                        .filter { kindOf(it) == kind && it.name.contains(query, ignoreCase = true) }
                        .map(::current)
                        .take(limit)
                if (kinds == null) {
                    (matching(ItemKind.MOVIE, SEARCH_LIMIT) +
                            matching(ItemKind.SERIES, SEARCH_LIMIT))
                        .take(SEARCH_LIMIT)
                } else {
                    kinds.flatMap { matching(it, SEARCH_LIMIT_PER_KIND) }
                }
            }

        override suspend fun suggestions(): List<FindroidItem> =
            call("suggestions") {
                library.items
                    .filter { kindOf(it) == ItemKind.MOVIE || kindOf(it) == ItemKind.SERIES }
                    .map(::current)
                    .take(6)
            }

        override suspend fun resumeItems(): List<FindroidItem> =
            call("resumeItems") {
                library.items
                    .map(::current)
                    .filter {
                        (it is FindroidMovie || it is FindroidEpisode) &&
                            !it.played &&
                            it.playbackPositionTicks > 0
                    }
                    .take(12)
            }

        override suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<FindroidItem> =
            call("latest") {
                childrenOf(parentId)
                    .map(::current)
                    .filter { kinds == null || kindOf(it) in kinds }
                    .take(16)
            }

        override suspend fun seasons(seriesId: ItemId): List<FindroidSeason> =
            call("seasons") {
                if (stored(seriesId) !is FindroidShow) notFound()
                childrenOf(seriesId).map { current(it) as FindroidSeason }
            }

        /**
         * Per series, the first unplayed episode after the last played one, specials left out; for
         * a series asked for by id also when none is played. As Jellyfin sends them: without
         * trickplay and chapters, which the player fetches with the episode itself.
         */
        override suspend fun nextUp(
            seriesId: ItemId?,
            dateCutoff: LocalDateTime?,
        ): List<FindroidEpisode> =
            call("nextUp") {
                val series =
                    if (seriesId != null) listOf(seriesId)
                    else library.items.filterIsInstance<FindroidShow>().map { it.id }
                series
                    .mapNotNull { id ->
                        val episodes = episodesOf(id).filter { it.parentIndexNumber > 0 }
                        val lastPlayed = episodes.indexOfLast { it.played }
                        if (lastPlayed < 0 && seriesId == null) return@mapNotNull null
                        episodes.drop(lastPlayed + 1).firstOrNull { !it.played }
                    }
                    .map { it.copy(trickplayInfo = null, chapters = emptyList()) }
            }

        override suspend fun episodes(
            seriesId: ItemId,
            seasonId: ItemId?,
            fields: Set<EpisodeField>?,
            startItemId: ItemId?,
            limit: Int?,
        ): List<FindroidEpisode> =
            call("episodes") {
                if (stored(seriesId) !is FindroidShow) notFound()
                val episodes =
                    episodesOf(seriesId).filter { seasonId == null || it.seasonId == seasonId }
                val start = startItemId?.let { id -> episodes.indexOfFirst { it.id == id } } ?: 0
                episodes.drop(start.coerceAtLeast(0)).take(limit ?: Int.MAX_VALUE)
            }

        override suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem> =
            call("similar") {
                if (!capabilities.similarItems) return@call emptyList()
                val item = stored(itemId) ?: notFound()
                library.items
                    .filter { it.id != itemId && kindOf(it) == kindOf(item) }
                    .map(::current)
                    .take(limit)
            }

        override suspend fun extras(
            itemId: ItemId,
            localTrailers: Boolean,
            specialFeatures: Boolean,
        ): List<FindroidMovie> =
            call("extras") {
                if (capabilities.extras) stored(itemId) ?: notFound()
                emptyList()
            }

        override suspend fun collectionsOf(itemId: ItemId): List<FindroidItem> =
            call("collectionsOf") {
                if (!capabilities.itemCollections) return@call emptyList()
                library.children
                    .filter { (id, children) -> itemId in children && stored(id) is FindroidBoxSet }
                    .keys
                    .mapNotNull(::stored)
                    .map(::current)
            }

        override suspend fun themeSongUrl(itemId: ItemId): String? =
            call("themeSongUrl") {
                if (capabilities.themeSongs) library.themeSongs[itemId] else null
            }
    }

    private inner class UserData : UserDataPort {
        override suspend fun setFavorite(itemId: ItemId, favorite: Boolean) {
            call("setFavorite") { mark(itemId) { it.copy(favorite = favorite) } }
        }

        override suspend fun setPlayed(itemId: ItemId, played: Boolean) {
            call("setPlayed") { mark(itemId) { it.copy(played = played, positionTicks = 0L) } }
        }
    }

    private fun mark(itemId: ItemId, change: (Marks) -> Marks) {
        stored(itemId) ?: notFound()
        synchronized(marks) { marks[itemId] = change(marks[itemId] ?: Marks(false, false, 0L)) }
    }

    private inner class Playback : PlaybackPort {
        override suspend fun sources(request: PlaybackRequest): List<FindroidSource> =
            call("sources") {
                val item = stored(request.itemId) ?: notFound()
                val target = request.target
                // As the adapter does for a local player: the source and the audio index go
                // together or not at all.
                val chosen =
                    if (target is PlaybackTarget.LocalPlayer) {
                        request.mediaSourceId.takeIf { request.audioStreamIndex != null }
                    } else request.mediaSourceId
                val audioIndex =
                    if (target is PlaybackTarget.LocalPlayer) {
                        request.audioStreamIndex.takeIf { request.mediaSourceId != null }
                    } else request.audioStreamIndex
                item.sources
                    .filter { chosen == null || it.id == chosen }
                    .map { source ->
                        val cap = request.maxBitrate
                        val transcoded =
                            cap != null && source.bitrate != null && source.bitrate!! > cap
                        val session =
                            if (transcoded && target !is PlaybackTarget.Download) {
                                UUID.randomUUID().toString().replace("-", "")
                            } else null
                        source.copy(
                            path =
                                when {
                                    transcoded ->
                                        "${library.address}/videos/${item.id}/master.m3u8" +
                                            "?MediaSourceId=${source.id}"
                                    request.includePath || target is PlaybackTarget.CastReceiver ->
                                        streamUrlOf(item.id, source.id)
                                    else -> ""
                                },
                            transcoded = transcoded,
                            playSessionId = session,
                            defaultAudioStreamIndex = audioIndex ?: source.defaultAudioStreamIndex,
                        )
                    }
            }

        override suspend fun streamUrl(itemId: ItemId, mediaSourceId: MediaSourceId): String =
            call("streamUrl") { streamUrlOf(itemId, mediaSourceId) }

        override suspend fun segments(itemId: ItemId): List<FindroidSegment> =
            call("segments") {
                stored(itemId) ?: notFound()
                if (capabilities.mediaSegments) library.segments[itemId].orEmpty() else emptyList()
            }

        override suspend fun trickplayTile(itemId: ItemId, width: Int, index: Int): ByteArray? =
            call("trickplayTile") {
                val item = stored(itemId) ?: notFound()
                val info = (item as? dev.jdtech.jellyfin.models.FindroidSources)?.trickplayInfo
                val has = info?.values?.any { it.width == width } == true
                if (capabilities.trickplay && has) TILE.copyOf() else null
            }

        override fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String? =
            answer("chapterImageUrl") {
                "${library.address}/Items/$itemId/Images/Chapter/$chapterIndex?tag=$tag"
            }

        override fun seasonBackdropUrl(seasonId: ItemId): String? =
            answer("seasonBackdropUrl") { "${library.address}/Items/$seasonId/Images/Backdrop" }

        override suspend fun reportStart(report: PlaybackReport) {
            call("reportStart") { reportLog += ReportKind.START to report }
        }

        override suspend fun reportProgress(report: PlaybackReport) {
            call("reportProgress") {
                reportLog += ReportKind.PROGRESS to report
                mark(report.itemId) { it.copy(positionTicks = report.positionTicks) }
            }
        }

        /** Applies the stop as a server does, with [ResumeThresholds.DEFAULT]. */
        override suspend fun reportStopped(report: PlaybackReport) {
            call("reportStopped") {
                reportLog += ReportKind.STOPPED to report
                val item = stored(report.itemId) ?: notFound()
                val stop =
                    stopUserData(
                        report.positionTicks,
                        playedPercentage = 0,
                        runtimeTicks = item.runtimeTicks,
                    )
                if (stop != null) {
                    mark(report.itemId) {
                        it.copy(
                            positionTicks = stop.positionTicks,
                            played = stop.played ?: it.played,
                        )
                    }
                }
            }
        }

        override suspend fun announceCapabilities() {
            call("announceCapabilities") {}
        }

        override suspend fun resumeThresholds(): ResumeThresholds? =
            call("resumeThresholds") {
                if (capabilities.serverResumeThresholds) ResumeThresholds.DEFAULT else null
            }
    }

    private fun streamUrlOf(itemId: ItemId, mediaSourceId: MediaSourceId) =
        "${library.address}/Videos/$itemId/stream?static=true&mediaSourceId=$mediaSourceId"

    private inner class Session : ServerSession {
        @Volatile override var baseUrl: String? = library.address
        @Volatile override var userId: UserId? = library.userId
        @Volatile override var accessToken: String? = TOKEN
        override val unauthorized = MutableSharedFlow<String>()
        override val serverReturned = MutableSharedFlow<Unit>()
        @Volatile override var requestWatcher: RequestWatcher? = null
        override val probeTimeoutMs: Long = 1_000L

        override fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
            answer("use") {
                this.baseUrl = baseUrl
                this.accessToken = accessToken
                this.userId = userId
            }
        }

        override suspend fun probe(): Boolean? =
            if (baseUrl == null) null
            else
                try {
                    call("probe") { true }
                } catch (_: ServerException) {
                    false
                }

        override suspend fun isTokenValid(): Boolean = call("isTokenValid") { accessToken != null }

        override fun keepAlive(): Flow<Nothing> = emptyFlow()

        override fun userImageUrl(userId: UserId, tag: String): String? = baseUrl?.let {
            "$it/Users/$userId/Images/Primary?tag=$tag"
        }

        override suspend fun updateDeviceName(name: String) {
            call("updateDeviceName") {}
        }

        override suspend fun autoPlayNextEpisode(): Boolean? =
            call("autoPlayNextEpisode") { if (capabilities.autoPlayNextSetting) true else null }

        override suspend fun currentUserImage(): UserImage =
            call("currentUserImage") { UserImage(userId ?: library.userId, tag = null) }
    }

    override val session: ServerSession = Session()
    override val catalog: CatalogPort = Catalog()
    override val userData: UserDataPort = UserData()
    override val playback: PlaybackPort = Playback()

    companion object {
        /** The access token of the fake's session. */
        const val TOKEN = "token"

        /** What a trickplay tile is: the start of a JPEG. */
        private val TILE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
