package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.domain.ports.MediaServer
import dev.jdtech.jellyfin.domain.ports.PlaybackReport
import dev.jdtech.jellyfin.domain.ports.PlaybackRequest
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
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.models.SeriesTrackPreferenceDto
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val server: MediaServer,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val syncRequester: UserDataSyncRequester = UserDataSyncRequester.NONE,
    private val knownServerState: KnownServerState = KnownServerState.NONE,
    private val storedLibraryNames: StoredLibraryNames = StoredLibraryNames.ENGLISH,
    /** CE (#637): the resume settings read from each server, kept for the stops made offline. */
    private val resumeThresholds: ResumeThresholdsStore =
        ResumeThresholdsStore(appPreferences.sharedPreferences),
) : JellyfinRepository {
    private val catalog: CatalogPort
        get() = server.catalog

    private val playSessions = PlaySessions()

    private val storedLists = StoredLists(database)

    /**
     * CE (#775): a list Room can answer: straight from Room while the server is known to be away,
     * else from Room only when the request gets no answer. Not after [STORED_FALLBACK_TIMEOUT_MS]
     * as an item is: a slow server would leave the shorter downloads-only list on a screen that
     * does not say so. A request left unanswered gets a probe, and the screens reload when that
     * says unreachable. CE (#779): the result says which of the two answered.
     */
    private suspend fun <T> serverOrStoredItems(
        stored: (suspend () -> List<T>)?,
        server: suspend () -> List<T>,
    ): ItemsResult<T> {
        if (stored != null && knownServerState.isKnownUnreachable()) {
            knownServerState.onServerSkipped()
            return ItemsResult(stored(), fromStore = true)
        }
        var fromStore = false
        val items =
            serverOrStoredWhenUnanswered(
                stored?.let { read ->
                    suspend {
                        fromStore = true
                        read()
                    }
                },
                server,
            )
        return ItemsResult(items, fromStore)
    }

    /** CE (#781): whether a download saved [image] of [itemId] (ImagesDownloaderWorker). */
    private fun hasStoredImage(itemId: UUID, image: String): Boolean =
        File(context.filesDir, "images/$itemId/$image").length() > 0

    /**
     * CE (#775): maps stored rows only when they are needed; null when there are none, so the
     * server's error comes through. With [emptyIsAnAnswer] no stored rows is an answer too: the
     * favourites can say "none downloaded", which an empty library or search could not.
     */
    private fun storedItems(
        serverId: String?,
        dtos: List<Any>,
        emptyIsAnAnswer: Boolean = false,
    ): (suspend () -> List<FindroidItem>)? {
        if (serverId == null) return null
        if (dtos.isEmpty()) return if (emptyIsAnAnswer) suspend { emptyList() } else null
        return suspend { storedLists.toItems(dtos, serverId, currentUserId) }
    }

    private fun currentServerId(): String? = appPreferences.getValue(appPreferences.currentServer)

    /**
     * CE (#637): reads the server's resume settings once per app run, so stops here and offline
     * follow them. A failure keeps the last ones read, or Jellyfin's defaults.
     */
    private suspend fun readResumeThresholds() {
        val serverId = currentServerId() ?: return
        if (resumeThresholds.isFresh(serverId)) return
        try {
            server.playback.resumeThresholds()?.let { resumeThresholds.put(serverId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not read the server's resume settings")
        }
    }

    private val currentUserId: UUID
        get() =
            server.session.userId
                ?: throw IllegalStateException(
                    "No authenticated user. userId is null — session may have expired."
                )

    override suspend fun getServerInfo(): ServerInfo = catalog.serverInfo()

    override suspend fun getUserViews(): List<FindroidCollection> = catalog.userViews()

    // CE (#281): the item getters fall back to the copy in Room (a download, or the show and season
    // of a downloaded episode) when the server fails or is slow, see serverOrStored. CE (#718):
    // only
    // a copy stored for the current server: another server can have the same item ids.
    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getEpisodeOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidEpisode(database, currentUserId) } }
            ) {
                catalog.episode(itemId).withLocalSources(localSourcesFor(listOf(itemId)))
            }
        }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getMovieOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidMovie(database, currentUserId) } }
            ) {
                catalog.movie(itemId).withLocalSources(localSourcesFor(listOf(itemId)))
            }
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getShowOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidShow(database, currentUserId) } }
            ) {
                catalog.show(itemId)
            }
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            val stored = currentServerId()?.let { database.getSeasonOfServer(itemId, it) }
            serverOrStored(
                stored?.let { dto -> suspend { dto.toFindroidSeason(database, currentUserId) } }
            ) {
                catalog.season(itemId)
            }
        }

    override suspend fun getLibraries(): ItemsResult<FindroidCollection> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, a Movies and a Shows library of what is downloaded.
            // CE (#781): built only when Room answers, as they look for their artwork.
            val serverId = currentServerId()?.takeIf { storedLists.hasDownloads(it) }
            val stored = serverId?.let { id ->
                suspend { storedLists.libraries(id, storedLibraryNames, ::hasStoredImage) }
            }
            serverOrStoredItems(stored) { catalog.libraries() }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) { serverOrStored(storedItem(itemId)) { catalog.item(itemId) } }

    /**
     * CE (#281): builds [itemId] from Room, or null when Room doesn't have it for the current
     * server (#718).
     */
    private suspend fun storedItem(itemId: UUID): (suspend () -> FindroidItem)? {
        val serverId = currentServerId() ?: return null
        database.getMovieOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidMovie(database, currentUserId) }
        }
        database.getEpisodeOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidEpisode(database, currentUserId) }
        }
        database.getShowOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidShow(database, currentUserId) }
        }
        database.getSeasonOfServer(itemId, serverId)?.let {
            return suspend { it.toFindroidSeason(database, currentUserId) }
        }
        return null
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> =
        getItemsPage(
                parentId,
                includeTypes,
                recursive,
                sortBy,
                sortOrder,
                filters,
                startIndex,
                limit,
                includeTotal = false,
            )
            .items

    override suspend fun getItemsPage(
        parentId: UUID?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
        includeTotal: Boolean,
    ): ItemsPage =
        withContext(Dispatchers.IO) {
            // CE (#775): both pagers and the plain list come through here.
            if (StoredLibraries.isStored(parentId)) {
                return@withContext storedLibraryPage(
                    parentId!!,
                    sortBy,
                    sortOrder,
                    filters,
                    startIndex,
                    limit,
                    includeTotal,
                )
            }
            val page =
                catalog.items(
                    ItemQuery(
                        parentId = parentId,
                        kinds = includeTypes,
                        recursive = recursive,
                        sortBy = sortBy,
                        sortOrder = sortOrder,
                        filters = filters,
                        startIndex = startIndex,
                        limit = limit,
                    ),
                    includeTotal = includeTotal,
                )
            page.copy(items = page.items.withLocalSources())
        }

    /**
     * CE (#775): a page of one of [StoredLibraries], always from Room: the server does not know
     * these ids. Only the page is mapped to items.
     */
    private suspend fun storedLibraryPage(
        libraryId: UUID,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
        startIndex: Int?,
        limit: Int?,
        includeTotal: Boolean,
    ): ItemsPage {
        val serverId = currentServerId() ?: return ItemsPage(emptyList(), 0)
        val userId = currentUserId
        val all =
            storedLists.libraryContents(serverId, userId, libraryId, sortBy, sortOrder, filters)
        val page = all.drop(startIndex ?: 0).let { if (limit != null) it.take(limit) else it }
        return ItemsPage(
            items = storedLists.toItems(page, serverId, userId),
            totalRecordCount = if (includeTotal) all.size else null,
        )
    }

    override suspend fun getRandomItemsWithBackdrop(
        includeTypes: List<ItemKind>,
        limit: Int,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            catalog.randomItemsWithBackdrop(includeTypes, limit).withLocalSources()
        }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: Set<ItemFilter>,
    ): Flow<PagingData<FindroidItem>> {
        return Pager(
                config =
                    PagingConfig(
                        pageSize = LIBRARY_PAGE_SIZE,
                        prefetchDistance = LIBRARY_PAGE_SIZE,
                        initialLoadSize = LIBRARY_PAGE_SIZE * 2,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemsPagingSource(
                        this,
                        parentId,
                        includeTypes,
                        recursive,
                        sortBy,
                        sortOrder,
                        filters,
                    )
                },
            )
            .flow
    }

    override suspend fun getItemCount(
        parentId: UUID?,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        filters: Set<ItemFilter>,
        nameLessThan: String?,
    ): Int =
        withContext(Dispatchers.IO) {
            // CE (#775): the A–Z bar of a stored library counts what its grid holds.
            if (StoredLibraries.isStored(parentId)) {
                val serverId = currentServerId() ?: return@withContext 0
                return@withContext storedLists.libraryCount(
                    serverId,
                    currentUserId,
                    parentId!!,
                    filters,
                    nameLessThan,
                )
            }
            catalog.itemCount(
                ItemQuery(
                    parentId = parentId,
                    kinds = includeTypes,
                    recursive = recursive,
                    filters = filters,
                ),
                nameLessThan = nameLessThan,
            )
        }

    override suspend fun getPerson(personId: UUID): FindroidPerson = catalog.person(personId)

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            catalog.personItems(personIds, includeTypes, recursive, limit).withLocalSources()
        }

    override suspend fun getFavoriteItems(): ItemsResult<FindroidItem> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, the downloaded favourites, also when there are none
            // but something else is downloaded: the screen says that none are downloaded. With
            // nothing downloaded at all (TV, a phone without downloads) the server's error comes
            // through, as before.
            val serverId = currentServerId()
            val stored = serverId?.let { storedLists.favorites(it, currentUserId) }.orEmpty()
            val noneIsAnAnswer =
                serverId != null && stored.isEmpty() && storedLists.hasDownloads(serverId)
            serverOrStoredItems(storedItems(serverId, stored, emptyIsAnAnswer = noneIsAnAnswer)) {
                catalog.favorites().withLocalSources()
            }
        }

    override suspend fun getSearchItems(
        query: String,
        itemKinds: List<ItemKind>?,
    ): ItemsResult<FindroidItem> =
        withContext(Dispatchers.IO) {
            // CE (#775): without the server, the downloads by name.
            val serverId = currentServerId()
            val stored =
                serverId
                    ?.let {
                        storedLists.search(
                            it,
                            query,
                            itemKinds,
                            if (itemKinds == null) CatalogPort.SEARCH_LIMIT
                            else CatalogPort.SEARCH_LIMIT_PER_KIND,
                        )
                    }
                    .orEmpty()
            serverOrStoredItems(storedItems(serverId, stored)) {
                catalog.search(query, itemKinds).withLocalSources()
            }
        }

    override suspend fun getSuggestions(): List<FindroidItem> =
        withContext(Dispatchers.IO) { catalog.suggestions().withLocalSources() }

    override suspend fun getResumeItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) { catalog.resumeItems().withLocalSources() }

    override suspend fun getLatestMedia(
        parentId: UUID,
        includeItemTypes: List<ItemKind>?,
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            catalog.latest(parentId, includeItemTypes).withLocalSources()
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                catalog.seasons(seriesId)
            } else {
                // CE (#718): the current server's stored seasons only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                database.getSeasonsByShowIdOfServer(seriesId, serverId).map {
                    it.toFindroidSeason(database, currentUserId)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            catalog.nextUp(
                seriesId,
                dateCutoff =
                    nextUpDateCutoff(
                        seriesId = seriesId,
                        maxDays = appPreferences.getValue(appPreferences.homeNextUpMaxDays),
                        now = LocalDateTime.now(),
                    ),
            )
        }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID?,
        fields: Set<EpisodeField>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                val episodes = catalog.episodes(seriesId, seasonId, fields, startItemId, limit)
                val local = localSourcesFor(episodes.map { it.id })
                episodes.map { it.withLocalSources(local) }
            } else {
                // CE (#718): the current server's stored episodes only.
                val serverId = currentServerId() ?: return@withContext emptyList()
                val episodes =
                    if (seasonId != null) database.getEpisodesBySeasonIdOfServer(seasonId, serverId)
                    else database.getEpisodesByShowIdOfServer(seriesId, serverId)
                episodes.map { it.toFindroidEpisode(database, currentUserId) }
            }
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        transcodeDolbyVision: Boolean,
        maxBitrate: Int?,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            // The downloader's path: the original file, or a transcode for Dolby Vision or a
            // download quality cap (JellyfinPlayback).
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                server.playback.sources(
                    PlaybackRequest(
                        itemId = itemId,
                        target = PlaybackTarget.Download(transcodeDolbyVision),
                        maxBitrate = maxBitrate,
                        includePath = includePath,
                    )
                )
            )
            // CE (#718): the current server's download of the item, not another server's.
            currentServerId()?.let { serverId ->
                sources.addAll(
                    database.getSourcesOfServer(itemId, serverId).map {
                        it.toFindroidSource(database)
                    }
                )
            }
            sources
        }

    override suspend fun getPlaybackSources(
        itemId: UUID,
        mediaSourceId: String?,
        audioStreamIndex: Int?,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            // A downloaded copy wins over any server source (see PlaylistManager), so do not wait
            // for the server when it is the one that will play. Only a finished one: an unfinished
            // download's partial file would play as far as it got, or not at all (#454). CE (#718):
            // and only a download stored for the current server.
            val localSources =
                currentServerId()
                    ?.let { database.getCompletedSources(itemId, it) }
                    .orEmpty()
                    .map { it.toFindroidSource(database) }
            if (
                localSources.isNotEmpty() &&
                    (mediaSourceId == null || localSources.any { it.id == mediaSourceId })
            ) {
                return@withContext localSources
            }

            // User-selected bitrate cap (player quality selector). 0 = original quality.
            val maxBitrate =
                appPreferences.getValue(appPreferences.playerMaxBitrate).takeIf { it > 0 }
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                server.playback.sources(
                    PlaybackRequest(
                        itemId = itemId,
                        target = localPlayerTarget(),
                        mediaSourceId = mediaSourceId,
                        audioStreamIndex = audioStreamIndex,
                        maxBitrate = maxBitrate,
                        includePath = true,
                    )
                )
            )
            sources.addAll(localSources)
            sources
        }

    /**
     * CE (#752): who plays a stream of [getPlaybackSources], from the player preferences. Any
     * backend value other than mpv's plays on ExoPlayer, as before. #766 moves this to the player.
     */
    private fun localPlayerTarget(): PlaybackTarget.LocalPlayer =
        PlaybackTarget.LocalPlayer(
            engine =
                PlayerEngineId.fromPreference(appPreferences.getValue(appPreferences.playerBackend))
                    ?: PlayerEngineId.EXOPLAYER,
            directPlayDolbyVision =
                appPreferences.getValue(appPreferences.playerDirectPlayDolbyVision),
        )

    override suspend fun getSeriesTrackPreferences(seriesId: UUID): List<SeriesTrackPreferenceDto> =
        withContext(Dispatchers.IO) { database.getSeriesTrackPreferences(currentUserId, seriesId) }

    override suspend fun saveSeriesTrackPreference(preference: SeriesTrackPreferenceDto) =
        withContext(Dispatchers.IO) {
            database.insertSeriesTrackPreference(preference.copy(userId = currentUserId))
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                server.playback.streamUrl(itemId, mediaSourceId)
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toFindroidSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            getServerSegments(itemId)
        }

    override suspend fun getServerSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            try {
                return@withContext server.playback.segments(itemId)
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read local trickplay data")
                }

                return@withContext server.playback.trickplayTile(itemId, width, index)
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun getThemeSongUrl(itemId: UUID): String? =
        withContext(Dispatchers.IO) {
            try {
                catalog.themeSongUrl(itemId)
            } catch (e: Exception) {
                Timber.e(e)
                null
            }
        }

    override suspend fun getSimilarItems(itemId: UUID, limit: Int): List<FindroidItem> =
        withContext(Dispatchers.IO) { catalog.similar(itemId, limit).withLocalSources() }

    override suspend fun getExtras(
        itemId: UUID,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<FindroidMovie> = catalog.extras(itemId, localTrailers, specialFeatures)

    override suspend fun getItemCollections(itemId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) { catalog.collectionsOf(itemId).withLocalSources() }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        server.playback.announceCapabilities()
    }

    override fun setPlaySession(itemId: UUID, playSessionId: String?, transcodes: Boolean) =
        playSessions.onPlaybackInfo(itemId, playSessionId, transcodes)

    override suspend fun postPlaybackStart(itemId: UUID, transcoded: Boolean) {
        // CE (#599): Transcode with the transcode's play session, see PlaySessions.playReport.
        val report = playSessions.playReport(itemId, transcoded)
        Timber.d("Sending start $itemId, transcoded: ${report.transcoded}")
        withContext(Dispatchers.IO) {
            // CE (#637): read alongside the start report, not before it, so a slow or failing read
            // does not hold up the start. The player waits for this call before it reports the
            // stop (up to 5 s), so the stop of this item still has them.
            coroutineScope {
                launch { readResumeThresholds() }
                server.playback.reportStart(
                    PlaybackReport(
                        itemId = itemId,
                        // The start sends no position.
                        positionTicks = 0L,
                        isPaused = false,
                        transcoded = report.transcoded,
                        playSessionId = report.playSessionId,
                    )
                )
            }
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int?,
    ) {
        Timber.d("Sending stop $itemId")
        withContext(Dispatchers.IO) {
            // CE (#637): the server applies its resume settings to this stop; the local copy
            // follows them.
            val userData =
                stopUserData(
                    positionTicks,
                    playedPercentage,
                    runtimeTicks = database.getRuntimeTicks(itemId),
                    thresholds = resumeThresholds.get(currentServerId()),
                )
            val stop = suspend {
                server.playback.reportStopped(
                    PlaybackReport(
                        itemId = itemId,
                        positionTicks = positionTicks,
                        isPaused = false,
                        transcoded = false,
                        // With it the server ends the item's transcode now, not a minute later.
                        playSessionId = playSessions.sessionOf(itemId),
                    )
                )
            }
            if (userData == null) {
                // Nothing local changes without user data, so there is nothing to sync later.
                try {
                    stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                return@withContext
            }
            reportUserData(itemId, report = stop) { userId ->
                setPlaybackPositionTicks(itemId, userId, userData.positionTicks)
                userData.played?.let { setPlayed(userId, itemId, it) }
                // CE (#633): sent along if this stop has to be synced later.
                setLastPlayedDate(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
        transcoded: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        val report = playSessions.playReport(itemId, transcoded)
        withContext(Dispatchers.IO) {
            reportUserData(
                itemId,
                report = {
                    server.playback.reportProgress(
                        PlaybackReport(
                            itemId = itemId,
                            positionTicks = positionTicks,
                            isPaused = isPaused,
                            transcoded = report.transcoded,
                            playSessionId = report.playSessionId,
                        )
                    )
                },
            ) { userId ->
                setPlaybackPositionTicks(itemId, userId, positionTicks)
                // CE (#633): sent along if this progress has to be synced later.
                setLastPlayedDate(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setFavorite(itemId, true) }) { userId
                ->
                setFavorite(userId, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setFavorite(itemId, false) }) { userId
                ->
                setFavorite(userId, itemId, false)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            reportUserData(itemId, report = { server.userData.setPlayed(itemId, true) }) { userId ->
                setPlayed(userId, itemId, true)
                // CE (#633): as the server does for a played mark without a date.
                setLastPlayedDateIfUnknown(userId, itemId, System.currentTimeMillis())
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            // CE (#635): the time of the mark, taken before the request: a request that fails only
            // after a timeout must not make the mark look later than a play elsewhere meanwhile.
            val markedAt = System.currentTimeMillis()
            val reached =
                reportUserData(itemId, report = { server.userData.setPlayed(itemId, false) }) {
                    userId ->
                    setPlayed(userId, itemId, false)
                    // CE (#635): kept until the sync sends the mark, which cannot clear the
                    // server's; with it the sync sees the mark is not older than the play it
                    // undoes. A row without one gets the time of the mark, or it would always count
                    // as older.
                    setLastPlayedDateIfUnknown(userId, itemId, markedAt)
                }
            // CE (#633): the server clears it for an unplayed mark too.
            if (reached) database.setLastPlayedDate(currentUserId, itemId, null)
        }
    }

    /** See the top-level [writeAndReport]: the change is written and marked before [report]. */
    private suspend fun reportUserData(
        itemId: UUID,
        report: suspend () -> Unit,
        write: suspend ServerDatabaseDao.(userId: UUID) -> Unit,
    ): Boolean {
        val userId = currentUserId
        return writeAndReport(database, syncRequester, userId, itemId, { write(userId) }, report)
    }

    override fun getBaseUrl() = server.session.baseUrl.orEmpty()

    override fun chapterImageUrl(itemId: UUID, chapterIndex: Int, tag: String) =
        server.playback.chapterImageUrl(itemId, chapterIndex, tag)

    override fun seasonBackdropUrl(seasonId: UUID) = server.playback.seasonBackdropUrl(seasonId)

    override suspend fun updateDeviceName(name: String) = server.session.updateDeviceName(name)

    override suspend fun refreshCurrentUserImage() {
        // Taken before the request: a switch of user while it waits must not give the tag to the
        // next user's row.
        val userId = currentUserId
        val tag = server.session.currentUserImageTag()
        withContext(Dispatchers.IO) { database.updateUserPrimaryImageTag(userId, tag) }
    }

    override suspend fun autoPlayNextEpisode(): Boolean? = server.session.autoPlayNextEpisode()

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val serverId =
                appPreferences.getValue(appPreferences.currentServer)
                    ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database.getMoviesByServerId(serverId).map {
                    it.toFindroidMovie(database, currentUserId)
                }
            )
            items.addAll(
                database.getShowsByServerId(serverId).map {
                    it.toFindroidShow(database, currentUserId)
                }
            )
            items
        }

    override fun getUserId(): UUID {
        return currentUserId
    }

    /**
     * Whether any source (download) is stored at all. Kept up to date by a Room observer, so a
     * download that starts, finishes or is deleted while the app runs flips it. Null until the
     * first value arrives.
     */
    private val hasLocalSources: StateFlow<Boolean?> =
        database
            .observeHasSources()
            .map<Boolean, Boolean?> { it }
            .catch { Timber.w(it, "Couldn't observe the sources table") }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, null)

    /**
     * The downloaded sources of [itemIds], or nothing without a query when the app has no sources
     * at all (the usual case for a user who doesn't download). CE (#718): only the current server's
     * downloads.
     */
    private suspend fun localSourcesFor(itemIds: List<UUID>): Map<UUID, List<FindroidSource>> {
        val serverId = currentServerId() ?: return emptyMap()
        val any = hasLocalSources.value ?: database.hasSources()
        return if (any) database.getLocalSourcesForItems(itemIds, serverId) else emptyMap()
    }

    /**
     * CE (#756): adds the downloaded sources of the movies and episodes the catalog gave, with one
     * batched lookup for the whole list instead of one query per item.
     */
    private suspend fun List<FindroidItem>.withLocalSources(): List<FindroidItem> {
        val local =
            localSourcesFor(filter { it is FindroidMovie || it is FindroidEpisode }.map { it.id })
        if (local.isEmpty()) return this
        return map { item ->
            when (item) {
                is FindroidMovie -> item.withLocalSources(local)
                is FindroidEpisode -> item.withLocalSources(local)
                else -> item
            }
        }
    }

    private companion object {
        /** Library grid page; a fling on a 2-4 column grid outruns anything smaller. */
        private const val LIBRARY_PAGE_SIZE = 40
    }
}

/**
 * CE (#756): the movie with its downloads of [local] after the server's sources, the order the
 * mappers kept before the catalog moved out (the player picks the local one, not the first).
 */
private fun FindroidMovie.withLocalSources(local: Map<UUID, List<FindroidSource>>): FindroidMovie =
    local[id]?.let { copy(sources = sources + it) } ?: this

/** CE (#756): as for a movie, the server's sources first, then the downloads of [local]. */
private fun FindroidEpisode.withLocalSources(
    local: Map<UUID, List<FindroidSource>>
): FindroidEpisode = local[id]?.let { copy(sources = sources + it) } ?: this

/**
 * The Home row only shows series watched in the last [maxDays] days, as the web client's "Max days
 * in Next Up" does. 0 is no limit. A single series has no cutoff: a show last watched years ago
 * still has a next episode.
 */
internal fun nextUpDateCutoff(seriesId: UUID?, maxDays: Int, now: LocalDateTime): LocalDateTime? =
    if (seriesId == null && maxDays > 0) now.minusDays(maxDays.toLong()) else null
