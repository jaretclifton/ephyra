package dev.jdtech.jellyfin.models

interface Playable {
    val sources: List<ItemSource>
    val runtimeTicks: Long
    val trickplayInfo: Map<String, ItemTrickplayInfo>?
}
