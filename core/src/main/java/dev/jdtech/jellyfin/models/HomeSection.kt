package dev.jdtech.jellyfin.models

data class HomeSection(val id: ItemId, val name: UiText, val items: List<FindroidItem>)
