package dev.jdtech.jellyfin.models

import java.util.UUID

data class FindroidPerson(
    val id: UUID,
    val name: String,
    val overview: String,
    val images: FindroidImages,
)
