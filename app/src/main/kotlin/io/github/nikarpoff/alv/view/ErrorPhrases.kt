package io.github.nikarpoff.alv.view

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.github.nikarpoff.alv.R
import io.github.nikarpoff.alv.model.ErrorCode

/**
 * System phrase recorded in advance (MOB-11).
 *
 * The list of phrases is `phrases/phrases.yaml`; `tts-service` renders each one into
 * `res/raw/phrase_<id>.wav`, and `res/values/strings.xml` keeps the same text as
 * `phrase_<id>` for the screen and for the fallback speech.
 *
 * @property id id in `phrases.yaml`
 * @property raw WAV of the phrase
 * @property text text of the phrase
 */
data class Phrase(val id: String, @param:RawRes val raw: Int, @param:StringRes val text: Int)

/** Phrase for the user by the error code (PROTO-11) and for a lost connection (MOB-8). */
object ErrorPhrases {
    val badRequest = Phrase("error_bad_request", R.raw.phrase_error_bad_request, R.string.phrase_error_bad_request)
    val unsupportedProtocol = Phrase(
        "error_unsupported_protocol",
        R.raw.phrase_error_unsupported_protocol,
        R.string.phrase_error_unsupported_protocol,
    )
    val questionTooLong = Phrase(
        "error_question_too_long",
        R.raw.phrase_error_question_too_long,
        R.string.phrase_error_question_too_long,
    )
    val busy = Phrase("error_busy", R.raw.phrase_error_busy, R.string.phrase_error_busy)
    val asrEmpty = Phrase("error_asr_empty", R.raw.phrase_error_asr_empty, R.string.phrase_error_asr_empty)
    val asrUnavailable = Phrase(
        "error_asr_unavailable",
        R.raw.phrase_error_asr_unavailable,
        R.string.phrase_error_asr_unavailable,
    )
    val detectorUnavailable = Phrase(
        "error_detector_unavailable",
        R.raw.phrase_error_detector_unavailable,
        R.string.phrase_error_detector_unavailable,
    )
    val upstreamTimeout = Phrase(
        "error_upstream_timeout",
        R.raw.phrase_error_upstream_timeout,
        R.string.phrase_error_upstream_timeout,
    )
    val internal = Phrase("error_internal", R.raw.phrase_error_internal, R.string.phrase_error_internal)

    /** No connection: E13 without a message from the server, or a gesture while offline. */
    val noConnection = Phrase("no_connection", R.raw.phrase_no_connection, R.string.phrase_no_connection)

    /**
     * Phrase for the `code` of an `error` message.
     *
     * `payload_too_large` means «too long a question» for a question and «request error»
     * for a description, where only the snapshot can be too large. A code this version
     * does not know (`null`) gets the phrase of `internal`.
     *
     * @param code error code, `null` for an unknown one
     * @param question the failed request is a question (`ask`)
     */
    fun forCode(code: ErrorCode?, question: Boolean = false): Phrase = when (code) {
        ErrorCode.BAD_REQUEST -> badRequest
        ErrorCode.UNSUPPORTED_PROTOCOL -> unsupportedProtocol
        ErrorCode.PAYLOAD_TOO_LARGE -> if (question) questionTooLong else badRequest
        ErrorCode.BUSY -> busy
        ErrorCode.ASR_EMPTY -> asrEmpty
        ErrorCode.ASR_UNAVAILABLE -> asrUnavailable
        ErrorCode.DETECTOR_UNAVAILABLE -> detectorUnavailable
        ErrorCode.UPSTREAM_TIMEOUT -> upstreamTimeout
        ErrorCode.INTERNAL, null -> internal
    }
}

/** Other system phrases: the passive mode (MOB-14) and the permissions (MOB-15). */
object SystemPhrases {
    val passiveOn = Phrase("passive_on", R.raw.phrase_passive_on, R.string.phrase_passive_on)
    val passiveOff = Phrase("passive_off", R.raw.phrase_passive_off, R.string.phrase_passive_off)
    val permissionRationale = Phrase(
        "permission_rationale",
        R.raw.phrase_permission_rationale,
        R.string.phrase_permission_rationale,
    )
    val permissionDenied = Phrase(
        "permission_denied",
        R.raw.phrase_permission_denied,
        R.string.phrase_permission_denied,
    )

    /** Phrase announcing the new state of the passive mode. */
    fun passiveMode(enabled: Boolean): Phrase = if (enabled) passiveOn else passiveOff
}
