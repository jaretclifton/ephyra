package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.PersonKind
import org.jellyfin.sdk.model.api.BaseItemKind

/** Maps by name: [PersonKind] has a counterpart of every SDK constant (see SdkEnumsTest). */
internal fun org.jellyfin.sdk.model.api.PersonKind.toModel(): PersonKind = PersonKind.valueOf(name)

/** The SDK kind of this kind. [ItemKind.UNKNOWN] has none: it throws [IllegalArgumentException]. */
internal fun ItemKind.toSdk(): BaseItemKind =
    when (this) {
        ItemKind.MOVIE -> BaseItemKind.MOVIE
        ItemKind.SERIES -> BaseItemKind.SERIES
        ItemKind.SEASON -> BaseItemKind.SEASON
        ItemKind.EPISODE -> BaseItemKind.EPISODE
        ItemKind.BOX_SET -> BaseItemKind.BOX_SET
        ItemKind.FOLDER -> BaseItemKind.FOLDER
        ItemKind.PHOTO_ALBUM -> BaseItemKind.PHOTO_ALBUM
        ItemKind.VIDEO -> BaseItemKind.VIDEO
        ItemKind.MUSIC_VIDEO -> BaseItemKind.MUSIC_VIDEO
        ItemKind.COLLECTION_FOLDER -> BaseItemKind.COLLECTION_FOLDER
        ItemKind.UNKNOWN -> throw IllegalArgumentException("ItemKind.UNKNOWN has no SDK kind")
    }

internal fun List<ItemKind>.toSdk(): List<BaseItemKind> = map { it.toSdk() }

/** The app's kind of this SDK kind; [ItemKind.UNKNOWN] for a kind the app does not list. */
internal fun BaseItemKind.toItemKind(): ItemKind =
    when (this) {
        BaseItemKind.MOVIE -> ItemKind.MOVIE
        BaseItemKind.SERIES -> ItemKind.SERIES
        BaseItemKind.SEASON -> ItemKind.SEASON
        BaseItemKind.EPISODE -> ItemKind.EPISODE
        BaseItemKind.BOX_SET -> ItemKind.BOX_SET
        BaseItemKind.FOLDER -> ItemKind.FOLDER
        BaseItemKind.PHOTO_ALBUM -> ItemKind.PHOTO_ALBUM
        BaseItemKind.VIDEO -> ItemKind.VIDEO
        BaseItemKind.MUSIC_VIDEO -> ItemKind.MUSIC_VIDEO
        BaseItemKind.COLLECTION_FOLDER -> ItemKind.COLLECTION_FOLDER
        else -> ItemKind.UNKNOWN
    }
