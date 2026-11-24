package dev.jdtech.jellyfin

import androidx.navigation3.runtime.NavKey
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import kotlinx.serialization.Serializable

@Serializable data object WelcomeRoute : NavKey

@Serializable data object PermissionRoute : NavKey

@Serializable data object ServersRoute : NavKey

@Serializable data object AddServerRoute : NavKey

@Serializable data object UsersRoute : NavKey

@Serializable data class LoginRoute(val username: String? = null) : NavKey

@Serializable data object MainRoute : NavKey

@Serializable
data class LibraryRoute(
    val libraryId: ItemId,
    val libraryName: String,
    val libraryType: CollectionType,
) : NavKey

@Serializable
data class CollectionRoute(val collectionId: ItemId, val collectionName: String) : NavKey

@Serializable data class MovieRoute(val itemId: ItemId) : NavKey

@Serializable data class ShowRoute(val itemId: ItemId) : NavKey

/** [seriesId] lets the season page request everything at once (#252); null when not known. */
@Serializable data class SeasonRoute(val seasonId: ItemId, val seriesId: ItemId? = null) : NavKey

/** CE (#130): an episode opens on the season page of its season, with that episode focused. */
@Serializable data class EpisodeRoute(val episodeId: ItemId) : NavKey

/** CE (#136): person page showing bio and movies/shows. */
@Serializable data class PersonRoute(val personId: ItemId) : NavKey

@Serializable
data class PlayerRoute(
    val itemId: ItemId,
    val itemKind: String,
    val mediaSourceId: MediaSourceId? = null,
    val startFromBeginning: Boolean = false,
    val shuffle: Boolean = false,
) : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data class SettingsSubRoute(val indexes: List<Int>) : NavKey

// CE (#142)
@Serializable data object HomeRowsRoute : NavKey

// CE (#331)
@Serializable data object ExternalRatingsRoute : NavKey

// CE (#299)
@Serializable data object ProfilePinRoute : NavKey

// CE (#211)
@Serializable data object AboutRoute : NavKey

@Serializable data object LicensesRoute : NavKey

@Serializable data class LicenseRoute(val uniqueId: String) : NavKey

@Serializable data object DeviceProfileRoute : NavKey
