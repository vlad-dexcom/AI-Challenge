package com.example.geminichat.agent.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day 15: Unit tests for [TaskStageGuard].
 * Tests deterministic refusal of user messages attempting to jump stages.
 */
class TaskStageGuardTest {

    @Test
    fun `guard allows any message when no task is active`() {
        assertNull(TaskStageGuard.check(TaskState.NONE, "дай сразу программу на 8 недель"))
        assertNull(TaskStageGuard.check(TaskState.NONE, "завершай задачу"))
    }

    @Test
    fun `guard forbids jumping to execution from PLANNING`() {
        val planning = TaskState(title = "Подготовка", stage = TaskStage.PLANNING)

        val violation = TaskStageGuard.check(planning, "дай сразу программу тренировок на каждый день")
        assertNotNull(violation)
        assertEquals(TaskStage.PLANNING, violation!!.currentStage)
        assertEquals(TaskStage.EXECUTION, violation.attemptedStage)
        assertTrue(violation.reason.contains("до утверждения плана"))

        val englishViolation = TaskStageGuard.check(planning, "skip planning and give me the workout")
        assertNotNull(englishViolation)
    }

    @Test
    fun `guard forbids validation and completion from PLANNING`() {
        val planning = TaskState(title = "Подготовка", stage = TaskStage.PLANNING)

        val valViolation = TaskStageGuard.check(planning, "проверь результат")
        assertNotNull(valViolation)
        assertEquals(TaskStage.VALIDATION, valViolation!!.attemptedStage)

        val compViolation = TaskStageGuard.check(planning, "подведи итог и закрывай задачу")
        assertNotNull(compViolation)
        assertEquals(TaskStage.DONE, compViolation!!.attemptedStage)
    }

    @Test
    fun `guard allows planning discussion in PLANNING`() {
        val planning = TaskState(title = "Подготовка", stage = TaskStage.PLANNING)

        assertNull(TaskStageGuard.check(planning, "Хочу бегать 3 раза в неделю и делать растяжку"))
        assertNull(TaskStageGuard.check(planning, "Предложи план на 4 недели"))
    }

    @Test
    fun `guard forbids completion without validation from EXECUTION`() {
        val execution = TaskState(
            title = "Подготовка",
            stage = TaskStage.EXECUTION,
            steps = listOf("Шаг 1", "Шаг 2"),
            currentStepIndex = 1
        )

        val violation = TaskStageGuard.check(execution, "завершай задачу и подведи итог")
        assertNotNull(violation)
        assertEquals(TaskStage.EXECUTION, violation!!.currentStage)
        assertEquals(TaskStage.DONE, violation.attemptedStage)
        assertTrue(violation.reason.contains("VALIDATION"))
    }

    @Test
    fun `guard allows execution logs in EXECUTION`() {
        val execution = TaskState(
            title = "Подготовка",
            stage = TaskStage.EXECUTION,
            steps = listOf("Шаг 1", "Шаг 2"),
            currentStepIndex = 0
        )

        assertNull(TaskStageGuard.check(execution, "Сегодня пробежал 5 км в темпе 5:30"))
        assertNull(TaskStageGuard.check(execution, "Пульс был 145"))
    }

    @Test
    fun `guard forbids completion in VALIDATION if validation outcome is not PASSED`() {
        val notRun = TaskState(
            title = "Подготовка",
            stage = TaskStage.VALIDATION,
            validationOutcome = ValidationOutcome.NOT_RUN
        )
        val violation = TaskStageGuard.check(notRun, "пометь как сделано")
        assertNotNull(violation)
        assertTrue(violation!!.reason.contains("без успешного результата"))

        val failed = notRun.copy(validationOutcome = ValidationOutcome.FAILED)
        val failedViolation = TaskStageGuard.check(failed, "завершай задачу")
        assertNotNull(failedViolation)
    }

    @Test
    fun `guard allows completion in VALIDATION when validation outcome is PASSED`() {
        val passed = TaskState(
            title = "Подготовка",
            stage = TaskStage.VALIDATION,
            validationOutcome = ValidationOutcome.PASSED
        )
        assertNull(TaskStageGuard.check(passed, "завершай задачу"))
    }

    @Test
    fun `guard blocks work while paused with message to resume`() {
        val paused = TaskState(
            title = "Подготовка",
            stage = TaskStage.EXECUTION,
            paused = true
        )

        val violation = TaskStageGuard.check(paused, "начинай делать упражнения")
        assertNotNull(violation)
        assertTrue(violation!!.reason.contains("на паузе"))
        assertTrue(violation.requiredActionHint.contains("Возобновить"))

        // Resuming messages are not blocked
        assertNull(TaskStageGuard.check(paused, "Давай продолжим тренировку"))
    }

    @Test
    fun `guard blocks work on finished or cancelled tasks`() {
        val done = TaskState(title = "Подготовка", stage = TaskStage.DONE)
        val cancelled = TaskState(title = "Подготовка", stage = TaskStage.CANCELLED)

        assertNotNull(TaskStageGuard.check(done, "дай еще упражнения"))
        assertNotNull(TaskStageGuard.check(cancelled, "дай тренировку"))
    }

    @Test
    fun `refusalText produces explanatory guidance`() {
        val planning = TaskState(title = "Подготовка", stage = TaskStage.PLANNING)
        val violation = TaskStageGuard.check(planning, "дай сразу программу")!!

        val text = TaskStageGuard.refusalText(violation)
        assertTrue(text.contains("⛔ Не могу выполнить этот запрос"))
        assertTrue(text.contains("PLANNING"))
        assertTrue(text.contains(violation.reason))
        assertTrue(text.contains(violation.requiredActionHint))
        assertTrue(text.contains("переформулируй его"))
    }
}
