package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.repository.ResumeThresholds
import org.jellyfin.sdk.model.api.ServerConfiguration

/** CE (#637): the server's resume settings, from its configuration. */
fun ServerConfiguration.toResumeThresholds() =
    ResumeThresholds(
        minResumePct = minResumePct,
        maxResumePct = maxResumePct,
        minResumeDurationSeconds = minResumeDurationSeconds,
    )
