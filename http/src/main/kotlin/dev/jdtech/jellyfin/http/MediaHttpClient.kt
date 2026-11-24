package dev.jdtech.jellyfin.http

import okhttp3.OkHttpClient

/**
 * The server API's own OkHttp client, for the player (#750). ExoPlayer streams media over a client
 * derived from it with `newBuilder()`, so media shares the connection pool with the API (#296); mpv
 * uses it for subtitle files only, as libmpv fetches the media itself.
 *
 * Not a port of `domain` (#964): it names a library type, and `domain` is plain Kotlin (R19). The
 * adapter that owns the client (`server/jellyfin`) provides it, the player uses it; this module is
 * the contract between the two, so neither depends on the other.
 */
fun interface MediaHttpClient {
    fun client(): OkHttpClient
}
