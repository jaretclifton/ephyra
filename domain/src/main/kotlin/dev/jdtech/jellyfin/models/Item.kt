package dev.jdtech.jellyfin.models

interface Item {
    val id: ItemId
    val name: String
    val originalTitle: String?
    val overview: String
    val played: Boolean
    val favorite: Boolean
    val canPlay: Boolean
    val canDownload: Boolean
    val sources: List<ItemSource>
    val runtimeTicks: Long
    val playbackPositionTicks: Long
    val unplayedItemCount: Int?
    val images: ItemImages
    val chapters: List<Chapter>
}

fun Item.isDownloading(): Boolean {
    return sources.filter { it.type == ItemSourceType.LOCAL }.any { it.path.endsWith(".download") }
}

fun Item.isDownloaded(): Boolean {
    return sources.filter { it.type == ItemSourceType.LOCAL }.any { !it.path.endsWith(".download") }
}

/** The versions a user can pick from. A downloaded copy keeps the id of its server version. */
fun Item.getVersions(): List<ItemSource> {
    return sources.distinctBy { it.id }
}
