package dev.jdtech.jellyfin.settings.domain

/**
 * CE (#837): the kind of network a default streaming quality is for. Told apart by whether Android
 * counts the network as metered, not by its transport: a phone's Wi-Fi hotspot is metered (mobile
 * data underneath), a VPN takes over the state of the network under it, and a network the user
 * marked metered or unmetered in Android's settings counts as marked.
 */
enum class QualityNetwork {
    /** Wi-Fi, Ethernet: a network that is not metered. */
    WIFI,

    /** Mobile data: a metered network. */
    MOBILE;

    companion object {
        fun of(metered: Boolean): QualityNetwork = if (metered) MOBILE else WIFI
    }
}
