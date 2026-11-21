package dev.jdtech.jellyfin.domain.ports

/**
 * #763: puts the saved server, user and token on a new session, before anything uses it.
 * Implemented in core, which knows Room. Not suspending on purpose: the Hilt provider of the
 * session calls it and cannot suspend, and the first screen needs a client that is already signed
 * in.
 */
fun interface SessionRestorer {
    fun restore(session: ServerSession)
}
