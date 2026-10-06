package com.example.geminichat.agent.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day 13's core requirement: transitions between [TaskStage]s are only ever legal per the
 * table in [TaskStateMachine]'s doc, pausing/resuming returns exactly to where it was paused,
 * and every operation either applies or explains why it didn't — never a silent no-op.
 */
class TaskStateMachineTest {

    private fun applied(result: TransitionResult): TaskState {
        assertTrue("expected Applied, got $result", result is TransitionResult.Applied)
        return (result as TransitionResult.Applied).state
    }

    private fun rejected(result: TransitionResult): String {
        assertTrue("expected Rejected, got $result", result is TransitionResult.Rejected)
        return (result as TransitionResult.Rejected).reason
    }

    // --- start ---

    @Test
    fun `start begins a task in PLANNING with no steps`() {
        val state = applied(TaskStateMachine.start("10km race in 6 weeks", now = 1L))

        assertEquals(TaskStage.PLANNING, state.stage)
        assertEquals("10km race in 6 weeks", state.title)
        assertTrue(state.steps.isEmpty())
        assertEquals(-1, state.currentStepIndex)
        assertEquals(ExpectedActor.AGENT, state.expectedActor)
        assertTrue(state.isActive)
    }

    @Test
    fun `start rejects a blank title`() {
        rejected(TaskStateMachine.start("   "))
    }

    // --- approvePlan: PLANNING -> EXECUTION ---

    @Test
    fun `approvePlan moves to EXECUTION at step 0 with the trimmed non-blank steps`() {
        val planning = applied(TaskStateMachine.start("Race prep"))

        val state = applied(TaskStateMachine.approvePlan(planning, listOf(" Week 1 ", "", "Week 2")))

        assertEquals(TaskStage.EXECUTION, state.stage)
        assertEquals(listOf("Week 1", "Week 2"), state.steps)
        assertEquals(0, state.currentStepIndex)
        assertEquals("Week 1", state.currentStep)
    }

    @Test
    fun `approvePlan rejects an empty plan`() {
        val planning = applied(TaskStateMachine.start("Race prep"))

        rejected(TaskStateMachine.approvePlan(planning, listOf("", "   ")))
    }

    @Test
    fun `approvePlan rejects from a stage other than PLANNING`() {
        val execution = executionState()

        rejected(TaskStateMachine.approvePlan(execution, listOf("Anything")))
    }

    // --- nextStep / previousStep within EXECUTION ---

    @Test
    fun `nextStep advances to the following step`() {
        val execution = executionState()

        val state = applied(TaskStateMachine.nextStep(execution))

        assertEquals(1, state.currentStepIndex)
        assertEquals("Week 2", state.currentStep)
    }

    @Test
    fun `nextStep rejects past the last step`() {
        val onLastStep = applied(TaskStateMachine.nextStep(executionState()))

        rejected(TaskStateMachine.nextStep(onLastStep))
    }

    @Test
    fun `previousStep rejects before the first step`() {
        rejected(TaskStateMachine.previousStep(executionState()))
    }

    @Test
    fun `previousStep moves back after nextStep moved forward`() {
        val onStep2 = applied(TaskStateMachine.nextStep(executionState()))

        val state = applied(TaskStateMachine.previousStep(onStep2))

        assertEquals(0, state.currentStepIndex)
    }

    // --- requestValidation / sendBackToExecution / complete ---

    @Test
    fun `requestValidation moves EXECUTION to VALIDATION`() {
        val state = applied(TaskStateMachine.requestValidation(executionState()))

        assertEquals(TaskStage.VALIDATION, state.stage)
    }

    @Test
    fun `requestValidation rejects from a stage other than EXECUTION`() {
        rejected(TaskStateMachine.requestValidation(applied(TaskStateMachine.start("Race prep"))))
    }

    @Test
    fun `sendBackToExecution moves VALIDATION back to EXECUTION at the given step`() {
        val validation = applied(TaskStateMachine.requestValidation(executionState()))

        val state = applied(TaskStateMachine.sendBackToExecution(validation, "pace too slow", backToStepIndex = 0))

        assertEquals(TaskStage.EXECUTION, state.stage)
        assertEquals(0, state.currentStepIndex)
        assertTrue(state.expectedAction.contains("pace too slow"))
    }

    @Test
    fun `sendBackToExecution rejects from a stage other than VALIDATION`() {
        rejected(TaskStateMachine.sendBackToExecution(executionState(), "reason"))
    }

    @Test
    fun `complete moves VALIDATION to DONE when validation PASSED`() {
        val validation = applied(TaskStateMachine.requestValidation(executionState()))
        val passed = applied(TaskStateMachine.recordValidation(validation, ValidationOutcome.PASSED, "All checks pass"))

        val state = applied(TaskStateMachine.complete(passed))

        assertEquals(TaskStage.DONE, state.stage)
    }

    @Test
    fun `complete rejects from VALIDATION if validation is not PASSED`() {
        val validation = applied(TaskStateMachine.requestValidation(executionState()))

        rejected(TaskStateMachine.complete(validation))
    }

    @Test
    fun `cancel moves PLANNING to CANCELLED`() {
        val planning = applied(TaskStateMachine.start("Race prep"))

        val state = applied(TaskStateMachine.cancel(planning, "Plans changed"))

        assertEquals(TaskStage.CANCELLED, state.stage)
        assertEquals("Plans changed", state.cancellationReason)
    }

    @Test
    fun `complete rejects from PLANNING`() {
        val planning = applied(TaskStateMachine.start("Race prep"))

        rejected(TaskStateMachine.complete(planning))
    }

    @Test
    fun `complete rejects from EXECUTION`() {
        rejected(TaskStateMachine.complete(executionState()))
    }

    // --- DONE is terminal ---

    @Test
    fun `every mutating operation is rejected once DONE, except reset`() {
        val validation = applied(TaskStateMachine.requestValidation(executionState()))
        val passed = applied(TaskStateMachine.recordValidation(validation, ValidationOutcome.PASSED))
        val done = applied(TaskStateMachine.complete(passed))

        rejected(TaskStateMachine.approvePlan(done, listOf("x")))
        rejected(TaskStateMachine.nextStep(done))
        rejected(TaskStateMachine.previousStep(done))
        rejected(TaskStateMachine.requestValidation(done))
        rejected(TaskStateMachine.recordValidation(done, ValidationOutcome.PASSED))
        rejected(TaskStateMachine.sendBackToExecution(done, "x"))
        rejected(TaskStateMachine.complete(done))
        rejected(TaskStateMachine.cancel(done))
        rejected(TaskStateMachine.pause(done))
        rejected(TaskStateMachine.setExpectedAction(done, "x", ExpectedActor.USER))
        assertEquals(TaskState.NONE, applied(TaskStateMachine.reset()))
    }

    // --- pause / resume: the Day 13 "continue without re-explaining" requirement ---

    @Test
    fun `pause then resume returns to exactly the same stage and step`() {
        val onStep2 = applied(TaskStateMachine.nextStep(executionState()))

        val paused = applied(TaskStateMachine.pause(onStep2, now = 10L))
        assertTrue(paused.paused)

        val resumed = applied(TaskStateMachine.resume(paused, now = 20L))

        assertEquals(onStep2.copy(updatedAt = resumed.updatedAt), resumed)
        assertFalse(resumed.paused)
    }

    @Test
    fun `pause is idempotent`() {
        val paused = applied(TaskStateMachine.pause(executionState()))

        val pausedAgain = applied(TaskStateMachine.pause(paused))

        assertTrue(pausedAgain.paused)
    }

    @Test
    fun `resume on a task that is not paused is a no-op transition`() {
        val execution = executionState()

        val state = applied(TaskStateMachine.resume(execution))

        assertEquals(execution.copy(updatedAt = state.updatedAt), state)
    }

    @Test
    fun `pause rejects on a DONE task`() {
        val validation = applied(TaskStateMachine.requestValidation(executionState()))
        val passed = applied(TaskStateMachine.recordValidation(validation, ValidationOutcome.PASSED))
        val done = applied(TaskStateMachine.complete(passed))

        rejected(TaskStateMachine.pause(done))
    }

    @Test
    fun `every mutating operation is rejected while paused, except resume and reset`() {
        val paused = applied(TaskStateMachine.pause(executionState()))

        rejected(TaskStateMachine.approvePlan(paused, listOf("x")))
        rejected(TaskStateMachine.nextStep(paused))
        rejected(TaskStateMachine.previousStep(paused))
        rejected(TaskStateMachine.requestValidation(paused))
        rejected(TaskStateMachine.sendBackToExecution(paused, "x"))
        rejected(TaskStateMachine.complete(paused))
        rejected(TaskStateMachine.setExpectedAction(paused, "x", ExpectedActor.USER))
        assertTrue(applied(TaskStateMachine.resume(paused)).let { !it.paused })
        assertEquals(TaskState.NONE, applied(TaskStateMachine.reset()))
    }

    // --- reset ---

    @Test
    fun `reset always returns to NONE, from any stage`() {
        assertEquals(TaskState.NONE, applied(TaskStateMachine.reset()))
    }

    private fun executionState(): TaskState {
        val planning = applied(TaskStateMachine.start("Race prep"))
        return applied(TaskStateMachine.approvePlan(planning, listOf("Week 1", "Week 2")))
    }
}
