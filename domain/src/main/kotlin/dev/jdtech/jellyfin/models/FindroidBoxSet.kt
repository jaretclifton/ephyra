package dev.jdtech.jellyfin.models

data class FindroidBoxSet(
    override val id: ItemId,
    override val name: String,
    override val originalTitle: String? = null,
    override val overview: String = "",
    override val played: Boolean = false,
    override val favorite: Boolean = false,
    override val canPlay: Boolean = false,
    override val canDownload: Boolean = false,
    override val sources: List<FindroidSource> = emptyList(),
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int? = null,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem
