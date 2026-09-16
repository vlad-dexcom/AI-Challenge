package com.example.geminichat.agent.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Day 13: [TaskStateRenderer] output is deterministic data-to-text, so tests only need to
 * check the rendered content, not mock anything. */
class TaskStateRendererTest {

    @Test
    fun `render is empty for NONE`() {
        assertEquals("", TaskStateRenderer.render(TaskState.NONE))
    }

    @Test
    fun `stageRules is empty for NONE`() {
        assertEquals("", TaskStateRenderer.stageRules(TaskState.NONE))
    }

    @Test
    fun `render includes task title, stage, step progress, current step and expected action`() {
        val state = TaskState(
            title = "10km race in 6 weeks",
            stage = TaskStage.EXECUTION,
            steps = listOf("Base mileage", "Tempo runs"),
            currentStepIndex = 1,
            expectedAction = "send pulse readings",
            expectedActor = ExpectedActor.USER
        )

        val block = TaskStateRenderer.render(state)

        assertTrue(block.contains("10km race in 6 weeks"))
        assertTrue(block.contains("EXECUTION"))
        assertTrue(block.contains("(2 of 2)"))
        assertTrue(block.contains("Tempo runs"))
        assertTrue(block.contains("USER"))
        assertTrue(block.contains("send pulse readings"))
    }

    @Test
    fun `render marks a paused task instead of implying it should move forward`() {
        val state = TaskState(title = "Race prep", stage = TaskStage.EXECUTION, paused = true)

        val block = TaskStateRenderer.render(state)

        assertTrue(block.contains("PAUSED"))
    }

    @Test
    fun `stageRules differ per stage and forbid re-explaining an approved plan during EXECUTION`() {
        val planningRules = TaskStateRenderer.stageRules(TaskState(title = "x", stage = TaskStage.PLANNING))
        val executionRules = TaskStateRenderer.stageRules(TaskState(title = "x", stage = TaskStage.EXECUTION))

        assertTrue(planningRules.contains("PLANNING"))
        assertTrue(executionRules.contains("EXECUTION"))
        assertTrue(executionRules.lowercase().contains("re-propose"))
    }

    @Test
    fun `stageRules for a paused task overrides the stage-specific rule with a wait-for-resume rule`() {
        val paused = TaskState(title = "x", stage = TaskStage.EXECUTION, paused = true)

        val rules = TaskStateRenderer.stageRules(paused)

        assertTrue(rules.lowercase().contains("paused"))
        assertTrue(rules.lowercase().contains("resume"))
    }
}
