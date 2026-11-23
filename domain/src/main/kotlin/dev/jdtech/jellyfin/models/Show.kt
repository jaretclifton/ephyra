package dev.jdtech.jellyfin.models

import java.time.DayOfWeek
import java.time.LocalDateTime

data class Show(
    override val id: ItemId,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<ItemSource>,
    val seasons: List<Season>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    val genres: List<String>,
    val people: List<ItemPerson>,
    override val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    // CE (#332): what the item response already carries. Not stored offline, so empty there.
    /** Every remote trailer; [trailer] is the first. */
    val trailers: List<Trailer> = emptyList(),
    val tagline: String? = null,
    /** The Rotten Tomatoes score (0–100) from the server's OMDb provider. */
    val criticRating: Float? = null,
    /** The networks that air the show. */
    val studios: List<String> = emptyList(),
    val externalLinks: List<ExternalLink> = emptyList(),
    val airDays: List<DayOfWeek> = emptyList(),
    /** As the server has it, such as "21:00" or "9:00 PM". */
    val airTime: String? = null,
    val localTrailerCount: Int = 0,
    val specialFeatureCount: Int? = null,
    override val images: ItemImages,
    override val chapters: List<Chapter> = emptyList(),
) : Item
