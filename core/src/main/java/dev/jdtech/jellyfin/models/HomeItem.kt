package dev.jdtech.jellyfin.models

sealed class HomeItem {
    data object OfflineCard : HomeItem() {
        override val id: ItemId = "dbfef8a9-7ff0-4c36-9e36-81dfd65fdd46".toItemId()
    }

    data class Suggestions(override val id: ItemId, val items: List<Item>) : HomeItem()

    data class Section(val homeSection: HomeSection) : HomeItem() {
        override val id = homeSection.id
    }

    data class ViewItem(val view: View) : HomeItem() {
        override val id = view.id
    }

    abstract val id: ItemId
}
