package com.example.geminichat.agent.task

import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class AdvisorFakeLlmClient(private val result: Result<String>) : LlmClient {
    var callCount = 0
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        callCount++
        return result
    }
}

/** Day 13's optional advisor: mirrors
 * [com.example.geminichat.agent.profile.PreferenceAdvisorTest] — parsing is pure/deterministic,
 * [TaskStateAdvisor.suggest] never throws, and it skips the call entirely when there's nothing
 * to advance. */
class TaskStateAdvisorTest {

    private fun executionState() = TaskState(
        title = "Race prep",
        stage = TaskStage.EXECUTION,
        steps = listOf("Week 1", "Week 2"),
        currentStepIndex = 0,
        expectedAction = "work on Week 1",
        expectedActor = ExpectedActor.USER
    )

    @Test
    fun `parseSuggestion returns null for the JSON literal null`() {
        assertNull(TaskStateAdvisor.parseSuggestion("null"))
    }

    @Test
    fun `parseSuggestion returns null for garbage text`() {
        assertNull(TaskStateAdvisor.parseSuggestion("not json at all"))
    }

    @Test
    fun `parseSuggestion parses a valid action`() {
        val suggestion = TaskStateAdvisor.parseSuggestion(
            """{"action": "next_step", "reason": "user reported the run is done"}"""
        )

        assertEquals(TaskTransitionAction.NEXT_STEP, suggestion?.action)
        assertEquals("user reported the run is done", suggestion?.reason)
    }

    @Test
    fun `parseSuggestion tolerates a markdown code fence`() {
        val suggestion = TaskStateAdvisor.parseSuggestion(
            "```json\n{\"action\": \"complete\", \"reason\": \"validated\"}\n```"
        )

        assertEquals(TaskTransitionAction.COMPLETE, suggestion?.action)
    }

    @Test
    fun `parseSuggestion returns null for an unrecognized action name`() {
        val suggestion = TaskStateAdvisor.parseSuggestion(
            """{"action": "teleport", "reason": "irrelevant"}"""
        )

        assertNull(suggestion)
    }

    @Test
    fun `suggest skips the call entirely when no task is active`() = runTest {
        val client = AdvisorFakeLlmClient(Result.success("""{"action": "next_step", "reason": "x"}"""))
        val advisor = TaskStateAdvisor(client)

        val outcome = advisor.suggest(TaskState.NONE, "done", "test-model")

        assertEquals(0, client.callCount)
        assertNull(outcome.getOrThrow().suggestion)
        assertEquals(0, outcome.getOrThrow().tokensUsed)
    }

    @Test
    fun `suggest skips the call entirely when the task is paused`() = runTest {
        val client = AdvisorFakeLlmClient(Result.success("""{"action": "next_step", "reason": "x"}"""))
        val advisor = TaskStateAdvisor(client)

        val outcome = advisor.suggest(executionState().copy(paused = true), "done", "test-model")

        assertEquals(0, client.callCount)
        assertNull(outcome.getOrThrow().suggestion)
    }

    @Test
    fun `suggest never throws on a malformed reply and reports no suggestion`() = runTest {
        val client = AdvisorFakeLlmClient(Result.success("complete garbage, not json"))
        val advisor = TaskStateAdvisor(client)

        val outcome = advisor.suggest(executionState(), "not sure what's happening", "test-model")

        assertTrue(outcome.isSuccess)
        assertNull(outcome.getOrThrow().suggestion)
    }

    @Test
    fun `suggest surfaces a valid suggestion from the client's reply`() = runTest {
        val client = AdvisorFakeLlmClient(
            Result.success("""{"action": "next_step", "reason": "user finished week 1"}""")
        )
        val advisor = TaskStateAdvisor(client)

        val outcome = advisor.suggest(executionState(), "done with week 1", "test-model")

        val suggestion = outcome.getOrThrow().suggestion
        assertEquals(TaskTransitionAction.NEXT_STEP, suggestion?.action)
        assertTrue(outcome.getOrThrow().tokensUsed > 0)
    }

    @Test
    fun `suggest surfaces a client failure as Result failure`() = runTest {
        val client = AdvisorFakeLlmClient(Result.failure(Exception("network down")))
        val advisor = TaskStateAdvisor(client)

        val outcome = advisor.suggest(executionState(), "hi", "test-model")

        assertTrue(outcome.isFailure)
    }
}
