package com.example.geminichat.agent.task

/**
 * Result of a [TaskStateMachine] operation: either the transition was legal and produced a new
 * [TaskState], or it was rejected with a human-readable [reason] — never a silent no-op and
 * never an exception, so the UI/[com.example.geminichat.ChatViewModel] can surface *why* a
 * button did nothing (see [com.example.geminichat.ChatUiState.errorMessage]).
 */
sealed class TransitionResult {
    data class Applied(val state: TaskState) : TransitionResult()
    data class Rejected(val reason: String) : TransitionResult()
}

/**
 * Day 13: the only code allowed to change a [TaskState] — mirrors how [MemoryRouter]/UI actions
 * are the only writers of memory layers, here made stricter by an explicit transition table
 * instead of "whatever the caller sets". No transition here calls the LLM; that's left to the
 * optional [TaskStateAdvisor], which only *suggests* — applying a suggestion still goes through
 * this same machine.
 *
 * Stage graph (see [TaskStage] for what each stage means):
 * ```
 * PLANNING --> EXECUTION   (approvePlan)
 * PLANNING --> DONE        (complete, e.g. task cancelled/not needed)
 * EXECUTION --> VALIDATION (requestValidation)
 * VALIDATION --> EXECUTION (sendBackToExecution)
 * VALIDATION --> DONE      (complete)
 * ```
 * [TaskStage.DONE] is terminal: every operation on it is [TransitionResult.Rejected] except
 * [reset]. [TaskState.paused] is a flag layered on top of any non-terminal stage rather than a
 * stage of its own (see [TaskState.paused]'s doc for why); while `paused`, every mutating
 * operation is rejected except [resume] and [reset], so "pause, then resume" always yields back
 * exactly the state that was paused (bar [TaskState.updatedAt]).
 */
object TaskStateMachine {

    /** Starts a brand new task in [TaskStage.PLANNING], replacing whatever task was active. */
    fun start(title: String, now: Long = System.currentTimeMillis()): TransitionResult {
        if (title.isBlank()) return TransitionResult.Rejected("Task title must not be blank.")
        return TransitionResult.Applied(
            TaskState(
                title = title.trim(),
                stage = TaskStage.PLANNING,
                expectedAction = "propose a plan",
                expectedActor = ExpectedActor.AGENT,
                updatedAt = now
            )
        )
    }

    /**
     * [TaskStage.PLANNING] -> [TaskStage.EXECUTION]: fixes [steps] as the approved plan and
     * moves to its first step. Requires at least one step — an empty plan can't be executed.
     */
    fun approvePlan(
        state: TaskState,
        steps: List<String>,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.PLANNING) {
            return rejectedStage(state, "approve the plan", TaskStage.PLANNING)
        }
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanSteps.isEmpty()) {
            return TransitionResult.Rejected("A plan needs at least one step before it can be approved.")
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.EXECUTION,
                steps = cleanSteps,
                currentStepIndex = 0,
                expectedAction = "work on: ${cleanSteps.first()}",
                expectedActor = ExpectedActor.AGENT,
                updatedAt = now
            )
        )
    }

    /**
     * Moves to the next step within [TaskStage.EXECUTION]. Rejected past the last step — the
     * caller should [requestValidation] instead of silently wrapping around.
     */
    fun nextStep(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.EXECUTION) {
            return rejectedStage(state, "advance to the next step", TaskStage.EXECUTION)
        }
        val next = state.currentStepIndex + 1
        if (next >= state.steps.size) {
            return TransitionResult.Rejected(
                "Already on the last step (${state.progressLabel}); use \"Send to validation\" instead."
            )
        }
        return TransitionResult.Applied(
            state.copy(
                currentStepIndex = next,
                expectedAction = "work on: ${state.steps[next]}",
                updatedAt = now
            )
        )
    }

    /** Moves back to the previous step within [TaskStage.EXECUTION]. Rejected before step 0. */
    fun previousStep(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.EXECUTION) {
            return rejectedStage(state, "go back a step", TaskStage.EXECUTION)
        }
        val prev = state.currentStepIndex - 1
        if (prev < 0) {
            return TransitionResult.Rejected("Already on the first step (${state.progressLabel}).")
        }
        return TransitionResult.Applied(
            state.copy(
                currentStepIndex = prev,
                expectedAction = "work on: ${state.steps[prev]}",
                updatedAt = now
            )
        )
    }

    /** [TaskStage.EXECUTION] -> [TaskStage.VALIDATION]: all steps are done, check the result. */
    fun requestValidation(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.EXECUTION) {
            return rejectedStage(state, "send to validation", TaskStage.EXECUTION)
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.VALIDATION,
                expectedAction = "check the result against the goal",
                expectedActor = ExpectedActor.AGENT,
                updatedAt = now
            )
        )
    }

    /**
     * [TaskStage.VALIDATION] -> [TaskStage.EXECUTION]: validation found the work isn't done yet.
     * Stays on the current step by default; the caller can pass [backToStepIndex] to reopen an
     * earlier one instead (e.g. the step whose result failed validation).
     */
    fun sendBackToExecution(
        state: TaskState,
        reason: String,
        backToStepIndex: Int? = null,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.VALIDATION) {
            return rejectedStage(state, "send back to execution", TaskStage.VALIDATION)
        }
        val targetIndex = backToStepIndex ?: state.currentStepIndex.coerceIn(0, state.steps.lastIndex.coerceAtLeast(0))
        if (targetIndex !in state.steps.indices) {
            return TransitionResult.Rejected("Step index $targetIndex is out of range for ${state.steps.size} steps.")
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.EXECUTION,
                currentStepIndex = targetIndex,
                expectedAction = "rework: ${state.steps[targetIndex]} ($reason)",
                expectedActor = ExpectedActor.AGENT,
                updatedAt = now
            )
        )
    }

    /**
     * [TaskStage.VALIDATION] -> [TaskStage.DONE], or [TaskStage.PLANNING] -> [TaskStage.DONE]
     * (cancelling a task before it was ever executed). [TaskStage.EXECUTION] must go through
     * [requestValidation] first.
     */
    fun complete(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage != TaskStage.VALIDATION && state.stage != TaskStage.PLANNING) {
            return rejectedStage(state, "mark done", TaskStage.VALIDATION, TaskStage.PLANNING)
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.DONE,
                expectedAction = "",
                expectedActor = ExpectedActor.AGENT,
                updatedAt = now
            )
        )
    }

    /**
     * Freezes the task exactly where it is. Idempotent (pausing an already-paused task just
     * refreshes [TaskState.updatedAt]). Rejected on [TaskStage.DONE] — a finished task has
     * nothing left to pause.
     */
    fun pause(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        if (state.stage == TaskStage.DONE) {
            return TransitionResult.Rejected("The task is already done; there is nothing to pause.")
        }
        return TransitionResult.Applied(state.copy(paused = true, updatedAt = now))
    }

    /**
     * Lifts a pause, returning to exactly the stage/step/expected-action that was paused —
     * this is the "continue without re-explaining" requirement made concrete. Idempotent
     * (resuming a task that isn't paused is a no-op transition, not an error).
     */
    fun resume(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult =
        TransitionResult.Applied(state.copy(paused = false, updatedAt = now))

    /** Overwrites who's expected to act next and what for — used after the agent replies. */
    fun setExpectedAction(
        state: TaskState,
        action: String,
        actor: ExpectedActor,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        rejectIfPaused(state)?.let { return it }
        if (state.stage == TaskStage.DONE) {
            return TransitionResult.Rejected("The task is done; there is no next action to set.")
        }
        return TransitionResult.Applied(state.copy(expectedAction = action, expectedActor = actor, updatedAt = now))
    }

    /** Drops the task entirely, back to [TaskState.NONE] — always legal, from any stage. */
    fun reset(): TransitionResult = TransitionResult.Applied(TaskState.NONE)

    private fun rejectIfPaused(state: TaskState): TransitionResult.Rejected? =
        if (state.paused) {
            TransitionResult.Rejected("The task is paused; resume it first.")
        } else {
            null
        }

    private fun rejectedStage(state: TaskState, action: String, vararg allowed: TaskStage): TransitionResult.Rejected =
        TransitionResult.Rejected(
            "Cannot $action from stage ${state.stage} (requires ${allowed.joinToString(" or ")})."
        )
}
