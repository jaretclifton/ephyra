// The HTTP contract between an adapter and the player (#964): MediaHttpClient, the API's OkHttp
// client. `server/jellyfin` provides it, `player/local` uses it (ExoPlayer's media, mpv's subtitle
// files), so the player shares the API's connection pool (#296) while neither module depends on
// the other (R18) and `domain` stays plain Kotlin (R19). Plain Kotlin with OkHttp, nothing else.
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    // MediaHttpClient returns an OkHttpClient, hence `api`: a module that uses it compiles without
    // declaring OkHttp.
    api(libs.okhttp)
}
