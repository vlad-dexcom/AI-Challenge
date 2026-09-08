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
}
