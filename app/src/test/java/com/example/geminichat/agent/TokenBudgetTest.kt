package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Demonstrates Day 8's token accounting end-to-end: how token counts grow across a short vs. a
 * long dialog, and what happens when a dialog exceeds the model's context window.
 *
 * A [BudgetFakeLlmClient] here can report an arbitrary [LlmClient.contextWindowTokens], which is what
 * lets the "overflow" scenario be reproduced deterministically and offline — no real model has
 * a window small enough to trigger on demand.
 */
private class BudgetFakeLlmClient(
    private val result: Result<String> = Result.success("A short reply."),
    private val fakeContextWindowTokens: Int = LlmClient.DEFAULT_CONTEXT_WINDOW_TOKENS
) : LlmClient {
    var lastSpec: LlmRequestSpec? = null
        private set
    var callCount: Int = 0
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        lastSpec = spec
        callCount++
        return result
    }

    override fun contextWindowTokens(model: String): Int = fakeContextWindowTokens
}

class TokenBudgetTest {

    private val config = AgentConfig(
        id = "budget-test-agent",
        displayName = "Budget Test Agent",
        description = "An agent used only to test token budgeting.",
        systemInstruction = "You are a concise assistant.",
        model = "test-model"
    )

    private fun turn(user: String, agentReply: String) = listOf(
        AgentMessage(role = AgentMessage.Role.USER, text = user),
        AgentMessage(role = AgentMessage.Role.AGENT, text = agentReply)
    )

    @Test
    fun `short dialog uses few tokens and succeeds`() = runTest {
        val client = BudgetFakeLlmClient(result = Result.success("Sure, here you go."))
        val agent = LlmAgent(config = config, client = client)

        val result = agent.handle(AgentRequest(userMessage = "Hi, how are you?"))

        assertTrue(result.isSuccess)
        val usage = result.getOrThrow().tokenUsage
        assertEquals(0, usage.historyTokens)
        // A short greeting should be a handful of tokens, nowhere near a real context window.
        assertTrue("expected a small prompt, got ${usage.promptTokens}", usage.promptTokens < 50)
        assertTrue(usage.totalTokens < 100)
    }

    @Test
    fun `long dialog accumulates more history tokens turn over turn`() = runTest {
        val client = BudgetFakeLlmClient(result = Result.success("Noted, continuing the plan."))
        val agent = LlmAgent(config = config, client = client)

        // Build up a long-running conversation by feeding back growing history each turn,
        // the same way ChatViewModel.sendMessage snapshots the visible chat before calling
        // the agent.
        var history = emptyList<AgentMessage>()
        val promptTokensPerTurn = mutableListOf<Int>()
        repeat(8) { turnIndex ->
            val userMessage = "Turn $turnIndex: please expand on the training plan in detail."
            val result = agent.handle(AgentRequest(userMessage = userMessage, history = history))
            assertTrue(result.isSuccess)
            val response = result.getOrThrow()
            promptTokensPerTurn += response.tokenUsage.promptTokens
            history = history + turn(userMessage, response.text)
        }

        // Token cost should grow monotonically as history accumulates — this is the
        // "long dialog" comparison: the last turn costs strictly more prompt tokens than the
        // first turn because every prior turn is replayed back into the model.
        for (i in 1 until promptTokensPerTurn.size) {
            assertTrue(
                "expected turn $i (${promptTokensPerTurn[i]}) to cost more than turn ${i - 1} " +
                    "(${promptTokensPerTurn[i - 1]})",
                promptTokensPerTurn[i] > promptTokensPerTurn[i - 1]
            )
        }
        assertTrue(promptTokensPerTurn.last() > promptTokensPerTurn.first() * 4)
    }

    @Test
    fun `dialog exceeding the model's context window fails without calling the client`() = runTest {
        // A config with a small reserved-output budget and a tiny fake context window (300
        // tokens) that a realistic multi-turn history blows past, simulating what "overflow"
        // looks like for any model without needing a real 1M-token conversation to trigger it.
        val smallWindowConfig = config.copy(maxOutputTokens = 20)
        val client = BudgetFakeLlmClient(fakeContextWindowTokens = 300)
        val agent = LlmAgent(config = smallWindowConfig, client = client)

        val longHistory = (0 until 20).flatMap { i ->
            turn(
                user = "Message number $i with a fair amount of extra padding text to grow tokens.",
                agentReply = "Reply number $i, also padded out with a bit more filler content."
            )
        }

        val result = agent.handle(AgentRequest(userMessage = "One more question, please.", history = longHistory))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is ContextWindowExceededException)
        error as ContextWindowExceededException
        assertTrue(error.promptTokens + error.reservedOutputTokens > error.contextWindowTokens)
        assertEquals(300, error.contextWindowTokens)
        assertEquals(20, error.reservedOutputTokens)

        // The whole point of the preemptive guard: the client is never even called, so there
        // is no truncated/garbled request sent anywhere — the failure is immediate and clean.
        assertEquals(0, client.callCount)
        assertNull(client.lastSpec)
    }

    @Test
    fun `a short dialog with the same tiny context window still succeeds`() = runTest {
        // Sanity check that the guard is about the *dialog's* size, not an unconditional cap:
        // the same tiny window that fails the long-dialog test above is fine for one short turn.
        val smallWindowConfig = config.copy(maxOutputTokens = 20)
        val client = BudgetFakeLlmClient(fakeContextWindowTokens = 300)
        val agent = LlmAgent(config = smallWindowConfig, client = client)

        val result = agent.handle(AgentRequest(userMessage = "Hi"))

        assertTrue(result.isSuccess)
        assertEquals(1, client.callCount)
    }
}
