package io.github.nikarpoff.alv.model

/**
 * State of the assistant (KM-2).
 *
 * @property id number of the state in KM-2
 * @property activities actions performed while in the state; A6 of [LISTENING] runs on entry
 */
enum class State(val id: String, val activities: List<Action>) {
    /** Waiting; with the passive mode on the 3 s timer runs. */
    IDLE("S1", emptyList()),

    /** Passive analysis of the surroundings. */
    PASSIVE("S2", listOf(Action.FIND_HAZARDS)),

    /** Analysis on the request of the user. */
    ANALYZING("S3", listOf(Action.ANALYZE_SCENE)),

    /** Speaking the description, the warning or the repeat. */
    SPEAKING("S4", listOf(Action.SPEAK)),

    /** Waiting for a question: the microphone is on. */
    LISTENING("S5", listOf(Action.MIC_ON)),

    /** Analysis of the surroundings with the question. */
    ASKING("S6", listOf(Action.ANALYZE_WITH_QUESTION)),

    /** Answer to the question. */
    ANSWERING("S7", listOf(Action.SPEAK)),
}
