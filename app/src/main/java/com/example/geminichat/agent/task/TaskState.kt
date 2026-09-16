package com.example.geminichat.agent.task

import kotlinx.serialization.Serializable

/**
 * Day 13: the stage of the task lifecycle a [TaskState] is currently in.
 *
 * - [PLANNING]: the goal and steps are being worked out; nothing is being executed yet.
 * - [EXECUTION]: the plan is approved; work proceeds step by step ([TaskState.currentStepIndex]).
 * - [VALIDATION]: the result of execution is being checked against the goal — no new work is
 *   started here, only "does this satisfy the plan?".
 * - [DONE]: terminal — the task is finished. [TaskStateMachine] allows no transitions out of it.
 *
 * See [TaskStateMachine] for the allowed transition table between these stages.
 */
enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE }

/**
 * Whose turn it is to act next, so the agent doesn't guess a result the user hasn't provided
 * yet, and the user isn't left waiting on a step only the agent can move forward — see
 * [TaskState.expectedAction].
 */
enum class ExpectedActor { USER, AGENT }

/**
 * Day 13: the formalized state of a single task the agent and user are working through
 * together — *where* the task is, not *what* is known about it (that's
 * [com.example.geminichat.agent.memory.MemoryLayer.WORKING], Day 11). Persisted per branch by
 * [TaskStateStore], mutated only through [TaskStateMachine] (never edited in place), and
 * rendered into the prompt by [TaskStateRenderer].
 *
 * @property title short human name for the task, e.g. "10km race in 6 weeks".
 * @property stage current [TaskStage].
 * @property steps the plan's steps, fixed once [TaskStage.PLANNING] is approved (see
 *   [TaskStateMachine.approvePlan]). Empty while still in [TaskStage.PLANNING].
 * @property currentStepIndex 0-based index into [steps]; `-1` when there is no current step
 *   (no steps yet, or the task hasn't reached [TaskStage.EXECUTION]).
 * @property expectedAction free-text description of what happens next, e.g. "send the pulse
 *   readings from the last three runs".
 * @property expectedActor who is expected to perform [expectedAction] — see [ExpectedActor].
 * @property paused **not** a stage of its own — a flag layered on top of whichever [stage] the
 *   task was in when paused, so resuming returns to exactly that stage and step instead of
 *   losing track of "where do we go back to" (see [TaskStateMachine.pause]/[resume]).
 * @property updatedAt epoch millis of the last mutation, for "updated N ago" in the UI.
 */
@Serializable
data class TaskState(
    val title: String = "",
    val stage: TaskStage = TaskStage.PLANNING,
    val steps: List<String> = emptyList(),
    val currentStepIndex: Int = -1,
    val expectedAction: String = "",
    val expectedActor: ExpectedActor = ExpectedActor.AGENT,
    val paused: Boolean = false,
    val updatedAt: Long = 0L
) {
    /** `true` once a task has actually been started (i.e. this isn't [NONE]). */
    val isActive: Boolean get() = this != NONE

    /** The step text at [currentStepIndex], or `null` if there is none. */
    val currentStep: String? get() = steps.getOrNull(currentStepIndex)

    /** Human "Step 2 of 4" label, or `null` when there are no steps (e.g. still [TaskStage.PLANNING]). */
    val progressLabel: String?
        get() = if (steps.isEmpty()) null else "${currentStepIndex + 1} of ${steps.size}"

    companion object {
        /**
         * No task has been started (or the last one was ended/reset). [TaskStateRenderer]
         * renders this as an empty string, so the app behaves exactly as it did before Day 13
         * when nothing is in flight — mirroring [com.example.geminichat.agent.profile.UserProfile.EMPTY].
         */
        val NONE = TaskState()
    }
}
