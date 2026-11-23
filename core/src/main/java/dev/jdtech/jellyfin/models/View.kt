package dev.jdtech.jellyfin.models

data class View(val id: ItemId, val name: String, val items: List<Item>, val type: CollectionType)
