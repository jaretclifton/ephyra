package dev.jdtech.jellyfin.models

data class FindroidTrickplayInfo(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val interval: Int,
    val bandwidth: Int,
)
