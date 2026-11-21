package dev.jdtech.jellyfin.models

import java.time.LocalDateTime
import java.util.UUID

data class FindroidEpisode(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    override val sources: List<FindroidSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val seriesId: UUID,
    val seriesName: String,
    val seasonId: UUID,
    val seasonName: String?,
    val communityRating: Float?,
    val people: List<FindroidItemPerson>,
    override val unplayedItemCount: Int? = null,
    val missing: Boolean = false,
    /** When the server last saw this episode played; null if never, or when read offline. */
    val lastPlayedDate: LocalDateTime? = null,
    // CE (#332): from the item response, not stored offline.
    val externalLinks: List<FindroidExternalLink> = emptyList(),
    /** How often the server saw this episode played to the end. */
    val playCount: Int = 0,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter>,
    override val trickplayInfo: Map<String, FindroidTrickplayInfo>?,
) : FindroidItem, FindroidSources
