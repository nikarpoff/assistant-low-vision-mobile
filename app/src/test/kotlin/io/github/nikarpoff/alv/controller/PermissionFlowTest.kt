package io.github.nikarpoff.alv.controller

import io.github.nikarpoff.alv.controller.PermissionFlow.Permission.CAMERA
import io.github.nikarpoff.alv.controller.PermissionFlow.Permission.MICROPHONE
import io.github.nikarpoff.alv.controller.PermissionFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PermissionFlowTest {
    private val flow = PermissionFlow()

    @Test
    fun explanationSoundsBeforeRequest() {
        assertNull(flow.step)

        assertEquals(Step.Explain, flow.start(granted = emptySet()))
        assertEquals(Step.Request(setOf(CAMERA, MICROPHONE)), flow.onExplained())
        assertEquals(Step.Ready, flow.onResult(granted = setOf(CAMERA, MICROPHONE)))
    }

    @Test
    fun grantedPermissionsAreNotAskedAgain() {
        assertEquals(Step.Ready, flow.start(granted = setOf(CAMERA, MICROPHONE)))
    }

    @Test
    fun onlyMissingPermissionIsRequested() {
        flow.start(granted = setOf(CAMERA))

        assertEquals(Step.Request(setOf(MICROPHONE)), flow.onExplained())
    }

    @Test
    fun refusalIsFollowedByPhraseOnSettings() {
        flow.start(granted = emptySet())
        flow.onExplained()

        assertEquals(Step.ExplainDenied(setOf(MICROPHONE)), flow.onResult(granted = setOf(CAMERA)))
        assertEquals(Step.Blocked(setOf(MICROPHONE)), flow.onDeniedExplained())
    }

    @Test
    fun fullRefusal() {
        flow.start(granted = emptySet())
        flow.onExplained()

        assertEquals(Step.ExplainDenied(setOf(CAMERA, MICROPHONE)), flow.onResult(granted = emptySet()))
    }

    @Test
    fun nextLaunchStartsOverWithExplanation() {
        flow.start(granted = emptySet())
        flow.onExplained()
        flow.onResult(granted = emptySet())
        flow.onDeniedExplained()

        assertEquals(Step.Explain, flow.start(granted = emptySet()))
    }

    @Test
    fun requestWithoutExplanationIsNotAllowed() {
        assertThrows(IllegalStateException::class.java) { flow.onResult(granted = emptySet()) }
        flow.start(granted = emptySet())
        assertThrows(IllegalStateException::class.java) { flow.onResult(granted = emptySet()) }
        assertThrows(IllegalStateException::class.java) { flow.onDeniedExplained() }
    }
}
