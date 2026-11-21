package dev.jdtech.jellyfin.setup.domain

/** Why [SetupRepository.addAddress] did not save an address. */
class AddressRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** No server answers there, or the one that does is not the saved server. */
        NOT_FOUND,

        /** The saved server already has this address. */
        ALREADY_SAVED,
    }
}
