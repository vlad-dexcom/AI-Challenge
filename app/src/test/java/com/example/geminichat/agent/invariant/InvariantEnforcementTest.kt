package com.example.geminichat.agent.invariant

import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.geminichat.agent.profile.ProfileRenderer
import com.example.geminichat.agent.profile.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeLlmClient(private val result: Result<String> = Result.success("ok")) : LlmClient {
    var callCount: Int = 0
        private set
    var lastSpec: LlmRequestSpec? = null
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        callCount++
        lastSpec = spec
        return result
    }
}

/**
 * Day 14 end-to-end: proves [LlmAgent.handle] actually enforces invariants — not just that
 * [InvariantGuard] can detect a conflict in isolation.
 */
class InvariantEnforcementTest {

    private val config = AgentConfig(
        id = "trainer-test",
        displayName = "Trainer",
        description = "Test trainer.",
        systemInstruction = "You are a trainer.",
        model = "test-model"
    )

    @Test
    fun `a conflicting request never reaches the client and is refused with the invariant id`() = runTest {
        val client = FakeLlmClient()
        val agent = LlmAgent(config = config, client = client, invariants = InvariantSet.DEFAULTS)

        val result = agent.handle(AgentRequest(userMessage = "Составь программу со штангой в тренажёрном зале"))

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(0, client.callCount)
        assertTrue(response.refusedByInvariantIds.contains("home-equipment-only"))
        assertTrue(response.text.contains("home-equipment-only"))
        assertEquals(0, response.tokenUsage.totalTokens)
    }

    @Test
    fun `the same request succeeds normally once the invariant is disabled`() = runTest {
        val disabled = InvariantSet.DEFAULTS.copy(
            invariants = InvariantSet.DEFAULTS.invariants.map {
                if (it.id == "home-equipment-only") it.copy(enabled = false) else it
            }
        )
        val client = FakeLlmClient(Result.success("Here is a gym program."))
        val agent = LlmAgent(config = config, client = client, invariants = disabled)

        val result = agent.handle(AgentRequest(userMessage = "Составь программу со штангой в тренажёрном зале"))

        assertTrue(result.isSuccess)
        assertEquals(1, client.callCount)
        assertTrue(result.getOrThrow().refusedByInvariantIds.isEmpty())
        assertEquals("Here is a gym program.", result.getOrThrow().text)
    }

    @Test
    fun `an ordinary request is sent with the invariants block appended after the profile block`() = runTest {
        val client = FakeLlmClient()
        val agent = LlmAgent(config = config, client = client, invariants = InvariantSet.DEFAULTS)
        val profileBlock = ProfileRenderer.render(UserProfile.STARTER)
        val invariantsBlock = InvariantRenderer.render(InvariantSet.DEFAULTS)

        agent.handle(
            AgentRequest(
                userMessage = "Дай план на неделю с отжиманиями",
                userProfile = profileBlock,
                invariants = invariantsBlock
            )
        )

        val systemInstruction = client.lastSpec?.systemInstruction.orEmpty()
        assertTrue(systemInstruction.contains(profileBlock))
        assertTrue(systemInstruction.contains(invariantsBlock))
        assertTrue(
            "invariants block must come after the profile block",
            systemInstruction.indexOf(invariantsBlock) > systemInstruction.indexOf(profileBlock)
        )
    }

    @Test
    fun `an agent with the default empty invariant set behaves exactly as before Day 14`() = runTest {
        val client = FakeLlmClient()
        val agent = LlmAgent(config = config, client = client)

        val result = agent.handle(AgentRequest(userMessage = "Составь программу со штангой в тренажёрном зале"))

        assertTrue(result.isSuccess)
        assertEquals(1, client.callCount)
        assertTrue(result.getOrThrow().refusedByInvariantIds.isEmpty())
    }

    @Test
    fun `a profile constraint that contradicts a locked invariant cannot override it`() = runTest {
        val client = FakeLlmClient()
        val agent = LlmAgent(config = config, client = client, invariants = InvariantSet.DEFAULTS)
        val contradictingProfile = UserProfile(constraints = listOf("хочу заниматься со штангой"))

        val result = agent.handle(
            AgentRequest(
                userMessage = "Составь программу со штангой в тренажёрном зале",
                userProfile = ProfileRenderer.render(contradictingProfile)
            )
        )

        assertTrue(result.isSuccess)
        assertEquals(0, client.callCount)
        assertTrue(result.getOrThrow().refusedByInvariantIds.contains("home-equipment-only"))
    }
}
