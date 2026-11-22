package dev.jdtech.jellyfin.player.cast

/**
 * Direct play is MP4 only (see the device profiles). The source's own container is no help: the
 * server gives ffprobe's list for it, "mov,mp4,m4a,3gp,3g2,mj2".
 */
internal fun castContentType(isTranscoded: Boolean): String =
    if (isTranscoded) "application/x-mpegurl" else "video/mp4"

/**
 * The audio stream a stream URL was asked for, `null` when it names none. The audio track is part
 * of the stream the server was asked for, so this is how a session that is picked up again knows
 * which one plays.
 */
internal fun audioStreamIndexOf(streamUrl: String): Int? =
    streamUrl
        .substringBefore('#')
        .substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=').equals("AudioStreamIndex", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.toIntOrNull()
