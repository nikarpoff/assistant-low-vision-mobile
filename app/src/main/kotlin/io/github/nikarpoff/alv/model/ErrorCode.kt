package io.github.nikarpoff.alv.model

/**
 * Error codes of the `error` message (PROTO-11).
 *
 * The app picks the phrase for the user by the code. A lost connection without
 * a message from the server is not a code: it is handled separately.
 *
 * @property wire value of `code` in the protocol
 * @property retryable value of `retryable` the gateway sends with the code
 */
enum class ErrorCode(val wire: String, val retryable: Boolean) {
    BAD_REQUEST("bad_request", false),
    UNSUPPORTED_PROTOCOL("unsupported_protocol", false),
    PAYLOAD_TOO_LARGE("payload_too_large", false),
    BUSY("busy", true),
    ASR_EMPTY("asr_empty", true),
    ASR_UNAVAILABLE("asr_unavailable", true),
    DETECTOR_UNAVAILABLE("detector_unavailable", true),
    UPSTREAM_TIMEOUT("upstream_timeout", true),
    INTERNAL("internal", true),
    ;

    companion object {
        /** Code by its protocol value, or `null` for a value this version does not know. */
        fun fromWire(wire: String): ErrorCode? = entries.firstOrNull { it.wire == wire }
    }
}
