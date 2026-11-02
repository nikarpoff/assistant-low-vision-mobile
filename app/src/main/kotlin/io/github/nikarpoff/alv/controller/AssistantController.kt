package io.github.nikarpoff.alv.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects the Model with the View (MOB-2).
 *
 * The scaffold only gives the screen its initial status; gestures, the session,
 * the camera and the microphone are wired in later.
 */
class AssistantController {
    private val mutableStatus = MutableStateFlow(Status.IDLE)

    /** Status shown on the screen. */
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** Status on the screen for low vision users (MOB-12). */
    enum class Status {
        IDLE,
        LISTENING,
        LOOKING,
        SPEAKING,
        OFFLINE,
    }
}
