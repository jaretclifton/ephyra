package dev.jdtech.jellyfin.models

import java.util.UUID

/** CE (#775): a date of a stored item, in epoch milliseconds, from the local database. */
data class StoredItemDate(val itemId: UUID, val date: Long?)
