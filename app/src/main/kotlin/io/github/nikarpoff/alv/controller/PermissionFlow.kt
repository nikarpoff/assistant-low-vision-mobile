package io.github.nikarpoff.alv.controller

/**
 * Sequence «explanation → request → result» for the camera and the microphone (MOB-15).
 *
 * The Android layer performs each [Step] and reports back: the explanation phrase
 * sounds before the system dialog, and a refusal is followed by the phrase on how
 * to grant the permission in the system settings. Permissions already granted are
 * not asked for again.
 *
 * Steps:
 * 1. [start] with the granted permissions: [Step.Ready] when all are granted, else [Step.Explain];
 * 2. [onExplained]: [Step.Request] with the missing permissions;
 * 3. [onResult]: [Step.Ready] or [Step.ExplainDenied];
 * 4. [onDeniedExplained]: [Step.Blocked].
 */
class PermissionFlow {
    /** Permission the assistant needs. */
    enum class Permission { CAMERA, MICROPHONE }

    /** What the Android layer does next. */
    sealed interface Step {
        /** Play the explanation phrase, then call [onExplained]. */
        data object Explain : Step

        /** Show the system dialog for [permissions], then call [onResult]. */
        data class Request(val permissions: Set<Permission>) : Step

        /** Play the phrase on how to grant [missing] in the settings, then call [onDeniedExplained]. */
        data class ExplainDenied(val missing: Set<Permission>) : Step

        /** Everything is granted: the assistant can work. */
        data object Ready : Step

        /** Something is still refused: the assistant cannot work until it is granted. */
        data class Blocked(val missing: Set<Permission>) : Step
    }

    /** Current step; `null` before [start]. */
    var step: Step? = null
        private set

    private var missing: Set<Permission> = emptySet()

    /** Starts the flow with the permissions already [granted]. */
    fun start(granted: Set<Permission>): Step {
        missing = REQUIRED - granted
        return go(if (missing.isEmpty()) Step.Ready else Step.Explain)
    }

    /** The explanation phrase finished. */
    fun onExplained(): Step {
        check(step == Step.Explain) { "explanation finished at step $step" }
        return go(Step.Request(missing))
    }

    /** The system dialog returned the [granted] permissions. */
    fun onResult(granted: Set<Permission>): Step {
        check(step is Step.Request) { "permission result at step $step" }
        missing -= granted
        return go(if (missing.isEmpty()) Step.Ready else Step.ExplainDenied(missing))
    }

    /** The phrase on how to grant the permission finished. */
    fun onDeniedExplained(): Step {
        check(step is Step.ExplainDenied) { "refusal explanation finished at step $step" }
        return go(Step.Blocked(missing))
    }

    private fun go(next: Step): Step {
        step = next
        return next
    }

    companion object {
        /** Permissions the assistant needs. */
        val REQUIRED: Set<Permission> = setOf(Permission.CAMERA, Permission.MICROPHONE)
    }
}
