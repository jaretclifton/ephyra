package dev.jdtech.jellyfin.models

import java.util.UUID

data class FindroidSeason(
    override val id: UUID,
    override val name: String,
    val seriesId: UUID,
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
