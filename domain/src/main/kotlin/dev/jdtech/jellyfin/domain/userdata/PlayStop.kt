package dev.jdtech.jellyfin.domain.userdata

import dev.jdtech.jellyfin.models.Ticks
import dev.jdtech.jellyfin.repository.ResumeThresholds

/**
 * What a playback stop writes: the resume position, and the played flag, which stays as it was when
 * [played] is null. Applied to a state with [PlayState.stopped].
 */
data class PlayStop(val positionTicks: Long, val played: Boolean?) {
    companion object {
        /**
         * What a stop at [positionTicks] writes. CE (#637): the same as the server does with it
         * (`UserDataManager.UpdatePlayState` in Jellyfin), with the server's [thresholds]:
         * - before [ResumeThresholds.minResumePct]: no position; the played flag stays as it was
         * - after [ResumeThresholds.maxResumePct], or within a second of the end: played, no
         *   position
         * - an item shorter than [ResumeThresholds.minResumeDurationSeconds]: played, no position
         * - otherwise the position is saved; the played flag stays as it was
         *
         * The percentage comes from [runtimeTicks], the server's runtime of the downloaded item, as
         * the server computes it; without one, from [playedPercentage], measured by the player.
         *
         * `null` writes nothing: the item was left before its duration was known (#461), so the
         * local position, played flag and sync flag stay as they were.
         */
        fun of(
            positionTicks: Long,
            playedPercentage: Int?,
            runtimeTicks: Long? = null,
            thresholds: ResumeThresholds = ResumeThresholds.DEFAULT,
        ): PlayStop? {
            val measured = playedPercentage ?: return null
            if (positionTicks <= 0) return PlayStop(positionTicks = 0L, played = null)

            val runtime = runtimeTicks?.takeIf { it > 0 }
            val percentage =
                if (runtime != null) positionTicks * 100.0 / runtime else measured.toDouble()
            val atEnd = runtime != null && positionTicks >= runtime - Ticks.PER_SECOND
            val tooShort =
                runtime != null && Ticks.toSeconds(runtime) < thresholds.minResumeDurationSeconds

            return when {
                percentage < thresholds.minResumePct -> PlayStop(positionTicks = 0L, played = null)
                percentage > thresholds.maxResumePct || atEnd ->
                    PlayStop(positionTicks = 0L, played = true)
                tooShort -> PlayStop(positionTicks = 0L, played = true)
                else -> PlayStop(positionTicks = positionTicks, played = null)
            }
        }
    }
}
