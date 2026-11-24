package dev.jdtech.jellyfin.http

import okhttp3.OkHttpClient

/**
 * The OkHttp client media is streamed through (#750). The player derives its own clients from it
 * with `newBuilder()`, so they share its connection pool with the server's API (#296).
 *
 * Not a port of `domain` (#964): it names a library type, and `domain` is plain Kotlin (R19). The
 * adapter that owns the client (`server/jellyfin`) provides it, the player uses it; this module is
 * the contract between the two, so neither depends on the other.
 */
fun interface MediaHttpClient {
    fun client(): OkHttpClient
}
