package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.domain.ports.CatalogPort
import dev.jdtech.jellyfin.domain.ports.CatalogPort.Companion.SEARCH_LIMIT
import dev.jdtech.jellyfin.domain.ports.CatalogPort.Companion.SEARCH_LIMIT_PER_KIND
import dev.jdtech.jellyfin.domain.ports.ItemQuery
import dev.jdtech.jellyfin.models.EpisodeField
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.ServerInfo
import dev.jdtech.jellyfin.repository.ItemsPage
import dev.jdtech.jellyfin.server.jellyfin.mappers.MapperContext
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidCollection
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidEpisode
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidItem
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidMovie
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidPerson
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidSeason
import dev.jdtech.jellyfin.server.jellyfin.mappers.toFindroidShow
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter as SdkItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder as SdkSortOrder
import timber.log.Timber

/**
 * [CatalogPort] over the Jellyfin SDK (#756). No Room: movies and episodes carry only the server's
 * sources, the repository adds the downloaded ones. Throws [ServerException] for every failure the
 * SDK reports as one (see [asServerException]); anything else leaves as it is.
 */
class JellyfinCatalog(private val api: JellyfinApi) : CatalogPort {
    private val currentUserId: UUID
        get() =
            api.userId
                ?: throw IllegalStateException(
                    "No authenticated user. userId is null — session may have expired."
                )

    private fun baseUrl() = api.api.baseUrl.orEmpty()

    /**
     * What the mappers need from the current server. Built at each mapping, never kept: the base
     * URL changes when the user switches server.
     */
    private fun mapperContext() =
        MapperContext(
            baseUrl = baseUrl(),
            streamUrl = { id, source ->
                try {
                    api.videoApi.getVideoStreamUrl(id, static = true, mediaSourceId = source)
                } catch (e: Exception) {
                    Timber.e(e)
                    ""
                }
            },
        )

    /**
     * On the IO dispatcher, with the SDK's failures translated. The block runs in a scope of its
     * own, so a failing child (search, extras) reaches the translation instead of failing the
     * dispatcher's scope with the SDK's exception.
     */
    private suspend fun <T> call(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope(block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.asServerException() ?: e
            }
        }

    override suspend fun serverInfo(): ServerInfo = call {
        val info = api.systemApi.getPublicSystemInfo().content
        ServerInfo(
            id = info.id.orEmpty(),
            name = info.serverName.orEmpty(),
            version = info.version,
            product = info.productName,
        )
    }

    override suspend fun libraries(): List<FindroidCollection> = call {
        api.libraryApi.getItems(currentUserId).content.items.mapNotNull {
            it.toFindroidCollection(baseUrl())
        }
    }

    override suspend fun userViews(): List<FindroidCollection> = call {
        api.userViewApi.getUserViews(currentUserId).content.items.mapNotNull {
            it.toFindroidCollection(baseUrl())
        }
    }

    override suspend fun item(id: ItemId): FindroidItem? = call {
        api.libraryApi
            .getItem(itemId = id, userId = currentUserId)
            .content
            .toFindroidItem(mapperContext(), emptyMap())
    }

    override suspend fun movie(id: ItemId): FindroidMovie = call {
        api.libraryApi
            .getItem(id, currentUserId)
            .content
            .toFindroidMovie(mapperContext(), emptyMap())
    }

    override suspend fun show(id: ItemId): FindroidShow = call {
        api.libraryApi.getItem(id, currentUserId).content.toFindroidShow(baseUrl())
    }

    override suspend fun season(id: ItemId): FindroidSeason = call {
        api.libraryApi.getItem(id, currentUserId).content.toFindroidSeason(baseUrl())
    }

    override suspend fun episode(id: ItemId): FindroidEpisode = call {
        api.libraryApi
            .getItem(id, currentUserId)
            .content
            .toFindroidEpisode(mapperContext(), emptyMap())!!
    }

    override suspend fun items(query: ItemQuery, includeTotal: Boolean): ItemsPage = call {
        val result =
            api.libraryApi
                .getItems(
                    currentUserId,
                    parentId = query.parentId,
                    includeItemTypes = query.kinds?.toSdk(),
                    recursive = query.recursive,
                    sortBy = listOf(ItemSortBy.fromName(query.sortBy.sortString)),
                    sortOrder = listOf(SdkSortOrder.fromName(query.sortOrder.sortString)),
                    filters = query.filters.toSdkFilters(),
                    startIndex = query.startIndex,
                    limit = query.limit,
                    // Cards read the primary image and the first backdrop. The server counts
                    // only for the first page of a pager (see getItemsPagingIndexed).
                    imageTypeLimit = 1,
                    enableImageTypes = ROW_IMAGE_TYPES,
                    enableTotalRecordCount = includeTotal,
                )
                .content
        ItemsPage(
            items = result.items.toFindroidItems(),
            totalRecordCount = if (includeTotal) result.totalRecordCount else null,
        )
    }

    override suspend fun itemCount(query: ItemQuery, nameLessThan: String?): Int = call {
        api.libraryApi
            .getItems(
                currentUserId,
                parentId = query.parentId,
                includeItemTypes = query.kinds?.toSdk(),
                recursive = query.recursive,
                filters = query.filters.toSdkFilters(),
                nameLessThan = nameLessThan,
                limit = 0,
                enableTotalRecordCount = true,
                enableImages = false,
                enableUserData = false,
            )
            .content
            .totalRecordCount
    }

    override suspend fun randomItemsWithBackdrop(
        kinds: List<ItemKind>,
        limit: Int,
    ): List<FindroidItem> = call {
        api.libraryApi
            .getItems(
                currentUserId,
                includeItemTypes = kinds.toSdk(),
                recursive = true,
                sortBy = listOf(ItemSortBy.RANDOM),
                imageTypes = listOf(ImageType.BACKDROP),
                limit = limit,
            )
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun person(id: ItemId): FindroidPerson = call {
        api.libraryApi.getItem(id, currentUserId).content.toFindroidPerson(baseUrl())
    }

    override suspend fun personItems(
        personIds: List<ItemId>,
        kinds: List<ItemKind>?,
        recursive: Boolean,
        limit: Int?,
    ): List<FindroidItem> = call {
        api.libraryApi
            .getItems(
                currentUserId,
                personIds = personIds,
                includeItemTypes = kinds?.toSdk(),
                recursive = recursive,
                limit = limit,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun favorites(): List<FindroidItem> = call {
        api.libraryApi
            .getItems(
                currentUserId,
                filters = listOf(SdkItemFilter.IS_FAVORITE),
                includeItemTypes =
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                recursive = true,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun search(query: String, kinds: List<ItemKind>?): List<FindroidItem> = call {
        if (kinds == null) {
            searchItems(query, listOf(ItemKind.MOVIE, ItemKind.SERIES), SEARCH_LIMIT)
        } else {
            kinds
                .map { kind -> async { searchItems(query, listOf(kind), SEARCH_LIMIT_PER_KIND) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun searchItems(
        query: String,
        itemKinds: List<ItemKind>,
        limit: Int,
    ): List<FindroidItem> =
        api.libraryApi
            .getItems(
                currentUserId,
                searchTerm = query,
                includeItemTypes = itemKinds.toSdk(),
                recursive = true,
                limit = limit,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()

    override suspend fun suggestions(): List<FindroidItem> = call {
        api.suggestionApi
            .getSuggestions(
                currentUserId,
                limit = 6,
                type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun resumeItems(): List<FindroidItem> = call {
        api.libraryApi
            .getResumeItems(
                currentUserId,
                limit = 12,
                mediaTypes = listOf(MediaType.VIDEO),
                includeItemTypes =
                    listOf(
                        BaseItemKind.MOVIE,
                        BaseItemKind.EPISODE,
                        BaseItemKind.VIDEO,
                        BaseItemKind.MUSIC_VIDEO,
                    ),
                // The TV hero shows it, and Jellyfin leaves it out of Resume, Latest and Next
                // Up unless asked (#410).
                fields = listOf(ItemFields.OVERVIEW),
                imageTypeLimit = 1,
                enableImageTypes = WIDE_ROW_IMAGE_TYPES,
                enableTotalRecordCount = false,
            )
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun latest(parentId: ItemId, kinds: List<ItemKind>?): List<FindroidItem> =
        call {
            api.libraryApi
                .getLatestMedia(
                    currentUserId,
                    parentId = parentId,
                    includeItemTypes = kinds?.toSdk(),
                    fields = listOf(ItemFields.OVERVIEW),
                    limit = 16,
                    imageTypeLimit = 1,
                    enableImageTypes = ROW_IMAGE_TYPES,
                )
                .content
                .toFindroidItems()
        }

    override suspend fun seasons(seriesId: ItemId): List<FindroidSeason> = call {
        api.showApi.getSeasons(seriesId, currentUserId).content.items.map {
            it.toFindroidSeason(baseUrl())
        }
    }

    override suspend fun nextUp(
        seriesId: ItemId?,
        dateCutoff: LocalDateTime?,
    ): List<FindroidEpisode> = call {
        api.showApi
            .getNextUp(
                currentUserId,
                limit = 24,
                seriesId = seriesId,
                fields = listOf(ItemFields.OVERVIEW),
                imageTypeLimit = 1,
                enableImageTypes = WIDE_ROW_IMAGE_TYPES,
                nextUpDateCutoff = dateCutoff,
                enableTotalRecordCount = false,
                enableResumable = false,
            )
            .content
            .items
            .mapNotNull { it.toFindroidEpisode(mapperContext(), emptyMap()) }
    }

    override suspend fun episodes(
        seriesId: ItemId,
        seasonId: ItemId?,
        fields: Set<EpisodeField>?,
        startItemId: ItemId?,
        limit: Int?,
    ): List<FindroidEpisode> = call {
        api.showApi
            .getEpisodes(
                seriesId,
                currentUserId,
                seasonId = seasonId,
                fields = fields?.map { it.toSdk() },
                startItemId = startItemId,
                limit = limit,
            )
            .content
            .items
            .mapNotNull { it.toFindroidEpisode(mapperContext(), emptyMap()) }
    }

    override suspend fun similar(itemId: ItemId, limit: Int): List<FindroidItem> = call {
        api.libraryApi
            .getSimilarItems(itemId, userId = currentUserId, limit = limit)
            .content
            .items
            .filter { it.id != itemId }
            .toFindroidItems()
    }

    override suspend fun extras(
        itemId: ItemId,
        localTrailers: Boolean,
        specialFeatures: Boolean,
    ): List<FindroidMovie> = call {
        val trailersAsync = async {
            if (localTrailers) {
                api.libraryApi.getLocalTrailers(itemId, currentUserId).content
            } else emptyList()
        }
        val featuresAsync = async {
            if (specialFeatures) {
                api.libraryApi.getSpecialFeatures(itemId, currentUserId).content
            } else emptyList()
        }
        (trailersAsync.await() + featuresAsync.await())
            .distinctBy { it.id }
            .map { it.toFindroidMovie(mapperContext(), emptyMap()) }
    }

    override suspend fun collectionsOf(itemId: ItemId): List<FindroidItem> = call {
        api.libraryApi
            .getItemCollections(itemId, userId = currentUserId)
            .content
            .items
            .toFindroidItems()
    }

    override suspend fun themeSongUrl(itemId: ItemId): String? = call {
        val song =
            api.libraryApi
                .getThemeSongs(itemId, currentUserId, inheritFromParent = true)
                .content
                .items
                .firstOrNull() ?: return@call null
        api.api.audioApi.getAudioStreamUrl(song.id, static = true)
    }

    private suspend fun List<BaseItemDto>.toFindroidItems(): List<FindroidItem> {
        val context = mapperContext()
        return mapNotNull { it.toFindroidItem(context, emptyMap()) }
    }

    private companion object {
        /**
         * The only images row cards (home rows, next up) render: `FindroidImages` reads the primary
         * tag, the first backdrop and the series primary tag. Together with `imageTypeLimit = 1`
         * this stops the server from resolving (and sending blurhashes for) every backdrop, logo,
         * thumb and banner of each item.
         */
        val ROW_IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP)

        /** CE (#608): Continue Watching and Next Up also show the thumb, the art with the title. */
        val WIDE_ROW_IMAGE_TYPES = ROW_IMAGE_TYPES + ImageType.THUMB
    }
}
