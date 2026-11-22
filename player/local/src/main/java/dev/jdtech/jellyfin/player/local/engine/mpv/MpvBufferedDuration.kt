package dev.jdtech.jellyfin.player.local.engine.mpv

/**
 * #847: what [MPVPlayer.getTotalBufferedDuration] reports. Media3's contract is the duration
 * buffered ahead of the current position, so never negative and zero when nothing is buffered
 * ahead. mpv's `demuxer-cache-time`, which [MPVPlayer.getBufferedPosition] reports, is an absolute
 * time and must not be returned as the duration.
 */
internal fun bufferedDurationAheadMs(bufferedPositionMs: Long, positionMs: Long): Long =
    (bufferedPositionMs - positionMs).coerceAtLeast(0L)
