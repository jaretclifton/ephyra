package dev.jdtech.jellyfin.domain.ports

/**
 * #770: the server in use is of a type this build has no adapter for: [storedType] is the type as
 * saved. A build that knows more server types saved it, and this one was installed over it.
 */
class UnsupportedServerTypeException(val storedType: String) :
    Exception("No adapter for server type \"$storedType\"")
