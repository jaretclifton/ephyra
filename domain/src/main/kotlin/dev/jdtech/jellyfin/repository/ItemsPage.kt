package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.FindroidItem

/** One page of items. [totalRecordCount] is null unless the caller asked for it. */
data class ItemsPage(val items: List<FindroidItem>, val totalRecordCount: Int? = null)
