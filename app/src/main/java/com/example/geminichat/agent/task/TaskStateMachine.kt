package com.example.geminichat.agent.task

/**
 * Result of a [TaskStateMachine] operation: either the transition was legal and produced a new
 * [TaskState], or it was rejected with a human-readable [reason] — never a silent no-op and
 * never an exception, so the UI/[com.example.geminichat.ChatViewModel] can surface *why* a
 * button did nothing (see [com.example.geminichat.ChatUiState.errorMessage]).
 */
sealed class TransitionResult {
    data class Applied(val state: TaskState, val event: TaskEvent? = null) : TransitionResult()
    data class Rejected(val reason: String, val rejection: TransitionRejection? = null) : TransitionResult()
}

/**
 * Day 13 & 15: the only code allowed to change a [TaskState] — delegates legality and guards
 * to [TaskTransitionTable] instead of scattered `if` checks.
 *
 * Stage graph:
 * ```
 * PLANNING ----(approvePlan)--------> EXECUTION
 * PLANNING ----(cancel)-------------> CANCELLED
 * EXECUTION ---(requestValidation)--> VALIDATION
 * EXECUTION ---(cancel)-------------> CANCELLED
 * VALIDATION --(sendBackToExecution)-> EXECUTION
 * VALIDATION --(recordValidation)---> VALIDATION
 * VALIDATION --(complete, PASSED)---> DONE
 * VALIDATION --(cancel)-------------> CANCELLED
 * ```
 * [TaskStage.DONE] and [TaskStage.CANCELLED] are terminal.
 * [TaskState.paused] is a flag layered on top of any non-terminal stage.
 */
object TaskStateMachine {

    /** Starts a brand new task in [TaskStage.PLANNING], replacing whatever task was active. */
    fun start(title: String, now: Long = System.currentTimeMillis()): TransitionResult {
        if (title.isBlank()) {
            return TransitionResult.Rejected(
                "Название задачи не может быть пустым.",
                TransitionRejection(TaskEvent.START, TaskStage.PLANNING, "Название пустое", "Введите название задачи")
            )
        }
        return TransitionResult.Applied(
            TaskState(
                title = title.trim(),
                stage = TaskStage.PLANNING,
                expectedAction = "предложить план",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.START
        )
    }

    /**
     * [TaskStage.PLANNING] -> [TaskStage.EXECUTION]: fixes [steps] as the approved plan and
     * moves to its first step.
     */
    fun approvePlan(
        state: TaskState,
        steps: List<String>,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.APPROVE_PLAN, steps)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.EXECUTION,
                steps = cleanSteps,
                currentStepIndex = 0,
                planApproved = true,
                validationOutcome = ValidationOutcome.NOT_RUN,
                expectedAction = "работа над: ${cleanSteps.first()}",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.APPROVE_PLAN
        )
    }

    /** Moves to the next step within [TaskStage.EXECUTION]. */
    fun nextStep(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.NEXT_STEP)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        val next = state.currentStepIndex + 1
        return TransitionResult.Applied(
            state.copy(
                currentStepIndex = next,
                expectedAction = "работа над: ${state.steps[next]}",
                updatedAt = now
            ),
            TaskEvent.NEXT_STEP
        )
    }

    /** Moves back to the previous step within [TaskStage.EXECUTION]. */
    fun previousStep(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.PREVIOUS_STEP)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        val prev = state.currentStepIndex - 1
        return TransitionResult.Applied(
            state.copy(
                currentStepIndex = prev,
                expectedAction = "работа над: ${state.steps[prev]}",
                updatedAt = now
            ),
            TaskEvent.PREVIOUS_STEP
        )
    }

    /** [TaskStage.EXECUTION] -> [TaskStage.VALIDATION]: all steps are done, check the result. */
    fun requestValidation(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.REQUEST_VALIDATION)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.VALIDATION,
                validationOutcome = ValidationOutcome.NOT_RUN,
                validationNote = "",
                expectedAction = "проверить соответствие результата цели",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.REQUEST_VALIDATION
        )
    }

    /** Records the outcome of validation while in [TaskStage.VALIDATION]. */
    fun recordValidation(
        state: TaskState,
        outcome: ValidationOutcome,
        note: String = "",
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.RECORD_VALIDATION)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        val expected = when (outcome) {
            ValidationOutcome.PASSED -> "завершить задачу (валидация пройдена)"
            ValidationOutcome.FAILED -> "отправить на доработку (найдены замечания)"
            ValidationOutcome.NOT_RUN -> "проверить соответствие результата цели"
        }
        return TransitionResult.Applied(
            state.copy(
                validationOutcome = outcome,
                validationNote = note.trim(),
                expectedAction = expected,
                expectedActor = ExpectedActor.USER,
                updatedAt = now
            ),
            TaskEvent.RECORD_VALIDATION
        )
    }

    /** [TaskStage.VALIDATION] -> [TaskStage.EXECUTION]: validation found the work isn't done yet. */
    fun sendBackToExecution(
        state: TaskState,
        reason: String,
        backToStepIndex: Int? = null,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.SEND_BACK_TO_EXECUTION)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        val targetIndex = backToStepIndex ?: state.currentStepIndex.coerceIn(0, state.steps.lastIndex.coerceAtLeast(0))
        if (targetIndex !in state.steps.indices) {
            return TransitionResult.Rejected(
                "Индекс шага $targetIndex выходит за границы (${state.steps.size} шагов).",
                TransitionRejection(TaskEvent.SEND_BACK_TO_EXECUTION, state.stage, "Неверный шаг", "Выберите существующий шаг")
            )
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.EXECUTION,
                currentStepIndex = targetIndex,
                validationOutcome = ValidationOutcome.NOT_RUN,
                expectedAction = "доработка: ${state.steps[targetIndex]} ($reason)",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.SEND_BACK_TO_EXECUTION
        )
    }

    /**
     * [TaskStage.VALIDATION] -> [TaskStage.DONE]: task is finished and passed validation.
     */
    fun complete(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.COMPLETE)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.DONE,
                expectedAction = "",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.COMPLETE
        )
    }

    /** Cancels an active task from any non-terminal stage. */
    fun cancel(state: TaskState, reason: String = "", now: Long = System.currentTimeMillis()): TransitionResult {
        val rejection = TaskTransitionTable.check(state, TaskEvent.CANCEL)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        return TransitionResult.Applied(
            state.copy(
                stage = TaskStage.CANCELLED,
                cancellationReason = reason.trim(),
                expectedAction = "",
                expectedActor = ExpectedActor.AGENT,
                stageEnteredAt = now,
                updatedAt = now
            ),
            TaskEvent.CANCEL
        )
    }

    /** Freezes the task where it is. */
    fun pause(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        if (state.paused) {
            return TransitionResult.Applied(state.copy(updatedAt = now), TaskEvent.PAUSE)
        }
        val rejection = TaskTransitionTable.check(state, TaskEvent.PAUSE)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        return TransitionResult.Applied(
            state.copy(paused = true, updatedAt = now),
            TaskEvent.PAUSE
        )
    }

    /** Lifts a pause. */
    fun resume(state: TaskState, now: Long = System.currentTimeMillis()): TransitionResult {
        if (!state.paused && state.isActive && !state.isTerminal) {
            return TransitionResult.Applied(state.copy(updatedAt = now), TaskEvent.RESUME)
        }
        val rejection = TaskTransitionTable.check(state, TaskEvent.RESUME)
        if (rejection != null) {
            return TransitionResult.Rejected(rejection.message, rejection)
        }
        return TransitionResult.Applied(
            state.copy(paused = false, updatedAt = now),
            TaskEvent.RESUME
        )
    }

    /** Overwrites expected action. */
    fun setExpectedAction(
        state: TaskState,
        action: String,
        actor: ExpectedActor,
        now: Long = System.currentTimeMillis()
    ): TransitionResult {
        if (!state.isActive) return TransitionResult.Rejected("Нет активной задачи.")
        if (state.paused) return TransitionResult.Rejected("Задача на паузе.")
        if (state.isTerminal) return TransitionResult.Rejected("Задача завершена.")
        return TransitionResult.Applied(
            state.copy(expectedAction = action, expectedActor = actor, updatedAt = now)
        )
    }

    /** Drops the task entirely, back to [TaskState.NONE]. */
    fun reset(): TransitionResult = TransitionResult.Applied(TaskState.NONE, TaskEvent.RESET)
}
