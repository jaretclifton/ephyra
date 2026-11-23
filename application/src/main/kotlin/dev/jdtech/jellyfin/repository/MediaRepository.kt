package dev.jdtech.jellyfin.repository

import androidx.paging.PagingData
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.Item
import dev.jdtech.jellyfin.models.ItemFilter
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ItemSource
import dev.jdtech.jellyfin.models.Library
import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.Season
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SeriesTrackPreference
import dev.jdtech.jellyfin.models.ServerCapabilities
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.models.Show
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    /**
     * #769: what the server in use can do. It changes with the server: read it when a screen loads.
     */
    val capabilities: ServerCapabilities

    suspend fun getServerInfo(): ServerInfo

    /** The libraries the current user sees, in the server's order, of the supported types only. */
    suspend fun getUserViews(): List<Library>

    /** Stores the current user's profile picture tag, as the server has it now. */
    suspend fun refreshCurrentUserImage()

    suspend fun getEpisode(itemId: ItemId): Episode

    suspend fun getMovie(itemId: ItemId): Movie

    suspend fun getShow(itemId: ItemId): Show

    suspend fun getSeason(itemId: ItemId): Season

    /** CE (#779): with where the list came from: Room answers it while the server is away. */
    suspend fun getLibraries(): ItemsResult<Library>

    suspend fun getItem(itemId: ItemId): Item?

    suspend fun getItems(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
        startIndex: Int? = null,
        limit: Int? = null,
    ): List<Item>

    /**
     * CE: like [getItems], but with the number of items that match in total (ignoring [startIndex]
     * and [limit]) when [includeTotal] is set, so a pager gets its length from its first page
     * instead of a separate [getItemCount]. The server counts only when asked, so leave
     * [includeTotal] off for later pages.
     */
    suspend fun getItemsPage(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
        startIndex: Int? = null,
        limit: Int? = null,
        includeTotal: Boolean = false,
    ): ItemsPage

    /**
     * CE (#140): up to [limit] items of [includeTypes] from all libraries, in random order, that
     * have a backdrop. Online the server picks them; the default (offline) shuffles local items.
     */
    suspend fun getRandomItemsWithBackdrop(includeTypes: List<ItemKind>, limit: Int): List<Item> =
        getItems(includeTypes = includeTypes, recursive = true)
            .filter { it.images.backdrop != null }
            .shuffled()
            .take(limit)

    suspend fun getItemsPaging(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: Set<ItemFilter> = emptySet(),
    ): Flow<PagingData<Item>>

    /**
     * CE: the number of items that [getItems] would return, without fetching them. With
     * [nameLessThan], only those whose sort name comes before it.
     */
    suspend fun getItemCount(
        parentId: ItemId? = null,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = false,
        filters: Set<ItemFilter> = emptySet(),
        nameLessThan: String? = null,
    ): Int

    suspend fun getPerson(personId: ItemId): Person

    suspend fun getPersonItems(
        personIds: List<ItemId>,
        includeTypes: List<ItemKind>? = null,
        recursive: Boolean = true,
        limit: Int? = null,
    ): List<Item>

    /** CE (#779): with where the list came from: Room answers it while the server is away. */
    suspend fun getFavoriteItems(): ItemsResult<Item>

    /**
     * Searches movies and shows. With [itemKinds], it searches those kinds instead, with a separate
     * limit per kind so one kind (episodes) can't push the others out (CE, TV search). CE (#779):
     * with where the list came from, as [getFavoriteItems].
     */
    suspend fun getSearchItems(query: String, itemKinds: List<ItemKind>? = null): ItemsResult<Item>

    suspend fun getSuggestions(): List<Item>

    suspend fun getResumeItems(): List<Item>

    /** [includeItemTypes] null leaves the choice to the server. */
    suspend fun getLatestMedia(
        parentId: ItemId,
        includeItemTypes: List<ItemKind>? = null,
    ): List<Item>

    suspend fun getSeasons(seriesId: ItemId, offline: Boolean = false): List<Season>

    suspend fun getNextUp(seriesId: ItemId? = null): List<Episode>

    /** The episodes of one season, or with a null [seasonId] (CE, #252) of the whole series. */
    suspend fun getEpisodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>? = null,
        startItemId: ItemId? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<Episode>

    /**
     * Resolves the sources for downloading an item. Always asks for the original file, except —
     * when [transcodeDolbyVision] is set — Dolby Vision files, which the server then transcodes to
     * a device-compatible H.264 copy so they play offline. Every non-DV file is still the original
     * regardless of the flag.
     *
     * [maxBitrate] (bits/sec) is the user-selected download quality cap. Files whose bitrate
     * exceeds it get a progressive server-side transcode down to the cap; files under it still
     * download as the original. Null = original quality for everything.
     */
    suspend fun getMediaSources(
        itemId: ItemId,
        includePath: Boolean = false,
        transcodeDolbyVision: Boolean = false,
        maxBitrate: Int? = null,
    ): List<ItemSource>

    /**
     * Resolves the sources to play an item. Unlike [getMediaSources] (which always asks for the
     * original file and is shared with the downloader), this sends an honest device profile so the
     * server transcodes anything the player cannot direct-play — notably Dolby Vision on hardware
     * without a working DV pipeline.
     *
     * When a downloaded copy exists and [mediaSourceId] is null or names it, the server is not
     * asked: the player plays the local copy then anyway, and waiting on the server only delays it.
     *
     * CE (#580): [audioStreamIndex] names the audio stream of [mediaSourceId] that a transcode has
     * to carry, as a transcode carries one. The server only takes it together with the source's id.
     * It means nothing for a source that plays directly or from a download.
     *
     * CE (#766): [target] is who plays the stream and [maxBitrate] the cap in bits per second (null
     * is none). The caller decides both; the repository reads no player preference.
     */
    suspend fun getPlaybackSources(
        itemId: ItemId,
        target: PlaybackTarget,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        maxBitrate: Int? = null,
    ): List<ItemSource>

    /** CE (#580): the tracks the user last picked by hand in [seriesId], one per track type. */
    suspend fun getSeriesTrackPreferences(seriesId: ItemId): List<SeriesTrackPreference>

    /**
     * Remembers [preference] as the current user's choice for its series and track type, replacing
     * the one before. The current user is written whatever [preference] says (#750).
     */
    suspend fun saveSeriesTrackPreference(preference: SeriesTrackPreference)

    suspend fun getStreamUrl(itemId: ItemId, mediaSourceId: String): String

    suspend fun getSegments(itemId: ItemId): List<Segment>

    /**
     * CE (#693): the item's segments as the server has them now, not the ones stored with a
     * download. A new download saves these.
     */
    suspend fun getServerSegments(itemId: ItemId): List<Segment> = getSegments(itemId)

    /**
     * Sheet [index] of the trickplay images [width] wide of [itemId]. CE (#860): [mediaSourceId] is
     * the version that is played, whose sheets these are, from a download or the server; null keeps
     * what it did before: any downloaded version's sheets, else the server's for the item.
     */
    suspend fun getTrickplayData(
        itemId: ItemId,
        width: Int,
        index: Int,
        mediaSourceId: String? = null,
    ): ByteArray?

    /**
     * CE (#141): a stream URL for the first theme song of [itemId] (a series), or null when the
     * server has none. Offline there is no theme music, so the default is null.
     */
    suspend fun getThemeSongUrl(itemId: ItemId): String? = null

    /**
     * CE (#332): up to [limit] items like [itemId], for "More like this". The detail pages load it
     * after they are shown. Offline there is nothing to compare with, so the default is empty.
     */
    suspend fun getSimilarItems(itemId: ItemId, limit: Int = SIMILAR_ITEMS_LIMIT): List<Item> =
        emptyList()

    /**
     * CE (#332): the extras of [itemId] — its local trailers, then its special features (behind the
     * scenes, deleted scenes, featurettes…) — as movies the player can play. Each kind is one
     * request, made only when asked for. Online only; the default is empty.
     */
    suspend fun getExtras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<Movie> = emptyList()

    /**
     * CE (#332): the collections (box sets) that contain [itemId], as jellyfin-web's "Included in"
     * finds them (`/Items/{id}/Collections`, Jellyfin 12 and later). Online only; the default is
     * empty.
     */
    suspend fun getItemCollections(itemId: ItemId): List<Item> = emptyList()

    suspend fun postCapabilities()

    /**
     * Records the play session of the stream that plays [itemId] now, which the start, progress and
     * stop reports of [itemId] name: it is the one whose transcode the stop report ends.
     * [transcodes] is whether that stream is the server's transcode.
     *
     * CE (#626): Cast asks with the receiver's device profile, not the phone's. CE (#728): the
     * local player calls this for each stream it loads. [getPlaybackSources] records nothing: a
     * quality or audio switch that fails after asking for the playing item's new stream keeps the
     * old stream, whose session the reports must keep naming.
     */
    fun setPlaySession(itemId: ItemId, playSessionId: String?, transcodes: Boolean) {}

    /**
     * Report that [itemId] started playing at [positionTicks], the player's position when the start
     * is sent (#868); `null` sends no position. [transcoded] says whether the stream playing is the
     * server's transcode of it (`PlayerItem.isTranscoded`), which the server shows as the play
     * method.
     */
    suspend fun postPlaybackStart(itemId: ItemId, positionTicks: Long?, transcoded: Boolean)

    /**
     * Report that [itemId] stopped playing at [positionTicks]. What the local database stores
     * follows the server's rules (see [stopUserData]): from the stored runtime of a downloaded item
     * and the server's resume settings, or from [playedPercentage] when no runtime is stored.
     * `null`, for an item left before its duration was known, leaves the local database as it is
     * and only tells the server.
     */
    suspend fun postPlaybackStop(itemId: ItemId, positionTicks: Long, playedPercentage: Int?)

    /** Report the position of [itemId]; [transcoded] as for [postPlaybackStart]. */
    suspend fun postPlaybackProgress(
        itemId: ItemId,
        positionTicks: Long,
        isPaused: Boolean,
        transcoded: Boolean,
    )

    suspend fun markAsFavorite(itemId: ItemId)

    suspend fun unmarkAsFavorite(itemId: ItemId)

    suspend fun markAsPlayed(itemId: ItemId)

    suspend fun markAsUnplayed(itemId: ItemId)

    fun getBaseUrl(): String

    /** The URL of the thumbnail the server extracted for a chapter. Null when no server is set. */
    fun chapterImageUrl(itemId: ItemId, chapterIndex: Int, tag: String): String?

    /**
     * The backdrop of [seasonId], whether or not the season has one of its own; the server answers
     * 404 when it has none. Null when no server is set.
     */
    fun seasonBackdropUrl(seasonId: ItemId): String?

    suspend fun updateDeviceName(name: String)

    /**
     * The user's server-side "play next episode automatically" setting, or null when the server has
     * none.
     */
    suspend fun autoPlayNextEpisode(): Boolean?

    suspend fun getDownloads(): List<Item>

    fun getUserId(): UserId
}

/** CE (#332): how many items a "More like this" row shows. */
const val SIMILAR_ITEMS_LIMIT = 12
