package dev.jdtech.jellyfin

import androidx.navigation3.runtime.NavKey
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ServerId
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute : NavKey

@Serializable data object PermissionRoute : NavKey

@Serializable data object ServersRoute : NavKey

@Serializable data object AddServerRoute : NavKey

@Serializable data class ServerAddressesRoute(val serverId: ServerId) : NavKey

@Serializable data object UsersRoute : NavKey

@Serializable data class LoginRoute(val username: String? = null) : NavKey

@Serializable data object HomeRoute : NavKey

@Serializable data object MediaRoute : NavKey

@Serializable data object DownloadsRoute : NavKey

@Serializable
data class LibraryRoute(
    val libraryId: ItemId,
    val libraryName: String,
    val libraryType: CollectionType,
) : NavKey

@Serializable
data class CollectionRoute(val collectionId: ItemId, val collectionName: String) : NavKey

@Serializable data object FavoritesRoute : NavKey

@Serializable data class MovieRoute(val movieId: ItemId) : NavKey

@Serializable data class ShowRoute(val showId: ItemId, val downloadsOnly: Boolean = false) : NavKey

@Serializable data class EpisodeRoute(val episodeId: ItemId) : NavKey

@Serializable data class DownloadedShowRoute(val showId: ItemId, val showName: String) : NavKey

/** [seriesId] lets the season page request everything at once (#252); null when not known. */
@Serializable
data class SeasonRoute(
    val seasonId: ItemId,
    val downloadsOnly: Boolean = false,
    val seriesId: ItemId? = null,
) : NavKey

@Serializable data class PersonRoute(val personId: ItemId) : NavKey

@Serializable data class SettingsRoute(val indexes: List<Int>) : NavKey

@Serializable data object SubtitleStyleRoute : NavKey

// CE (#142)
@Serializable data object HomeRowsRoute : NavKey

// CE (#299)
@Serializable data object ProfilePinRoute : NavKey

@Serializable data class SettingsFileEditRoute(val filePath: String) : NavKey

@Serializable data object AboutRoute : NavKey

// CE (#211)
@Serializable data object LicensesRoute : NavKey
