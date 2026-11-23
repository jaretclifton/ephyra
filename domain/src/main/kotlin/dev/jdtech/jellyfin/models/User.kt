package dev.jdtech.jellyfin.models

/** A user saved on a [Server] (#899: the app's own model of a row of Room's `users` table). */
data class User(
    val id: UserId,
    val name: String,
    val serverId: ServerId,
    val accessToken: String? = null,
    /** Tag of the user's Jellyfin profile picture; null when they have none. */
    val primaryImageTag: String? = null,
    /**
     * CE (#299): the profile PIN, as a PBKDF2 hash with [pinSalt]. Local only, never sent to the
     * server; null when the profile has no PIN.
     */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /**
     * Whether a profile with a PIN also asks for it when the app starts in it (not only on a
     * switch).
     */
    val pinOnStart: Boolean = true,
    /**
     * CE (#330): what [pinHash] is the hash of, [ProfileLockType.value]: `pin` (digits) or
     * `pattern` (dots on a 3×3 grid). One lock type per profile; `pin` when the profile has none.
     */
    val lockType: String = ProfileLockType.PIN.value,
)

/** CE (#330): how a locked profile is unlocked. TV only offers [PIN]. */
enum class ProfileLockType(val value: String) {
    PIN("pin"),
    PATTERN("pattern");

    companion object {
        /** Unknown values read as [PIN], the type every lock had before #330. */
        fun fromValue(value: String?): ProfileLockType =
            entries.firstOrNull { it.value == value } ?: PIN
    }
}

/**
 * CE (#299): the profile is locked behind a local PIN, or since #330 a pattern ([profileLockType]).
 */
val User.hasPin: Boolean
    get() = pinHash != null

/** CE (#330): the type of the profile's lock, or null when it has none. */
val User.profileLockType: ProfileLockType?
    get() = if (hasPin) ProfileLockType.fromValue(lockType) else null
