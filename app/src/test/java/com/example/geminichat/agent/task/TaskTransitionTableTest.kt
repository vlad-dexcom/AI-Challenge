package com.example.geminichat.agent.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day 15: Unit tests for [TaskTransitionTable].
 * Verifies that state transitions obey the declarative rules and guards.
 */
class TaskTransitionTableTest {

    private fun planningState(steps: List<String> = listOf("Step 1", "Step 2")) = TaskState(
        title = "Test task",
        stage = TaskStage.PLANNING,
        steps = steps,
        updatedAt = 100L
    )

    private fun executionState(steps: List<String> = listOf("Step 1", "Step 2"), stepIndex: Int = 0) = TaskState(
        title = "Test task",
        stage = TaskStage.EXECUTION,
        steps = steps,
        currentStepIndex = stepIndex,
        planApproved = true,
        updatedAt = 100L
    )

    private fun validationState(outcome: ValidationOutcome = ValidationOutcome.NOT_RUN) = TaskState(
        title = "Test task",
        stage = TaskStage.VALIDATION,
        steps = listOf("Step 1", "Step 2"),
        currentStepIndex = 1,
        validationOutcome = outcome,
        planApproved = true,
        updatedAt = 100L
    )

    private fun doneState() = TaskState(
        title = "Test task",
        stage = TaskStage.DONE,
        steps = listOf("Step 1", "Step 2"),
        validationOutcome = ValidationOutcome.PASSED,
        planApproved = true,
        updatedAt = 100L
    )

    private fun cancelledState() = TaskState(
        title = "Test task",
        stage = TaskStage.CANCELLED,
        updatedAt = 100L
    )

    // --- Universal actions ---

    @Test
    fun `RESET and START are always allowed from any state`() {
        listOf(
            TaskState.NONE,
            planningState(),
            executionState(),
            validationState(),
            doneState(),
            cancelledState(),
            executionState().copy(paused = true)
        ).forEach { state ->
            assertNull("RESET should be allowed from ${state.stage}", TaskTransitionTable.check(state, TaskEvent.RESET))
            assertNull("START should be allowed from ${state.stage}", TaskTransitionTable.check(state, TaskEvent.START))
        }
    }

    // --- PLANNING transitions ---

    @Test
    fun `APPROVE_PLAN is allowed from PLANNING with non-empty steps`() {
        val state = planningState()
        assertNull(TaskTransitionTable.check(state, TaskEvent.APPROVE_PLAN, listOf("Step 1")))
    }

    @Test
    fun `APPROVE_PLAN is rejected from PLANNING with empty steps`() {
        val state = planningState()
        val rejection = TaskTransitionTable.check(state, TaskEvent.APPROVE_PLAN, emptyList<String>())
        assertNotNull(rejection)
        assertTrue(rejection!!.reason.contains("План должен содержать"))
    }

    @Test
    fun `APPROVE_PLAN is rejected from EXECUTION`() {
        val state = executionState()
        val rejection = TaskTransitionTable.check(state, TaskEvent.APPROVE_PLAN, listOf("Step 1"))
        assertNotNull(rejection)
    }

    @Test
    fun `CANCEL is allowed from PLANNING, EXECUTION, and VALIDATION`() {
        assertNull(TaskTransitionTable.check(planningState(), TaskEvent.CANCEL))
        assertNull(TaskTransitionTable.check(executionState(), TaskEvent.CANCEL))
        assertNull(TaskTransitionTable.check(validationState(), TaskEvent.CANCEL))
    }

    @Test
    fun `CANCEL is rejected from terminal states`() {
        assertNotNull(TaskTransitionTable.check(doneState(), TaskEvent.CANCEL))
        assertNotNull(TaskTransitionTable.check(cancelledState(), TaskEvent.CANCEL))
    }

    // --- EXECUTION transitions ---

    @Test
    fun `NEXT_STEP is allowed within bounds`() {
        val state = executionState(stepIndex = 0)
        assertNull(TaskTransitionTable.check(state, TaskEvent.NEXT_STEP))
    }

    @Test
    fun `NEXT_STEP is rejected at the last step`() {
        val state = executionState(stepIndex = 1)
        val rejection = TaskTransitionTable.check(state, TaskEvent.NEXT_STEP)
        assertNotNull(rejection)
        assertTrue(rejection!!.reason.contains("последнем шаге"))
    }

    @Test
    fun `PREVIOUS_STEP is rejected at the first step`() {
        val state = executionState(stepIndex = 0)
        val rejection = TaskTransitionTable.check(state, TaskEvent.PREVIOUS_STEP)
        assertNotNull(rejection)
        assertTrue(rejection!!.reason.contains("первом шаге"))
    }

    @Test
    fun `PREVIOUS_STEP is allowed after the first step`() {
        val state = executionState(stepIndex = 1)
        assertNull(TaskTransitionTable.check(state, TaskEvent.PREVIOUS_STEP))
    }

    @Test
    fun `REQUEST_VALIDATION is allowed from EXECUTION`() {
        assertNull(TaskTransitionTable.check(executionState(), TaskEvent.REQUEST_VALIDATION))
    }

    @Test
    fun `REQUEST_VALIDATION is rejected from PLANNING`() {
        assertNotNull(TaskTransitionTable.check(planningState(), TaskEvent.REQUEST_VALIDATION))
    }

    // --- VALIDATION transitions ---

    @Test
    fun `COMPLETE is rejected from VALIDATION without PASSED outcome`() {
        val notRun = validationState(ValidationOutcome.NOT_RUN)
        val failed = validationState(ValidationOutcome.FAILED)

        val rejNotRun = TaskTransitionTable.check(notRun, TaskEvent.COMPLETE)
        assertNotNull(rejNotRun)
        assertTrue(rejNotRun!!.reason.contains("Валидация не пройдена"))

        val rejFailed = TaskTransitionTable.check(failed, TaskEvent.COMPLETE)
        assertNotNull(rejFailed)
        assertTrue(rejFailed!!.reason.contains("Валидация не пройдена"))
    }

    @Test
    fun `COMPLETE is allowed from VALIDATION when outcome is PASSED`() {
        val passed = validationState(ValidationOutcome.PASSED)
        assertNull(TaskTransitionTable.check(passed, TaskEvent.COMPLETE))
    }

    @Test
    fun `COMPLETE is rejected from PLANNING and EXECUTION`() {
        assertNotNull(TaskTransitionTable.check(planningState(), TaskEvent.COMPLETE))
        assertNotNull(TaskTransitionTable.check(executionState(), TaskEvent.COMPLETE))
    }

    @Test
    fun `SEND_BACK_TO_EXECUTION is allowed from VALIDATION`() {
        assertNull(TaskTransitionTable.check(validationState(), TaskEvent.SEND_BACK_TO_EXECUTION))
    }

    @Test
    fun `RECORD_VALIDATION is allowed from VALIDATION`() {
        assertNull(TaskTransitionTable.check(validationState(), TaskEvent.RECORD_VALIDATION))
    }

    // --- Pause & Resume ---

    @Test
    fun `PAUSE is allowed on non-terminal active tasks`() {
        assertNull(TaskTransitionTable.check(planningState(), TaskEvent.PAUSE))
        assertNull(TaskTransitionTable.check(executionState(), TaskEvent.PAUSE))
        assertNull(TaskTransitionTable.check(validationState(), TaskEvent.PAUSE))
    }

    @Test
    fun `PAUSE is rejected when already paused or terminal`() {
        assertNotNull(TaskTransitionTable.check(executionState().copy(paused = true), TaskEvent.PAUSE))
        assertNotNull(TaskTransitionTable.check(doneState(), TaskEvent.PAUSE))
        assertNotNull(TaskTransitionTable.check(cancelledState(), TaskEvent.PAUSE))
    }

    @Test
    fun `RESUME is allowed when paused`() {
        val paused = executionState().copy(paused = true)
        assertNull(TaskTransitionTable.check(paused, TaskEvent.RESUME))
    }

    @Test
    fun `RESUME is rejected when not paused`() {
        assertNotNull(TaskTransitionTable.check(executionState(), TaskEvent.RESUME))
    }

    @Test
    fun `Operations are rejected when paused except RESUME, CANCEL, RESET, START`() {
        val paused = executionState().copy(paused = true)
        assertNotNull(TaskTransitionTable.check(paused, TaskEvent.NEXT_STEP))
        assertNotNull(TaskTransitionTable.check(paused, TaskEvent.PREVIOUS_STEP))
        assertNotNull(TaskTransitionTable.check(paused, TaskEvent.REQUEST_VALIDATION))
        assertNull(TaskTransitionTable.check(paused, TaskEvent.CANCEL))
        assertNull(TaskTransitionTable.check(paused, TaskEvent.RESUME))
        assertNull(TaskTransitionTable.check(paused, TaskEvent.RESET))
    }

    // --- allowedEvents check ---

    @Test
    fun `allowedEvents returns expected events for each stage`() {
        val planningAllowed = TaskTransitionTable.allowedEvents(planningState())
        assertTrue(planningAllowed.contains(TaskEvent.APPROVE_PLAN))
        assertTrue(planningAllowed.contains(TaskEvent.CANCEL))
        assertTrue(planningAllowed.contains(TaskEvent.PAUSE))
        assertFalse(planningAllowed.contains(TaskEvent.COMPLETE))
        assertFalse(planningAllowed.contains(TaskEvent.NEXT_STEP))

        // Regression: right after start(), the task is in PLANNING with NO steps yet (they're
        // only typed into the UI and submitted via approvePlan) — APPROVE_PLAN must still show
        // up so the user has a way to submit a plan at all.
        val freshPlanning = planningState(steps = emptyList())
        assertTrue(TaskTransitionTable.allowedEvents(freshPlanning).contains(TaskEvent.APPROVE_PLAN))

        val execAllowed = TaskTransitionTable.allowedEvents(executionState(stepIndex = 0))
        assertTrue(execAllowed.contains(TaskEvent.NEXT_STEP))
        assertTrue(execAllowed.contains(TaskEvent.REQUEST_VALIDATION))
        assertFalse(execAllowed.contains(TaskEvent.PREVIOUS_STEP)) // on first step
        assertFalse(execAllowed.contains(TaskEvent.COMPLETE))

        val valAllowed = TaskTransitionTable.allowedEvents(validationState(ValidationOutcome.NOT_RUN))
        assertTrue(valAllowed.contains(TaskEvent.RECORD_VALIDATION))
        assertTrue(valAllowed.contains(TaskEvent.SEND_BACK_TO_EXECUTION))
        assertFalse(valAllowed.contains(TaskEvent.COMPLETE)) // not passed yet

        val valPassedAllowed = TaskTransitionTable.allowedEvents(validationState(ValidationOutcome.PASSED))
        assertTrue(valPassedAllowed.contains(TaskEvent.COMPLETE))

        val pausedAllowed = TaskTransitionTable.allowedEvents(executionState().copy(paused = true))
        assertTrue(pausedAllowed.contains(TaskEvent.RESUME))
        assertTrue(pausedAllowed.contains(TaskEvent.CANCEL))
        assertFalse(pausedAllowed.contains(TaskEvent.PAUSE))
        assertFalse(pausedAllowed.contains(TaskEvent.NEXT_STEP))
    }
}
