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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateMachineTest {
    /** Machine brought into [state] by transitions of KM-2. */
    private fun machineIn(state: State, passiveMode: Boolean = state == PASSIVE): AssistantStateMachine {
        val machine = AssistantStateMachine(passiveMode)
        val path = when (state) {
            IDLE -> emptyList()
            PASSIVE -> listOf(Event.PassiveTimer)
            ANALYZING -> listOf(Event.Tap)
            SPEAKING -> listOf(Event.Tap, Event.DescriptionReady)
            LISTENING -> listOf(Event.Tap, Event.DescriptionReady, Event.PlaybackFinished)
            ASKING -> listOf(Event.Tap, Event.DescriptionReady, Event.PlaybackFinished, Event.QuestionAsked)
            ANSWERING -> listOf(Event.Tap, Event.DescriptionReady, Event.PlaybackFinished, Event.QuestionAsked, Event.AnswerReady)
        }
        path.forEach { machine.handle(it) }
        assertEquals(state, machine.state)
        return machine
    }

    private fun assertTransition(
        from: State,
        event: Event,
        id: String,
        to: State,
        actions: List<Action>,
        passiveMode: Boolean = from == PASSIVE,
    ): AssistantStateMachine.Result {
        val machine = machineIn(from, passiveMode)

        val result = machine.handle(event)

        assertEquals("$from ${event.id}", id, result.transition)
        assertEquals(from, result.from)
        assertEquals(to, result.to)
        assertEquals("$id actions", actions, result.actions)
        assertEquals(to, machine.state)
        assertEquals(machine.passiveMode, result.passiveMode)
        return result
    }

    @Test
    fun startsIdleWithPassiveModeOff() {
        val machine = AssistantStateMachine()

        assertEquals(IDLE, machine.state)
        assertFalse(machine.passiveMode)
        assertFalse(machine.passiveTimerActive)
    }

    @Test
    fun t1PassiveTimerTakesPhoto() {
        assertTransition(IDLE, Event.PassiveTimer, "T1", PASSIVE, listOf(TAKE_PHOTO), passiveMode = true)
    }

    @Test
    fun t2TapStartsAnalysis() {
        assertTransition(IDLE, Event.Tap, "T2", ANALYZING, listOf(CONFIRM_GESTURE, TAKE_PHOTO))
    }

    @Test
    fun t3HazardStartsWarning() {
        assertTransition(PASSIVE, Event.HazardFound, "T3", SPEAKING, listOf(COMPOSE_DESCRIPTION))
    }

    @Test
    fun t4DescriptionReadyStartsSpeech() {
        assertTransition(ANALYZING, Event.DescriptionReady, "T4", SPEAKING, listOf(COMPOSE_DESCRIPTION))
    }

    @Test
    fun t5TapCancelsPassiveRequest() {
        assertTransition(PASSIVE, Event.Tap, "T5", ANALYZING, listOf(CONFIRM_GESTURE, CANCEL_PASSIVE, TAKE_PHOTO))
    }

    @Test
    fun t6PassiveClearReturnsToIdle() {
        assertTransition(PASSIVE, Event.PassiveClear, "T6", IDLE, emptyList())
    }

    @Test
    fun t7PlaybackFinishedOpensListenWindow() {
        assertTransition(SPEAKING, Event.PlaybackFinished, "T7", LISTENING, listOf(MIC_ON))
    }

    @Test
    fun t8TapInListenWindowStartsAnalysis() {
        assertTransition(LISTENING, Event.Tap, "T8", ANALYZING, listOf(CONFIRM_GESTURE, MIC_OFF, TAKE_PHOTO))
    }

    @Test
    fun t9QuestionAskedSendsQuestion() {
        assertTransition(LISTENING, Event.QuestionAsked, "T9", ASKING, listOf(MIC_OFF, TAKE_PHOTO))
    }

    @Test
    fun t10AnswerReadyStartsAnswer() {
        assertTransition(ASKING, Event.AnswerReady, "T10", ANSWERING, listOf(COMPOSE_DESCRIPTION))
    }

    @Test
    fun t11AnswerPlayedOpensListenWindow() {
        assertTransition(ANSWERING, Event.PlaybackFinished, "T11", LISTENING, listOf(MIC_ON))
    }

    @Test
    fun t12ListenTimeoutReturnsToIdle() {
        assertTransition(LISTENING, Event.ListenTimeout, "T12", IDLE, listOf(MIC_OFF))
    }

    @Test
    fun t13HoldInIdleStartsRecording() {
        assertTransition(IDLE, Event.HoldStarted, "T13", LISTENING, listOf(CONFIRM_GESTURE, MIC_ON_HOLD))
    }

    @Test
    fun t14HoldInListenWindowSwitchesToHoldRecording() {
        assertTransition(LISTENING, Event.HoldStarted, "T14", LISTENING, listOf(CONFIRM_GESTURE, RECORD_HOLD))
    }

    @Test
    fun t15HoldWithoutSpeechReturnsToIdle() {
        assertTransition(LISTENING, Event.HoldEndedWithoutSpeech, "T15", IDLE, listOf(CONFIRM_GESTURE, MIC_OFF))
    }

    @Test
    fun t16TapInterruptsAndStartsNewAnalysis() {
        for (from in listOf(ANALYZING, SPEAKING, ASKING, ANSWERING)) {
            assertTransition(from, Event.Tap, "T16", ANALYZING, listOf(CONFIRM_GESTURE, INTERRUPT, TAKE_PHOTO))
        }
    }

    @Test
    fun t17DoubleTapRepeatsLastAnswer() {
        assertTransition(IDLE, Event.DoubleTap, "T17", SPEAKING, listOf(CONFIRM_GESTURE, REPEAT_LAST))
        assertTransition(LISTENING, Event.DoubleTap, "T17", SPEAKING, listOf(CONFIRM_GESTURE, MIC_OFF, REPEAT_LAST))
    }

    @Test
    fun t18FailureReportsErrorAndReturnsToIdle() {
        for (from in listOf(PASSIVE, ANALYZING, SPEAKING, ASKING, ANSWERING)) {
            assertTransition(from, Event.Failure, "T18", IDLE, listOf(REPORT_ERROR))
        }
    }

    @Test
    fun t19StopInterruptsSpeech() {
        for (from in listOf(SPEAKING, ANSWERING)) {
            assertTransition(from, Event.Stop, "T19", IDLE, listOf(CONFIRM_GESTURE, INTERRUPT))
        }
    }

    @Test
    fun t20SwipeUpTogglesPassiveMode() {
        val on = assertTransition(IDLE, Event.SwipeUp, "T20", IDLE, listOf(CONFIRM_GESTURE, SET_PASSIVE_MODE), passiveMode = false)
        assertTrue(on.passiveMode)

        val off = assertTransition(IDLE, Event.SwipeUp, "T20", IDLE, listOf(CONFIRM_GESTURE, SET_PASSIVE_MODE), passiveMode = true)
        assertFalse(off.passiveMode)

        val fromPassive = assertTransition(PASSIVE, Event.SwipeUp, "T20", IDLE, listOf(CONFIRM_GESTURE, CANCEL_PASSIVE, SET_PASSIVE_MODE))
        assertFalse(fromPassive.passiveMode)
    }

    @Test
    fun t21AppCommandsAfterQuestion() {
        assertTransition(ASKING, Event.AppCommand(Event.Command.REPEAT), "T21", SPEAKING, listOf(REPEAT_LAST))
        assertTransition(ASKING, Event.AppCommand(Event.Command.STOP), "T21", IDLE, emptyList())

        val on = assertTransition(ASKING, Event.AppCommand(Event.Command.PASSIVE_ON), "T21", IDLE, listOf(SET_PASSIVE_MODE))
        assertTrue(on.passiveMode)
        val stillOn = assertTransition(
            ASKING,
            Event.AppCommand(Event.Command.PASSIVE_ON),
            "T21",
            IDLE,
            listOf(SET_PASSIVE_MODE),
            passiveMode = true,
        )
        assertTrue(stillOn.passiveMode)

        val off = assertTransition(
            ASKING,
            Event.AppCommand(Event.Command.PASSIVE_OFF),
            "T21",
            IDLE,
            listOf(SET_PASSIVE_MODE),
            passiveMode = true,
        )
        assertFalse(off.passiveMode)
    }

    /** Every pair "state - event" of KM-2 and its transition; the rest are ignored. */
    private val table: Map<Pair<State, String>, String> = buildMap {
        put(IDLE to "E1", "T1")
        put(IDLE to "E2", "T2")
        put(PASSIVE to "E3", "T3")
        put(ANALYZING to "E4", "T4")
        put(PASSIVE to "E2", "T5")
        put(PASSIVE to "E5", "T6")
        put(SPEAKING to "E6", "T7")
        put(LISTENING to "E2", "T8")
        put(LISTENING to "E7", "T9")
        put(ASKING to "E8", "T10")
        put(ANSWERING to "E6", "T11")
        put(LISTENING to "E9", "T12")
        put(IDLE to "E10", "T13")
        put(LISTENING to "E10", "T14")
        put(LISTENING to "E11", "T15")
        listOf(ANALYZING, SPEAKING, ASKING, ANSWERING).forEach { put(it to "E2", "T16") }
        listOf(IDLE, LISTENING).forEach { put(it to "E12", "T17") }
        listOf(PASSIVE, ANALYZING, SPEAKING, ASKING, ANSWERING).forEach { put(it to "E13", "T18") }
        listOf(SPEAKING, ANSWERING).forEach { put(it to "E15", "T19") }
        listOf(IDLE, PASSIVE).forEach { put(it to "E14", "T20") }
        put(ASKING to "E16", "T21")
    }

    private val allEvents: List<Event> = listOf(
        Event.PassiveTimer,
        Event.Tap,
        Event.HazardFound,
        Event.DescriptionReady,
        Event.PassiveClear,
        Event.PlaybackFinished,
        Event.QuestionAsked,
        Event.AnswerReady,
        Event.ListenTimeout,
        Event.HoldStarted,
        Event.HoldEndedWithoutSpeech,
        Event.DoubleTap,
        Event.Failure,
        Event.SwipeUp,
        Event.Stop,
    ) + Event.Command.entries.map { Event.AppCommand(it) }

    @Test
    fun everyPairFollowsTheTable() {
        for (state in State.entries) {
            for (event in allEvents) {
                val machine = machineIn(state, passiveMode = true)

                val result = machine.handle(event)

                assertEquals("${state.id} ${event.id}", table[state to event.id], result.transition)
            }
        }
    }

    @Test
    fun ignoredGestureGivesUnavailableSignal() {
        val gestures = setOf("E2", "E7", "E10", "E11", "E12", "E14", "E15")
        var ignored = 0
        for (state in State.entries) {
            for (event in allEvents) {
                if (table.containsKey(state to event.id)) continue
                val machine = machineIn(state, passiveMode = true)

                val result = machine.handle(event)

                assertTrue(result.ignored)
                assertEquals(state, result.to)
                assertEquals(state, machine.state)
                assertTrue(machine.passiveMode)
                val expected = if (event.id in gestures) listOf(SIGNAL_UNAVAILABLE) else emptyList()
                assertEquals("${state.id} ${event.id}", expected, result.actions)
                assertEquals(event.id in gestures, event.gesture)
                ignored++
            }
        }
        assertTrue(ignored > 0)
    }

    @Test
    fun passiveModeWorksOnlyInIdle() {
        val idleOff = AssistantStateMachine(passiveMode = false)
        assertFalse(idleOff.passiveTimerActive)
        val offResult = idleOff.handle(Event.PassiveTimer)
        assertNull(offResult.transition)
        assertEquals(emptyList<Action>(), offResult.actions)
        assertEquals(IDLE, idleOff.state)

        assertTrue(machineIn(IDLE, passiveMode = true).passiveTimerActive)
        for (state in State.entries.filter { it != IDLE }) {
            val machine = machineIn(state, passiveMode = true)
            assertFalse(state.id, machine.passiveTimerActive)

            val result = machine.handle(Event.PassiveTimer)

            assertNull(state.id, result.transition)
            assertFalse(state.id, TAKE_PHOTO in result.actions)
            assertEquals(state, machine.state)
        }
    }

    @Test
    fun passiveCycleReturnsToTimer() {
        val machine = AssistantStateMachine(passiveMode = true)

        machine.handle(Event.PassiveTimer)
        assertFalse(machine.passiveTimerActive)
        machine.handle(Event.PassiveClear)

        assertEquals(IDLE, machine.state)
        assertTrue(machine.passiveTimerActive)
    }

    @Test
    fun statesHaveActivitiesOfKm2() {
        assertEquals(emptyList<Action>(), IDLE.activities)
        assertEquals(listOf(Action.FIND_HAZARDS), PASSIVE.activities)
        assertEquals(listOf(Action.ANALYZE_SCENE), ANALYZING.activities)
        assertEquals(listOf(Action.SPEAK), SPEAKING.activities)
        assertEquals(listOf(MIC_ON), LISTENING.activities)
        assertEquals(listOf(Action.ANALYZE_WITH_QUESTION), ASKING.activities)
        assertEquals(listOf(Action.SPEAK), ANSWERING.activities)
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5", "S6", "S7"), State.entries.map { it.id })
        assertTrue(COMPOSE_DESCRIPTION.onServer)
        assertFalse(TAKE_PHOTO.onServer)
    }
}
