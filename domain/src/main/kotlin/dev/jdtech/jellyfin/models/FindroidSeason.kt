package dev.jdtech.jellyfin.models

data class FindroidSeason(
    override val id: ItemId,
    override val name: String,
    val seriesId: ItemId,
    val seriesName: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val indexNumber: Int,
    val episodes: Collection<FindroidEpisode>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem
