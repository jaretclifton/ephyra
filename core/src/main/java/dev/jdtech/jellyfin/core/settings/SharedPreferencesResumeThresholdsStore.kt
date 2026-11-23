package dev.jdtech.jellyfin.core.settings

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.ServerId
import dev.jdtech.jellyfin.models.asString
import dev.jdtech.jellyfin.repository.ResumeThresholds
import dev.jdtech.jellyfin.repository.ResumeThresholdsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * CE (#637): the [ResumeThresholdsStore] of the app, in its preferences, one key per server
 * id. #918: moved from `data` (`ResumeThresholdsStore`), with the same keys in the same file.
 */
class SharedPreferencesResumeThresholdsStore(private val preferences: SharedPreferences) :
    ResumeThresholdsStore {

    override fun get(serverId: ServerId?): ResumeThresholds =
        serverId?.let { preferences.getString(key(it), null) }?.let(::parse)
            ?: ResumeThresholds.DEFAULT

    override fun put(serverId: ServerId, thresholds: ResumeThresholds) {
        preferences.edit().putString(key(serverId), format(thresholds)).apply()
        readThisProcess.add(serverId)
    }

    override fun isFresh(serverId: ServerId): Boolean = serverId in readThisProcess

    private fun key(serverId: ServerId) = "ce_resume_thresholds_${serverId.asString()}"

    internal companion object {
        private val readThisProcess: MutableSet<ServerId> = ConcurrentHashMap.newKeySet()

        fun format(thresholds: ResumeThresholds): String =
            with(thresholds) { "$minResumePct,$maxResumePct,$minResumeDurationSeconds" }

        /** Null for a value that is not three numbers, so the defaults are used instead. */
        fun parse(value: String): ResumeThresholds? {
            val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 3) return null
            return ResumeThresholds(parts[0], parts[1], parts[2])
        }
    }
}
