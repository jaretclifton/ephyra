package dev.jdtech.jellyfin.models

/** The kinds of media server the app can talk to (#757). [value] is the stored form. */
enum class ServerType(val value: String) {
    JELLYFIN("jellyfin"),
    EMBY("emby");

    companion object {
        /** The type whose [value] is exactly [value], or null for anything else. */
        fun fromValue(value: String): ServerType? = entries.firstOrNull { it.value == value }
    }
}
