package dev.jdtech.jellyfin.models

data class FindroidPerson(
    val id: ItemId,
    val name: String,
    val overview: String,
    val images: FindroidImages,
)
