package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fake [LlmClient] so [LlmAgent] can be tested without any network/Android dependency.
 * Records the last [LlmRequestSpec] it received so tests can assert the agent built the
 * prompt/config correctly.
 */
private class FakeLlmClient(
    private val result: Result<String>
) : LlmClient {
    var lastSpec: LlmRequestSpec? = null
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        lastSpec = spec
        return result
    }
}

class LlmAgentTest {

    private val testConfig = AgentConfig(
        id = "test-agent",
        displayName = "Test Agent",
        description = "An agent used only in tests.",
        systemInstruction = "You are a test agent.",
        model = "test-model"
    )

    @Test
    fun `handle returns trimmed answer and agent metadata on success`() = runTest {
        val client = FakeLlmClient(Result.success("  Here is your answer.  "))
        val agent = LlmAgent(config = testConfig, client = client)

        val result = agent.handle(AgentRequest(userMessage = "How do I squat?"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals("Here is your answer.", response.text)
        assertEquals(testConfig.id, response.agentId)
        assertEquals(testConfig.model, response.model)
        // Token accounting: no history on this turn, so historyTokens is zero and
        // promptTokens is just the request + system instruction; completionTokens reflects
        // the (trimmed) answer text.
        assertEquals(0, response.tokenUsage.historyTokens)
        assertTrue(response.tokenUsage.requestTokens > 0)
        assertTrue(response.tokenUsage.completionTokens > 0)
        assertEquals(
            response.tokenUsage.requestTokens + response.tokenUsage.historyTokens +
                response.tokenUsage.systemInstructionTokens,
            response.tokenUsage.promptTokens
        )
        assertEquals(
            response.tokenUsage.promptTokens + response.tokenUsage.completionTokens,
            response.tokenUsage.totalTokens
        )
    }

    @Test
    fun `handle sends the agent's system instruction to the client`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        agent.handle(AgentRequest(userMessage = "Plan my week"))

        assertEquals(testConfig.systemInstruction, client.lastSpec?.systemInstruction)
        assertEquals("Plan my week", client.lastSpec?.input)
        assertEquals(testConfig.model, client.lastSpec?.model)
    }

    @Test
    fun `handle honors a model override without changing the client's other params`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        agent.handle(AgentRequest(userMessage = "Hi", modelOverride = "override-model"))

        assertEquals("override-model", client.lastSpec?.model)
    }

    @Test
    fun `handle rejects blank input without calling the client`() = runTest {
        val client = FakeLlmClient(Result.success("should not be used"))
        val agent = LlmAgent(config = testConfig, client = client)

        val result = agent.handle(AgentRequest(userMessage = "   "))

        assertTrue(result.isFailure)
        assertEquals(null, client.lastSpec)
    }

    @Test
    fun `handle surfaces the client's failure`() = runTest {
        val failure = Exception("Rate limit exceeded.")
        val client = FakeLlmClient(Result.failure(failure))
        val agent = LlmAgent(config = testConfig, client = client)

        val result = agent.handle(AgentRequest(userMessage = "Hi"))

        assertTrue(result.isFailure)
        assertEquals("Rate limit exceeded.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `handle fails when the client returns a blank answer`() = runTest {
        val client = FakeLlmClient(Result.success("   "))
        val agent = LlmAgent(config = testConfig, client = client)

        val result = agent.handle(AgentRequest(userMessage = "Hi"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `handle folds history into the prompt when non-empty`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        val history = listOf(
            AgentMessage(role = AgentMessage.Role.USER, text = "What's a good warm-up?"),
            AgentMessage(role = AgentMessage.Role.AGENT, text = "Try 5 minutes of light cardio.")
        )
        agent.handle(AgentRequest(userMessage = "And after that?", history = history))

        val renderedInput = client.lastSpec?.input.orEmpty()
        assertTrue(renderedInput.contains("User: What's a good warm-up?"))
        assertTrue(renderedInput.contains("${testConfig.displayName}: Try 5 minutes of light cardio."))
        assertTrue(renderedInput.endsWith("User: And after that?"))
    }

    @Test
    fun `handle sends only the user message when history is empty`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        agent.handle(AgentRequest(userMessage = "Hi there"))

        assertEquals("Hi there", client.lastSpec?.input)
    }

    /**
     * Day 11's "как это влияет на ответы агента" check: a fact mentioned once, early in a long
     * conversation, has long since scrolled out of the recent-history window the ViewModel keeps
     * (see [com.example.geminichat.agent.memory.MemoryRouter.RECENT_CONTEXT_SIZE]) — simulated
     * here by *not* including it in [AgentRequest.history] at all, exactly as
     * `ChatViewModel.prepareRequestContext` builds `history.takeLast(RECENT_CONTEXT_SIZE)`.
     * Without memory layers the model would simply never see it again. With
     * [AgentRequest.longTermMemory] carrying it forward, it still reaches the prompt — so the
     * agent can still answer correctly.
     */
    @Test
    fun `long-term memory keeps an old fact in the prompt after it has fallen out of recent history`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        // Recent history no longer contains anything about the shoulder injury mentioned turns
        // ago; only long-term memory carries it forward.
        val recentHistory = listOf(
            AgentMessage(role = AgentMessage.Role.USER, text = "What's next in my plan?"),
            AgentMessage(role = AgentMessage.Role.AGENT, text = "Week 3: add light strength work.")
        )

        agent.handle(
            AgentRequest(
                userMessage = "Can I bench press now?",
                history = recentHistory,
                longTermMemory = "- injury_shoulder: cannot do overhead press"
            )
        )

        val renderedInput = client.lastSpec?.input.orEmpty()
        assertTrue(renderedInput.contains("injury_shoulder"))
        assertTrue(!renderedInput.contains("What's a good warm-up?"))
    }

    @Test
    fun `without long-term memory an old fact absent from recent history never reaches the prompt`() = runTest {
        val client = FakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        val recentHistory = listOf(
            AgentMessage(role = AgentMessage.Role.USER, text = "What's next in my plan?"),
            AgentMessage(role = AgentMessage.Role.AGENT, text = "Week 3: add light strength work.")
        )

        agent.handle(AgentRequest(userMessage = "Can I bench press now?", history = recentHistory))

        val renderedInput = client.lastSpec?.input.orEmpty()
        assertTrue(!renderedInput.contains("injury_shoulder"))
    }
}
