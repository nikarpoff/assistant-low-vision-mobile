package io.github.nikarpoff.alv.model

/**
 * Answer (KM-1): the last answer of the assistant, kept for the repeat command (A10).
 *
 * Text and speech live in memory only and are never written to disk (MOB-7, MOB-16).
 *
 * @property requestId request the answer belongs to
 * @property kind what the answer is, from `answer.start`
 * @property text full answer text from `answer.end`
 * @property audio answer speech, or `null` when the gateway sent `audio: null` (`tts_unavailable`)
 */
class Answer(
    val requestId: String,
    val kind: Kind,
    val text: String,
    val audio: Audio?,
) {
    /** Kind of the answer; [wire] is the value of `kind` in `answer.start`. */
    enum class Kind(val wire: String) {
        DESCRIPTION("description"),
        ANSWER("answer"),
        WARNING("warning"),
    }

    /**
     * Answer speech as received in AUDIO_OUT frames.
     *
     * @property sampleRate sample rate in Hz, from the `audio` format of `answer.start`
     * @property channels number of channels
     * @property pcm PCM 16-bit little-endian samples
     */
    class Audio(
        val sampleRate: Int,
        val channels: Int,
        val pcm: ByteArray,
    )
}
