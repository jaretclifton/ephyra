package dev.jdtech.jellyfin.domain.ports

import okhttp3.OkHttpClient

/**
 * The OkHttp client media is streamed through (#750). The player derives its own clients from it
 * with `newBuilder()`, so they share its connection pool with the server's API (#296).
 */
fun interface MediaHttpClient {
    fun client(): OkHttpClient
}
