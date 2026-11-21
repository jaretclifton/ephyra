package dev.jdtech.jellyfin.server.jellyfin.mappers

import dev.jdtech.jellyfin.models.FindroidExternalLink
import dev.jdtech.jellyfin.models.FindroidTrailer
import java.time.DayOfWeek
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DayOfWeek as JellyfinDayOfWeek
import org.jellyfin.sdk.model.api.ExternalUrl
import org.jellyfin.sdk.model.api.MediaUrl

// CE (#332): the metadata the server already sends in the item response and the detail pages show.

/** All remote trailers that have a URL, in the server's order, without duplicates. */
internal fun List<MediaUrl>?.toFindroidTrailers(): List<FindroidTrailer> =
    orEmpty()
        .mapNotNull { trailer ->
            trailer.url
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    FindroidTrailer(name = trailer.name?.takeIf { n -> n.isNotBlank() }, url = it)
                }
        }
        .distinctBy { it.url }

/**
 * The item's outside links. The server builds [externalUrls] from its metadata providers, so they
 * are used as they come. A server that leaves them out still sends the provider ids, so the common
 * sites are built from those instead.
 */
internal fun externalLinks(
    externalUrls: List<ExternalUrl>?,
    providerIds: Map<String, String?>?,
    type: BaseItemKind?,
): List<FindroidExternalLink> {
    val fromServer =
        externalUrls.orEmpty().mapNotNull { link ->
            val name = link.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = link.url?.takeIf { it.isWebUrl() } ?: return@mapNotNull null
            FindroidExternalLink(name, url)
        }
    if (fromServer.isNotEmpty()) return fromServer.distinctBy { it.url }

    val ids = providerIds.orEmpty().mapKeys { it.key.lowercase() }
    return buildList {
            ids["imdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { add(FindroidExternalLink("IMDb", "https://www.imdb.com/title/$it")) }
            ids["tmdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    when (type) {
                        BaseItemKind.MOVIE ->
                            add(
                                FindroidExternalLink(
                                    "TheMovieDb",
                                    "https://www.themoviedb.org/movie/$id",
                                )
                            )
                        BaseItemKind.SERIES ->
                            add(
                                FindroidExternalLink(
                                    "TheMovieDb",
                                    "https://www.themoviedb.org/tv/$id",
                                )
                            )
                        else -> Unit
                    }
                }
            ids["tvdb"]
                ?.takeIf { it.isNotBlank() }
                ?.let { id ->
                    if (type == BaseItemKind.SERIES) {
                        add(
                            FindroidExternalLink(
                                "TheTVDB",
                                "https://thetvdb.com/?tab=series&id=$id",
                            )
                        )
                    }
                }
        }
        .distinctBy { it.url }
}

internal fun BaseItemDto.toExternalLinks(): List<FindroidExternalLink> =
    externalLinks(externalUrls, providerIds, type)

/** The studio (movie) or network (show) names, in the server's order. */
internal fun BaseItemDto.studioNames(): List<String> =
    studios.orEmpty().mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }.distinct()

internal fun List<JellyfinDayOfWeek>?.toDaysOfWeek(): List<DayOfWeek> =
    orEmpty().mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name == day.name } }.distinct()

private fun String.isWebUrl(): Boolean = startsWith("https://") || startsWith("http://")
