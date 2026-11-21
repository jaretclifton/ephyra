package dev.jdtech.jellyfin.models

/** Narrows a list of items. */
enum class ItemFilter {
    IS_UNPLAYED
}

/**
 * What an episode list should carry beyond the basics. The server computes these only when asked.
 */
enum class EpisodeField {
    OVERVIEW,
    CAN_DOWNLOAD,
    CHAPTERS,
    TRICKPLAY,
}
