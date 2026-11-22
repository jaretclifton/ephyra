package dev.jdtech.jellyfin.models

/** CE (#775): a date of a stored item, in epoch milliseconds, from the local database. */
data class StoredItemDate(val itemId: ItemId, val date: Long?)
