package dev.jdtech.jellyfin.models

data class Library(
    override val id: ItemId,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = false,
    override val canDownload: Boolean = false,
    override val sources: List<ItemSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int? = null,
    val type: CollectionType,
    override val images: ItemImages,
    override val chapters: List<Chapter> = emptyList(),
) : Item
