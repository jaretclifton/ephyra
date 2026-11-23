package dev.jdtech.jellyfin.models

import java.time.LocalDateTime

data class Movie(
    override val id: ItemId,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<ItemSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: LocalDateTime?,
    val people: List<ItemPerson>,
    val genres: List<String>,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    override val unplayedItemCount: Int? = null,
    /** When the server last saw this movie played; null if never, or when read offline. */
    val lastPlayedDate: LocalDateTime? = null,
    // CE (#332): what the item response already carries. Not stored offline, so empty there.
    /** Every remote trailer; [trailer] is the first. */
    val trailers: List<Trailer> = emptyList(),
    val tagline: String? = null,
    /** The Rotten Tomatoes score (0–100) from the server's OMDb provider. */
    val criticRating: Float? = null,
    val studios: List<String> = emptyList(),
    val externalLinks: List<ExternalLink> = emptyList(),
    /** How often the server saw this movie played to the end. */
    val playCount: Int = 0,
    val is3D: Boolean = false,
    val localTrailerCount: Int = 0,
    /** Null when the server didn't say, which is no reason not to ask for the extras. */
    val specialFeatureCount: Int? = null,
    override val images: ItemImages,
    override val chapters: List<Chapter>,
    override val trickplayInfo: Map<MediaSourceId, ItemTrickplayInfo>?,
) : Item, Playable
