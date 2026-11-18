package io.github.nikarpoff.alv.model

/**
 * Action of the assistant (KM-2).
 *
 * Actions with [onServer] are done by the gateway; the app only records them.
 * Actions without a number are the parts of transitions KM-2 names in words.
 *
 * @property id number of the action in KM-2, empty for an action without a number
 * @property onServer the gateway performs the action
 */
enum class Action(val id: String, val onServer: Boolean = false) {
    /** A1: take the last frame of the running camera and send it to the gateway. */
    TAKE_PHOTO("A1"),

    /** A2: look for hazards. */
    FIND_HAZARDS("A2", onServer = true),

    /** A3: general analysis of the scene. */
    ANALYZE_SCENE("A3", onServer = true),

    /** A4: compose the description. */
    COMPOSE_DESCRIPTION("A4", onServer = true),

    /** A5: speak the description: play the audio stream. */
    SPEAK("A5"),

    /** A6: turn the microphone on, listen window mode. */
    MIC_ON("A6"),

    /** A6 in hold mode: turn the microphone on and record while the finger is down. */
    MIC_ON_HOLD("A6"),

    /** A7: analysis of the surroundings with the question. */
    ANALYZE_WITH_QUESTION("A7", onServer = true),

    /** A8: turn the microphone off. */
    MIC_OFF("A8"),

    /** A9: stop the playback and cancel the current request (`cancel`). */
    INTERRUPT("A9"),

    /** A10: repeat the last answer from the memory of the app. */
    REPEAT_LAST("A10"),

    /** A11: report the error: error earcon and a short phrase by the code (PROTO-11). */
    REPORT_ERROR("A11"),

    /** A12: switch the passive mode and speak the new state. */
    SET_PASSIVE_MODE("A12"),

    /** A13: confirm the gesture with a sound and/or vibration within 100 ms. */
    CONFIRM_GESTURE("A13"),

    /** Cancel the passive request in flight (T5, T20). */
    CANCEL_PASSIVE(""),

    /** Keep the microphone on and switch the recording to hold mode (T14). */
    RECORD_HOLD(""),

    /** Short "unavailable" signal: the gesture does nothing in this state. */
    SIGNAL_UNAVAILABLE(""),
}
