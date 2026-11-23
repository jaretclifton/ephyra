package dev.jdtech.jellyfin.models

import java.time.LocalDateTime

data class Episode(
    override val id: ItemId,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    override val sources: List<ItemSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val seriesId: ItemId,
    val seriesName: String,
    val seasonId: ItemId,
    val seasonName: String?,
    val communityRating: Float?,
    val people: List<ItemPerson>,
    override val unplayedItemCount: Int? = null,
    val missing: Boolean = false,
    /** When the server last saw this episode played; null if never, or when read offline. */
    val lastPlayedDate: LocalDateTime? = null,
    // CE (#332): from the item response, not stored offline.
    val externalLinks: List<ExternalLink> = emptyList(),
    /** How often the server saw this episode played to the end. */
    val playCount: Int = 0,
    override val images: ItemImages,
    override val chapters: List<Chapter>,
    override val trickplayInfo: Map<MediaSourceId, ItemTrickplayInfo>?,
) : Item, Playable
