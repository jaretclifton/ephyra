package dev.jdtech.jellyfin.models

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.UUID

data class FindroidShow(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val seasons: List<FindroidSeason>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    val genres: List<String>,
    val people: List<FindroidItemPerson>,
    override val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: LocalDateTime?,
    val trailer: String?,
    // CE (#332): what the item response already carries. Not stored offline, so empty there.
    /** Every remote trailer; [trailer] is the first. */
    val trailers: List<FindroidTrailer> = emptyList(),
    val tagline: String? = null,
    /** The Rotten Tomatoes score (0–100) from the server's OMDb provider. */
    val criticRating: Float? = null,
    /** The networks that air the show. */
    val studios: List<String> = emptyList(),
    val externalLinks: List<FindroidExternalLink> = emptyList(),
    val airDays: List<DayOfWeek> = emptyList(),
    /** As the server has it, such as "21:00" or "9:00 PM". */
    val airTime: String? = null,
    val localTrailerCount: Int = 0,
    val specialFeatureCount: Int? = null,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem
