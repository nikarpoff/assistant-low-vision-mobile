package io.github.nikarpoff.alv.model

import io.github.nikarpoff.alv.model.Action.CANCEL_PASSIVE
import io.github.nikarpoff.alv.model.Action.COMPOSE_DESCRIPTION
import io.github.nikarpoff.alv.model.Action.CONFIRM_GESTURE
import io.github.nikarpoff.alv.model.Action.INTERRUPT
import io.github.nikarpoff.alv.model.Action.MIC_OFF
import io.github.nikarpoff.alv.model.Action.MIC_ON
import io.github.nikarpoff.alv.model.Action.MIC_ON_HOLD
import io.github.nikarpoff.alv.model.Action.RECORD_HOLD
import io.github.nikarpoff.alv.model.Action.REPEAT_LAST
import io.github.nikarpoff.alv.model.Action.REPORT_ERROR
import io.github.nikarpoff.alv.model.Action.SET_PASSIVE_MODE
import io.github.nikarpoff.alv.model.Action.SIGNAL_UNAVAILABLE
import io.github.nikarpoff.alv.model.Action.TAKE_PHOTO
import io.github.nikarpoff.alv.model.State.ANALYZING
import io.github.nikarpoff.alv.model.State.ANSWERING
import io.github.nikarpoff.alv.model.State.ASKING
import io.github.nikarpoff.alv.model.State.IDLE
import io.github.nikarpoff.alv.model.State.LISTENING
import io.github.nikarpoff.alv.model.State.PASSIVE
import io.github.nikarpoff.alv.model.State.SPEAKING

/**
 * Assistant (KM-1) as the state machine of KM-2: states S1-S7, events E1-E16, transitions T1-T21.
 *
 * The machine only decides: [handle] returns the transition and the actions, the controllers perform them.
 * A pair "state - event" absent from KM-2 is ignored; an ignored gesture gives the "unavailable" signal.
 * The passive mode works only in S1: E1 is ignored in every other state and while the mode is off.
 *
 * @param passiveMode passive mode on start, the last choice of the user (MOB-14)
 */
class AssistantStateMachine(passiveMode: Boolean = false) {
    /** Current state. */
    var state: State = IDLE
        private set

    /** Passive mode; switched by A12. */
    var passiveMode: Boolean = passiveMode
        private set

    /** The 3 s timer of the passive mode runs: the state is S1 and the passive mode is on. */
    val passiveTimerActive: Boolean
        get() = state == IDLE && passiveMode

    /**
     * Result of an event.
     *
     * @property transition number of the transition, `null` when the event is ignored
     * @property from state before the event
     * @property to state after the event
     * @property actions actions of the transition in order
     * @property passiveMode passive mode after the event
     */
    data class Result(
        val transition: String?,
        val from: State,
        val to: State,
        val actions: List<Action>,
        val passiveMode: Boolean,
    ) {
        /** The event was ignored. */
        val ignored: Boolean get() = transition == null
    }

    /** Applies the event and returns the transition with its actions. */
    @Synchronized
    fun handle(event: Event): Result {
        val from = state
        val step = transition(from, event)
        if (step == null) {
            val actions = if (event.gesture) listOf(SIGNAL_UNAVAILABLE) else emptyList()
            return Result(null, from, from, actions, passiveMode)
        }

        if (SET_PASSIVE_MODE in step.actions) {
            passiveMode = step.passiveMode ?: !passiveMode
        }
        state = step.to
        return Result(step.id, from, step.to, step.actions, passiveMode)
    }

    /** Transition of KM-2; [passiveMode] is the new mode of A12, `null` to toggle it. */
    private class Step(val id: String, val to: State, val actions: List<Action>, val passiveMode: Boolean? = null)

    private fun transition(from: State, event: Event): Step? =
        when (event) {
            Event.PassiveTimer -> if (from == IDLE && passiveMode) Step("T1", PASSIVE, listOf(TAKE_PHOTO)) else null
            Event.Tap -> tap(from)
            Event.HazardFound -> if (from == PASSIVE) Step("T3", SPEAKING, listOf(COMPOSE_DESCRIPTION)) else null
            Event.DescriptionReady -> if (from == ANALYZING) Step("T4", SPEAKING, listOf(COMPOSE_DESCRIPTION)) else null
            Event.PassiveClear -> if (from == PASSIVE) Step("T6", IDLE, emptyList()) else null
            Event.PlaybackFinished -> playbackFinished(from)
            Event.QuestionAsked -> if (from == LISTENING) Step("T9", ASKING, listOf(MIC_OFF, TAKE_PHOTO)) else null
            Event.AnswerReady -> if (from == ASKING) Step("T10", ANSWERING, listOf(COMPOSE_DESCRIPTION)) else null
            Event.ListenTimeout -> if (from == LISTENING) Step("T12", IDLE, listOf(MIC_OFF)) else null
            Event.HoldStarted -> holdStarted(from)
            Event.HoldEndedWithoutSpeech ->
                if (from == LISTENING) Step("T15", IDLE, listOf(CONFIRM_GESTURE, MIC_OFF)) else null
            Event.DoubleTap -> doubleTap(from)
            Event.Failure -> if (from in FAILING_STATES) Step("T18", IDLE, listOf(REPORT_ERROR)) else null
            Event.SwipeUp -> swipeUp(from)
            Event.Stop -> if (from == SPEAKING || from == ANSWERING) Step("T19", IDLE, listOf(CONFIRM_GESTURE, INTERRUPT)) else null
            is Event.AppCommand -> if (from == ASKING) command(event.command) else null
        }

    private fun tap(from: State): Step? =
        when (from) {
            IDLE -> Step("T2", ANALYZING, listOf(CONFIRM_GESTURE, TAKE_PHOTO))
            PASSIVE -> Step("T5", ANALYZING, listOf(CONFIRM_GESTURE, CANCEL_PASSIVE, TAKE_PHOTO))
            LISTENING -> Step("T8", ANALYZING, listOf(CONFIRM_GESTURE, MIC_OFF, TAKE_PHOTO))
            ANALYZING, SPEAKING, ASKING, ANSWERING -> Step("T16", ANALYZING, listOf(CONFIRM_GESTURE, INTERRUPT, TAKE_PHOTO))
        }

    private fun playbackFinished(from: State): Step? =
        when (from) {
            SPEAKING -> Step("T7", LISTENING, listOf(MIC_ON))
            ANSWERING -> Step("T11", LISTENING, listOf(MIC_ON))
            else -> null
        }

    private fun holdStarted(from: State): Step? =
        when (from) {
            IDLE -> Step("T13", LISTENING, listOf(CONFIRM_GESTURE, MIC_ON_HOLD))
            LISTENING -> Step("T14", LISTENING, listOf(CONFIRM_GESTURE, RECORD_HOLD))
            else -> null
        }

    private fun doubleTap(from: State): Step? =
        when (from) {
            IDLE -> Step("T17", SPEAKING, listOf(CONFIRM_GESTURE, REPEAT_LAST))
            LISTENING -> Step("T17", SPEAKING, listOf(CONFIRM_GESTURE, MIC_OFF, REPEAT_LAST))
            else -> null
        }

    private fun swipeUp(from: State): Step? =
        when (from) {
            IDLE -> Step("T20", IDLE, listOf(CONFIRM_GESTURE, SET_PASSIVE_MODE))
            PASSIVE -> Step("T20", IDLE, listOf(CONFIRM_GESTURE, CANCEL_PASSIVE, SET_PASSIVE_MODE))
            else -> null
        }

    private fun command(command: Event.Command): Step =
        when (command) {
            Event.Command.REPEAT -> Step("T21", SPEAKING, listOf(REPEAT_LAST))
            Event.Command.STOP -> Step("T21", IDLE, emptyList())
            Event.Command.PASSIVE_ON -> Step("T21", IDLE, listOf(SET_PASSIVE_MODE), passiveMode = true)
            Event.Command.PASSIVE_OFF -> Step("T21", IDLE, listOf(SET_PASSIVE_MODE), passiveMode = false)
        }

    private companion object {
        /** States E13 leaves for S1 (T18). */
        val FAILING_STATES = setOf(PASSIVE, ANALYZING, SPEAKING, ASKING, ANSWERING)
    }
}
