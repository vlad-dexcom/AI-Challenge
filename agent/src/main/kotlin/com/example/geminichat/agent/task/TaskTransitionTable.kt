package com.example.geminichat.agent.task

/**
 * Day 15: explicit events that can cause a transition in the task lifecycle.
 */
enum class TaskEvent(val displayName: String) {
    START("Начать задачу"),
    APPROVE_PLAN("Утвердить план"),
    NEXT_STEP("Следующий шаг"),
    PREVIOUS_STEP("Предыдущий шаг"),
    REQUEST_VALIDATION("Отправить на валидацию"),
    RECORD_VALIDATION("Зафиксировать результат валидации"),
    SEND_BACK_TO_EXECUTION("Вернуть на доработку"),
    COMPLETE("Завершить задачу"),
    CANCEL("Отменить задачу"),
    PAUSE("Приостановить"),
    RESUME("Возобновить"),
    RESET("Сбросить состояние")
}

/**
 * Preconditions (guards) that must be satisfied for a transition to be valid.
 */
sealed interface TransitionGuard {
    fun evaluate(state: TaskState, context: Any? = null): GuardResult

    object NotPaused : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (state.paused) {
                GuardResult.Failed(
                    "Задача на паузе",
                    "Сначала возобновите задачу кнопкой «Возобновить»"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object IsPaused : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (!state.paused) {
                GuardResult.Failed(
                    "Задача не на паузе",
                    "Приостановить задачу можно кнопкой «Приостановить»"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object NotTerminal : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (state.isTerminal) {
                GuardResult.Failed(
                    "Задача уже в терминальном состоянии (${state.stage})",
                    "Для начала новой задачи используйте «Сбросить состояние» или начните новую"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object IsActive : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (!state.isActive) {
                GuardResult.Failed(
                    "Нет активной задачи",
                    "Сначала начните задачу"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object PlanHasSteps : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult {
            @Suppress("UNCHECKED_CAST")
            val steps = (context as? List<String>) ?: state.steps
            val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }
            return if (cleanSteps.isEmpty()) {
                GuardResult.Failed(
                    "План должен содержать хотя бы один шаг",
                    "Добавьте хотя бы один шаг в план перед утверждением"
                )
            } else {
                GuardResult.Satisfied
            }
        }
    }

    object HasNextStep : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (state.currentStepIndex + 1 >= state.steps.size) {
                GuardResult.Failed(
                    "Уже на последнем шаге (${state.progressLabel})",
                    "Отправьте задачу на валидацию кнопкой «Отправить на валидацию»"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object HasPreviousStep : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (state.currentStepIndex - 1 < 0) {
                GuardResult.Failed(
                    "Уже на первом шаге (${state.progressLabel})",
                    "Двигайтесь вперед или вернитесь к планированию"
                )
            } else {
                GuardResult.Satisfied
            }
    }

    object ValidationPassed : TransitionGuard {
        override fun evaluate(state: TaskState, context: Any?): GuardResult =
            if (state.validationOutcome != ValidationOutcome.PASSED) {
                GuardResult.Failed(
                    "Валидация не пройдена (текущий статус: ${state.validationOutcome})",
                    "Зафиксируйте успешный результат валидации (PASSED) перед завершением задачи"
                )
            } else {
                GuardResult.Satisfied
            }
    }
}

sealed interface GuardResult {
    object Satisfied : GuardResult
    data class Failed(val reason: String, val hint: String) : GuardResult
}

/**
 * Structured rejection of an attempted state transition.
 */
data class TransitionRejection(
    val event: TaskEvent,
    val currentStage: TaskStage,
    val reason: String,
    val hint: String
) {
    val message: String
        get() = "Нельзя выполнить «${event.displayName}» из состояния $currentStage: $reason. $hint"
}

/**
 * One valid transition descriptor in the state transition table.
 */
data class TransitionDefinition(
    val from: TaskStage,
    val event: TaskEvent,
    val to: TaskStage,
    val guards: List<TransitionGuard> = emptyList()
)

/**
 * Day 15: Declarative state transition table.
 * Single source of truth for all permitted state transitions in the task lifecycle.
 */
object TaskTransitionTable {

    val transitions: List<TransitionDefinition> = listOf(
        // PLANNING transitions
        TransitionDefinition(
            from = TaskStage.PLANNING,
            event = TaskEvent.APPROVE_PLAN,
            to = TaskStage.EXECUTION,
            guards = listOf(TransitionGuard.NotPaused, TransitionGuard.PlanHasSteps)
        ),
        TransitionDefinition(
            from = TaskStage.PLANNING,
            event = TaskEvent.CANCEL,
            to = TaskStage.CANCELLED,
            guards = listOf(TransitionGuard.NotTerminal)
        ),

        // EXECUTION transitions
        TransitionDefinition(
            from = TaskStage.EXECUTION,
            event = TaskEvent.NEXT_STEP,
            to = TaskStage.EXECUTION,
            guards = listOf(TransitionGuard.NotPaused, TransitionGuard.HasNextStep)
        ),
        TransitionDefinition(
            from = TaskStage.EXECUTION,
            event = TaskEvent.PREVIOUS_STEP,
            to = TaskStage.EXECUTION,
            guards = listOf(TransitionGuard.NotPaused, TransitionGuard.HasPreviousStep)
        ),
        TransitionDefinition(
            from = TaskStage.EXECUTION,
            event = TaskEvent.REQUEST_VALIDATION,
            to = TaskStage.VALIDATION,
            guards = listOf(TransitionGuard.NotPaused)
        ),
        TransitionDefinition(
            from = TaskStage.EXECUTION,
            event = TaskEvent.CANCEL,
            to = TaskStage.CANCELLED,
            guards = listOf(TransitionGuard.NotTerminal)
        ),

        // VALIDATION transitions
        TransitionDefinition(
            from = TaskStage.VALIDATION,
            event = TaskEvent.RECORD_VALIDATION,
            to = TaskStage.VALIDATION,
            guards = listOf(TransitionGuard.NotPaused)
        ),
        TransitionDefinition(
            from = TaskStage.VALIDATION,
            event = TaskEvent.SEND_BACK_TO_EXECUTION,
            to = TaskStage.EXECUTION,
            guards = listOf(TransitionGuard.NotPaused)
        ),
        TransitionDefinition(
            from = TaskStage.VALIDATION,
            event = TaskEvent.COMPLETE,
            to = TaskStage.DONE,
            guards = listOf(TransitionGuard.NotPaused, TransitionGuard.ValidationPassed)
        ),
        TransitionDefinition(
            from = TaskStage.VALIDATION,
            event = TaskEvent.CANCEL,
            to = TaskStage.CANCELLED,
            guards = listOf(TransitionGuard.NotTerminal)
        )
    )

    /**
     * Checks if [event] is legally allowed from [state] with the given [context].
     * Returns `null` if allowed, or a [TransitionRejection] detailing why it was rejected.
     */
    fun check(state: TaskState, event: TaskEvent, context: Any? = null): TransitionRejection? {
        // Universal actions
        if (event == TaskEvent.RESET) {
            return null
        }
        if (event == TaskEvent.START) {
            return null
        }

        if (!state.isActive) {
            return TransitionRejection(
                event = event,
                currentStage = state.stage,
                reason = "Нет активной задачи",
                hint = "Сначала начните задачу"
            )
        }

        // Pause / Resume special handling
        if (event == TaskEvent.PAUSE) {
            if (state.isTerminal) {
                return TransitionRejection(
                    event = event,
                    currentStage = state.stage,
                    reason = "Задача завершена",
                    hint = "Нельзя поставить на паузу завершенную задачу"
                )
            }
            if (state.paused) {
                return TransitionRejection(
                    event = event,
                    currentStage = state.stage,
                    reason = "Задача уже на паузе",
                    hint = "Возобновите задачу, когда будете готовы"
                )
            }
            return null
        }

        if (event == TaskEvent.RESUME) {
            if (state.isTerminal) {
                return TransitionRejection(
                    event = event,
                    currentStage = state.stage,
                    reason = "Задача завершена",
                    hint = "Нельзя возобновить завершенную задачу"
                )
            }
            if (!state.paused) {
                return TransitionRejection(
                    event = event,
                    currentStage = state.stage,
                    reason = "Задача не на паузе",
                    hint = "Задача уже активна"
                )
            }
            return null
        }

        // Check matching definitions for (from, event)
        val matchingDefs = transitions.filter { it.from == state.stage && it.event == event }
        if (matchingDefs.isEmpty()) {
            val allowedForEvent = transitions.filter { it.event == event }.map { it.from }
            val hint = if (allowedForEvent.isNotEmpty()) {
                "Действие «${event.displayName}» разрешено только в: ${allowedForEvent.joinToString(", ")}"
            } else {
                "Действие не поддерживается для текущего состояния"
            }
            return TransitionRejection(
                event = event,
                currentStage = state.stage,
                reason = "Переход не предусмотрен в таблице состояний",
                hint = hint
            )
        }

        // Evaluate guards
        for (def in matchingDefs) {
            for (guard in def.guards) {
                val guardResult = guard.evaluate(state, context)
                if (guardResult is GuardResult.Failed) {
                    return TransitionRejection(
                        event = event,
                        currentStage = state.stage,
                        reason = guardResult.reason,
                        hint = guardResult.hint
                    )
                }
            }
        }

        return null
    }

    /**
     * Returns the set of events that are valid and executable from the given [state].
     */
    fun allowedEvents(state: TaskState): Set<TaskEvent> {
        val allowed = mutableSetOf<TaskEvent>()
        allowed += TaskEvent.START
        allowed += TaskEvent.RESET

        if (!state.isActive) {
            return allowed
        }

        if (state.isTerminal) {
            return allowed
        }

        if (state.paused) {
            allowed += TaskEvent.RESUME
            allowed += TaskEvent.CANCEL
            return allowed
        }

        allowed += TaskEvent.PAUSE
        allowed += TaskEvent.CANCEL

        for (def in transitions) {
            if (def.from == state.stage) {
                // APPROVE_PLAN's real step list is only known at submission time (typed into
                // the UI's text field), not yet reflected in `state.steps` while still in
                // PLANNING. Checking with no context would make PlanHasSteps always fail here,
                // hiding the button entirely — so probe with a non-empty placeholder to test
                // only the *other* guards (paused/terminal); the real step content is validated
                // for real when TaskStateMachine.approvePlan(state, actualSteps) is called.
                val probeContext = if (def.event == TaskEvent.APPROVE_PLAN) listOf("placeholder") else null
                if (check(state, def.event, probeContext) == null) {
                    allowed += def.event
                }
            }
        }

        return allowed
    }
}
