package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.ServerId

/**
 * CE (#637): the last [ResumeThresholds] read from each server, kept so stops made offline follow
 * them too. Kept per server id, because each server has its own. #918: a port, so `data` does not
 * know where they are kept; `core` keeps them in the app's preferences.
 */
interface ResumeThresholdsStore {
    /** The ones kept for [serverId], or [ResumeThresholds.DEFAULT] when none are (or no server). */
    fun get(serverId: ServerId?): ResumeThresholds

    fun put(serverId: ServerId, thresholds: ResumeThresholds)

    /** Whether [serverId]'s were read in this run: they rarely change, so once will do. */
    fun isFresh(serverId: ServerId): Boolean
}
