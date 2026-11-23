package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.Item

/** One page of items. [totalRecordCount] is null unless the caller asked for it. */
data class ItemsPage(val items: List<Item>, val totalRecordCount: Int? = null)
