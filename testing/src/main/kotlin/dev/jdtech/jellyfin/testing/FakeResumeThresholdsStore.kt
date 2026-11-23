package dev.jdtech.jellyfin.testing

import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.repository.ResumeThresholds
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore

/**
 * #918: a [ResumeThresholdsStore] in memory, for tests that build the repository. A server is fresh
 * once its thresholds were put in this store.
 */
class FakeResumeThresholdsStore : ResumeThresholdsStore {
    private val stored = mutableMapOf<ServerId, ResumeThresholds>()

    override fun get(serverId: ServerId?): ResumeThresholds =
        serverId?.let(stored::get) ?: ResumeThresholds.DEFAULT

    override fun put(serverId: ServerId, thresholds: ResumeThresholds) {
        stored[serverId] = thresholds
    }

    override fun isFresh(serverId: ServerId): Boolean = serverId in stored
}
