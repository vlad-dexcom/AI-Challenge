package com.example.geminichat.agent.profile

import com.example.geminichat.agent.AgentConfig
import com.example.geminichat.agent.AgentRequest
import com.example.geminichat.agent.LlmAgent
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PersonalizationFakeLlmClient(private val result: Result<String>) : LlmClient {
    var lastSpec: LlmRequestSpec? = null
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        lastSpec = spec
        return result
    }
}

/**
 * Day 12's main proof: the same [Agent.handle] call for the same user question behaves
 * differently depending on the current [UserProfile] — automatically, with no participation
 * from the caller beyond passing [AgentRequest.userProfile] (exactly what
 * [com.example.geminichat.ChatViewModel.prepareRequestContext] does on every turn).
 */
class PersonalizationTest {

    private val testConfig = AgentConfig(
        id = "personal-trainer",
        displayName = "Personal Trainer",
        description = "test",
        systemInstruction = "You are a Personal Trainer.",
        model = "test-model"
    )

    @Test
    fun `an empty profile leaves the prompt identical to pre-Day-12 behavior`() = runTest {
        val client = PersonalizationFakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        agent.handle(AgentRequest(userMessage = "Составь план тренировки на неделю"))
        val withoutProfileArg = client.lastSpec

        agent.handle(
            AgentRequest(
                userMessage = "Составь план тренировки на неделю",
                userProfile = ProfileRenderer.render(UserProfile.EMPTY)
            )
        )
        val withEmptyProfile = client.lastSpec

        assertEquals(withoutProfileArg?.systemInstruction, withEmptyProfile?.systemInstruction)
        assertEquals(testConfig.systemInstruction, withEmptyProfile?.systemInstruction)
    }

    @Test
    fun `the same question produces a different system instruction under two different profile states`() = runTest {
        val client = PersonalizationFakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)

        val beginnerProfile = UserProfile(
            about = "новичок, тренируется дома без инвентаря",
            expertise = ExpertiseLevel.BEGINNER,
            tone = "дружелюбно и просто",
            format = "короткий список из 3-5 шагов",
            maxAnswerSentences = 5,
            constraints = listOf("нет доступа к залу")
        )
        val advancedProfile = UserProfile(
            about = "продвинутый атлет, готовится к соревнованиям",
            expertise = ExpertiseLevel.ADVANCED,
            tone = "технично, по делу",
            format = "таблица с процентами от 1ПМ",
            constraints = listOf("травма колена — без прыжков")
        )

        agent.handle(
            AgentRequest(
                userMessage = "Составь план тренировки на неделю",
                userProfile = ProfileRenderer.render(beginnerProfile)
            )
        )
        val beginnerSpec = client.lastSpec

        agent.handle(
            AgentRequest(
                userMessage = "Составь план тренировки на неделю",
                userProfile = ProfileRenderer.render(advancedProfile)
            )
        )
        val advancedSpec = client.lastSpec

        // Same question, same agent persona — but the system instruction differs because the
        // profile is folded in, and each profile's own directives show up in its own prompt.
        assertTrue(beginnerSpec?.systemInstruction != advancedSpec?.systemInstruction)
        assertTrue(beginnerSpec?.systemInstruction.orEmpty().contains("нет доступа к залу"))
        assertTrue(!beginnerSpec?.systemInstruction.orEmpty().contains("травма колена"))
        assertTrue(advancedSpec?.systemInstruction.orEmpty().contains("травма колена"))
        assertTrue(!advancedSpec?.systemInstruction.orEmpty().contains("нет доступа к залу"))
        // Both still carry the agent's own persona — personalization augments it, not replaces it.
        assertTrue(beginnerSpec?.systemInstruction.orEmpty().contains(testConfig.systemInstruction))
        assertTrue(advancedSpec?.systemInstruction.orEmpty().contains(testConfig.systemInstruction))
        // The user-turn prompt itself is unaffected by the profile (it lives in the system
        // instruction, not mixed into the conversational input).
        assertEquals(beginnerSpec?.input, advancedSpec?.input)
    }

    @Test
    fun `the profile is applied automatically without the caller repeating anything in the message`() = runTest {
        val client = PersonalizationFakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)
        val profile = UserProfile(tone = "очень кратко")

        agent.handle(
            AgentRequest(
                userMessage = "Что мне делать сегодня?",
                userProfile = ProfileRenderer.render(profile)
            )
        )

        assertTrue(client.lastSpec?.systemInstruction.orEmpty().contains("очень кратко"))
        assertEquals("Что мне делать сегодня?", client.lastSpec?.input)
    }

    @Test
    fun `profile tokens are counted and included in the prompt token budget`() = runTest {
        val client = PersonalizationFakeLlmClient(Result.success("ok"))
        val agent = LlmAgent(config = testConfig, client = client)
        val profile = UserProfile(about = "довольно длинное описание пользователя для теста токенов")

        val result = agent.handle(
            AgentRequest(
                userMessage = "Hi",
                userProfile = ProfileRenderer.render(profile)
            )
        )

        val usage = result.getOrThrow().tokenUsage
        assertTrue(usage.profileTokens > 0)
        assertEquals(
            usage.requestTokens + usage.historyTokens + usage.longTermMemoryTokens +
                usage.workingMemoryTokens + usage.profileTokens + usage.systemInstructionTokens,
            usage.promptTokens
        )
    }
}
