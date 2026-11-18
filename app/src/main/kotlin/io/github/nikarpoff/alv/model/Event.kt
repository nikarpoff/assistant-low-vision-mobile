package io.github.nikarpoff.alv.model

/**
 * Event of the assistant (KM-2).
 *
 * @property id number of the event in KM-2
 * @property gesture the user caused the event; ignoring it is confirmed with the "unavailable" signal
 */
sealed class Event(val id: String, val gesture: Boolean = false) {
    /** E1: 3 s passed in S1 with the passive mode on. */
    data object PassiveTimer : Event("E1")

    /** E2: tap. */
    data object Tap : Event("E2", gesture = true)

    /** E3: the passive analysis found a hazard (`answer.start` with `kind=warning`). */
    data object HazardFound : Event("E3")

    /** E4: the analysis on request is ready (`answer.start`). */
    data object DescriptionReady : Event("E4")

    /** E5: the passive analysis found no hazard (`passive.clear`). */
    data object PassiveClear : Event("E5")

    /** E6: the playback of the answer finished. */
    data object PlaybackFinished : Event("E6")

    /** E7: the question is asked: the speech detector saw its end, or the finger is up after speech over 300 ms. */
    data object QuestionAsked : Event("E7", gesture = true)

    /** E8: the answer to the question is ready (`answer.start`). */
    data object AnswerReady : Event("E8")

    /** E9: 5 s in S5 without speech. */
    data object ListenTimeout : Event("E9")

    /** E10: the hold started (touch over 400 ms). */
    data object HoldStarted : Event("E10", gesture = true)

    /** E11: the hold ended without speech (under 300 ms or silence). */
    data object HoldEndedWithoutSpeech : Event("E11", gesture = true)

    /** E12: double tap. */
    data object DoubleTap : Event("E12", gesture = true)

    /** E13: error: no connection, `error`, deadline exceeded. */
    data object Failure : Event("E13")

    /** E14: swipe up: switch the passive mode. */
    data object SwipeUp : Event("E14", gesture = true)

    /** E15: swipe down or the "stop" command: interrupt the speech. */
    data object Stop : Event("E15", gesture = true)

    /**
     * E16: a command the app executes (`transcript` with `intent` [Command]).
     *
     * @property command the command
     */
    data class AppCommand(val command: Command) : Event("E16")

    /** Command of E16 (PLT-4). */
    enum class Command {
        REPEAT,
        STOP,
        PASSIVE_ON,
        PASSIVE_OFF,
    }
}
