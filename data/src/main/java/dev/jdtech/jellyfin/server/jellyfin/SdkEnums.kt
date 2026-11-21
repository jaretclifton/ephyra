package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.PersonKind

/** Maps by name: [PersonKind] has a counterpart of every SDK constant (see SdkEnumsTest). */
internal fun org.jellyfin.sdk.model.api.PersonKind.toModel(): PersonKind = PersonKind.valueOf(name)
