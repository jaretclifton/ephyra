package dev.jdtech.jellyfin.models

/** A failure talking to a media server, whatever the server type. */
sealed class ServerException(message: String?, cause: Throwable?) : Exception(message, cause) {
    /** The server answered, with an error [status]. */
    class HttpStatus(val status: Int, cause: Throwable? = null) :
        ServerException("HTTP $status", cause)

    /** The server did not answer in time. */
    class Timeout(cause: Throwable? = null) : ServerException("timeout", cause)

    /** The secure connection to the server failed. */
    class Tls(cause: Throwable? = null) : ServerException("TLS", cause)

    /** The server could not be reached. */
    class Unreachable(cause: Throwable? = null) : ServerException("unreachable", cause)
}
