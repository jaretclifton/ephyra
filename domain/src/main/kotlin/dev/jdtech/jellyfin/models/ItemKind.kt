package dev.jdtech.jellyfin.models

/** The kinds of item the app handles. [serialName] is what a route or an intent extra carries. */
enum class ItemKind(val serialName: String) {
    MOVIE("Movie"),
    SERIES("Series"),
    SEASON("Season"),
    EPISODE("Episode"),
    BOX_SET("BoxSet"),
    FOLDER("Folder"),
    PHOTO_ALBUM("PhotoAlbum"),
    VIDEO("Video"),
    MUSIC_VIDEO("MusicVideo"),
    COLLECTION_FOLDER("CollectionFolder"),
    UNKNOWN("Unknown");

    companion object {
        /**
         * The kind whose [serialName] is exactly [name]. Like the SDK's `BaseItemKind.fromName`, it
         * throws [IllegalArgumentException] for anything else (#740).
         */
        fun fromName(name: String): ItemKind =
            entries.firstOrNull { it.serialName == name }
                ?: throw IllegalArgumentException("Unknown item kind: $name")
    }
}
