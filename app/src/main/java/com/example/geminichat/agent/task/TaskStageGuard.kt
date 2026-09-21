package com.example.geminichat.agent.task

/**
 * Day 15: Details of a stage violation detected by [TaskStageGuard].
 *
 * @property currentStage The stage the task is currently in.
 * @property attemptedStage The stage the user's message is attempting to jump into.
 * @property reason Why this jump is not permitted.
 * @property requiredActionHint What transition or action must happen first.
 * @property matchedTrigger The substring or pattern that triggered the violation.
 */
data class StageViolation(
    val currentStage: TaskStage,
    val attemptedStage: TaskStage,
    val reason: String,
    val requiredActionHint: String,
    val matchedTrigger: String
)

/**
 * Day 15: Deterministic request pre-check (mirroring [com.example.geminichat.agent.invariant.InvariantGuard]).
 *
 * Prevents the assistant from jumping stages (e.g., executing before plan approval, or finalizing
 * without validation). Runs *before* prompt assembly and LLM invocation:
 * - When violated, refusal happens in code with zero token cost.
 * - Does not rely on the LLM to self-police its own prompt instructions.
 */
object TaskStageGuard {

    private fun normalize(text: String): String = text.lowercase().replace('ё', 'е').trim()

    // Execution intent triggers: requests to perform/output concrete execution steps or programs
    private val EXECUTION_TRIGGERS = listOf(
        Regex("""(дай|напиши|составь|сделай|выдай)\s*(сразу|прямо сейчас)?\s*(программ|тренировк|упражнен|план тренировок)""", RegexOption.IGNORE_CASE),
        Regex("""(дай|выдай|покажи|напиши)\s*(еще|другие)?\s*(упражнен|тренировк)""", RegexOption.IGNORE_CASE),
        Regex("""(погнали|начинай|давай)\s+(делать|выполнять|тренироваться|реализаци)""", RegexOption.IGNORE_CASE),
        Regex("""(дай|выдай)\s+(конкретн|готов)\s*(упражнен|шаг|действи)""", RegexOption.IGNORE_CASE),
        Regex("""(пропусти|без)\s+(план|планирован)""", RegexOption.IGNORE_CASE),
        Regex("""(start\s+execution|give\s+me\s+the\s+program|give\s+me\s+the\s+workout|skip\s+planning)""", RegexOption.IGNORE_CASE)
    )

    // Validation intent triggers: requests to validate results before execution is done
    private val VALIDATION_TRIGGERS = listOf(
        Regex("""(проверь|проведи\s+валидацию|провалидируй)\s*(результат|итог)?""", RegexOption.IGNORE_CASE),
        Regex("""готово\s+ли\s+все""", RegexOption.IGNORE_CASE),
        Regex("""(check|validate)\s+(the\s+)?result""", RegexOption.IGNORE_CASE)
    )

    // Completion / Done intent triggers: requests to finish or mark done
    private val COMPLETION_TRIGGERS = listOf(
        Regex("""(заверш|закрой|заканчивай|финализируй)\s*(задач|программ)?""", RegexOption.IGNORE_CASE),
        Regex("""пометь\s+(как\s+)?сделан""", RegexOption.IGNORE_CASE),
        Regex("""подведи\s+итог\s+и\s+(закрывай|завершай)""", RegexOption.IGNORE_CASE),
        Regex("""(mark\s+done|complete\s+task|finish\s+task)""", RegexOption.IGNORE_CASE)
    )

    // Actions attempted while paused
    private val RESUME_EXEMPTION_TRIGGERS = listOf(
        Regex("""(продолж|возобнови|resume)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Checks whether [userMessage] attempts an illegal stage jump given [state].
     * Returns a [StageViolation] if illegal, or `null` if the message is allowed for the current stage.
     */
    fun check(state: TaskState, userMessage: String): StageViolation? {
        if (!state.isActive) return null

        val normalized = normalize(userMessage)

        // 1. Paused check: any attempt to execute/progress while paused (except resuming) is blocked
        if (state.paused) {
            val isResumeRequest = RESUME_EXEMPTION_TRIGGERS.any { it.containsMatchIn(normalized) }
            if (!isResumeRequest) {
                // If the user tries to do actual work while paused:
                val isTryingToWork = EXECUTION_TRIGGERS.any { it.containsMatchIn(normalized) } ||
                    VALIDATION_TRIGGERS.any { it.containsMatchIn(normalized) } ||
                    COMPLETION_TRIGGERS.any { it.containsMatchIn(normalized) }
                if (isTryingToWork) {
                    return StageViolation(
                        currentStage = state.stage,
                        attemptedStage = state.stage,
                        reason = "Задача находится на паузе",
                        requiredActionHint = "Нажмите «Возобновить» в панели «Задача» или напишите «продолжим», чтобы снять задачу с паузы",
                        matchedTrigger = "задача на паузе"
                    )
                }
            }
            return null
        }

        // 2. Terminal check: cannot advance or execute on DONE or CANCELLED tasks
        if (state.isTerminal) {
            val hasWorkIntent = EXECUTION_TRIGGERS.any { it.containsMatchIn(normalized) } ||
                VALIDATION_TRIGGERS.any { it.containsMatchIn(normalized) } ||
                COMPLETION_TRIGGERS.any { it.containsMatchIn(normalized) }
            if (hasWorkIntent) {
                return StageViolation(
                    currentStage = state.stage,
                    attemptedStage = TaskStage.EXECUTION,
                    reason = "Задача уже завершена (${state.stage})",
                    requiredActionHint = "Сбросьте текущую задачу («Сбросить») и начните новую («Начать задачу»)",
                    matchedTrigger = "задача завершена"
                )
            }
            return null
        }

        // 3. Stage-specific checks:
        when (state.stage) {
            TaskStage.PLANNING -> {
                // Cannot execute without approved plan
                EXECUTION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                    return StageViolation(
                        currentStage = TaskStage.PLANNING,
                        attemptedStage = TaskStage.EXECUTION,
                        reason = "Нельзя переходить к выполнению до утверждения плана",
                        requiredActionHint = "Согласуйте и утвердите план шагов (кнопка «Утвердить план» в панели задачи)",
                        matchedTrigger = match.value
                    )
                }
                // Cannot validate or complete from planning
                VALIDATION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                    return StageViolation(
                        currentStage = TaskStage.PLANNING,
                        attemptedStage = TaskStage.VALIDATION,
                        reason = "Нельзя проводить валидацию на этапе планирования",
                        requiredActionHint = "Сначала утвердите план и выполните шаги",
                        matchedTrigger = match.value
                    )
                }
                COMPLETION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                    return StageViolation(
                        currentStage = TaskStage.PLANNING,
                        attemptedStage = TaskStage.DONE,
                        reason = "Нельзя завершить задачу без выполнения и валидации",
                        requiredActionHint = "Если задача больше не актуальна — нажмите «Отменить задачу». Для завершения пройдите выполнение и валидацию",
                        matchedTrigger = match.value
                    )
                }
            }
            TaskStage.EXECUTION -> {
                // Cannot jump straight to complete without validation
                COMPLETION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                    return StageViolation(
                        currentStage = TaskStage.EXECUTION,
                        attemptedStage = TaskStage.DONE,
                        reason = "Нельзя завершить задачу без проверки (этап VALIDATION)",
                        requiredActionHint = "Отправьте задачу на валидацию кнопкой «Отправить на валидацию» и зафиксируйте успешный результат",
                        matchedTrigger = match.value
                    )
                }
            }
            TaskStage.VALIDATION -> {
                // Cannot complete if validation hasn't passed
                if (state.validationOutcome != ValidationOutcome.PASSED) {
                    COMPLETION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                        return StageViolation(
                            currentStage = TaskStage.VALIDATION,
                            attemptedStage = TaskStage.DONE,
                            reason = "Нельзя завершить задачу без успешного результата валидации (текущий: ${state.validationOutcome})",
                            requiredActionHint = "Зафиксируйте результат валидации как «Пройдена» (PASSED) в панели задачи перед завершением",
                            matchedTrigger = match.value
                        )
                    }
                }
                // In validation, cannot start fresh execution without sending back
                EXECUTION_TRIGGERS.firstNotNullOfOrNull { it.find(normalized) }?.let { match ->
                    return StageViolation(
                        currentStage = TaskStage.VALIDATION,
                        attemptedStage = TaskStage.EXECUTION,
                        reason = "Задача находится на валидации; новые шаги не выполняются",
                        requiredActionHint = "Если требуются доработки, используйте «Отправить на доработку»",
                        matchedTrigger = match.value
                    )
                }
            }
            TaskStage.DONE, TaskStage.CANCELLED -> {
                // Handled above in isTerminal check
            }
        }

        return null
    }

    /**
     * Formats a clear, explanatory refusal message for the user.
     */
    fun refusalText(violation: StageViolation): String = buildString {
        append("⛔ Не могу выполнить этот запрос на текущем этапе задачи (**${violation.currentStage}**).\n\n")
        append("**Причина:** ${violation.reason}.\n")
        append("**Что нужно сделать:** ${violation.requiredActionHint}.\n\n")
        append("Если я неправильно понял запрос — переформулируй его.")
    }
}
