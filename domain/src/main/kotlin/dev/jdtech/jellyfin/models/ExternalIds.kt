package dev.jdtech.jellyfin.models

/**
 * CE (#331): the ids of an item at outside sites, as the server knows them. They come with the
 * item, so reading them costs no request. Not stored with a download, so empty there.
 */
data class ExternalIds(val imdb: String? = null, val tmdb: String? = null, val tvdb: String? = null)
