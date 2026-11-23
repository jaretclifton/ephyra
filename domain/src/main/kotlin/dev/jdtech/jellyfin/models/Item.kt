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
    return sources.any { it.downloadState == DownloadState.DOWNLOADING }
}

fun Item.isDownloaded(): Boolean {
    return sources.any { it.downloadState == DownloadState.FINISHED }
}

/** The versions a user can pick from. A downloaded copy keeps the id of its server version. */
fun Item.getVersions(): List<ItemSource> {
    return sources.distinctBy { it.id }
}
