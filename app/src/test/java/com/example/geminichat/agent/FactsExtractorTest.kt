package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the spec it was asked to complete, and always returns a fixed canned facts JSON. */
private class FakeFactsClient(
    private val result: Result<String> = Result.success("""{"goal":"run a 10k"}""")
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
}

class FactsExtractorTest {

    // --- parseFacts: pure JSON parsing, no LLM involved ---

    @Test
    fun `parseFacts reads a flat JSON object into a string map`() {
        val facts = FactsExtractor.parseFacts("""{"goal":"run a 10k","deadline":"in 8 weeks"}""")

        assertEquals(mapOf("goal" to "run a 10k", "deadline" to "in 8 weeks"), facts)
    }

    @Test
    fun `parseFacts tolerates a markdown code fence around the JSON`() {
        val facts = FactsExtractor.parseFacts(
            "```json\n{\"goal\":\"run a 10k\"}\n```"
        )

        assertEquals(mapOf("goal" to "run a 10k"), facts)
    }

    @Test
    fun `parseFacts returns null for non-JSON text`() {
        assertNull(FactsExtractor.parseFacts("Sure! Here's a summary of what we discussed."))
    }

    @Test
    fun `parseFacts returns null for blank text`() {
        assertNull(FactsExtractor.parseFacts("   "))
    }

    // --- render: pure formatting ---

    @Test
    fun `render formats facts as key-value bullet lines`() {
        val extractor = FactsExtractor(client = FakeFactsClient())

        val rendered = extractor.render(linkedMapOf("goal" to "run a 10k", "deadline" to "8 weeks"))

        assertEquals("- goal: run a 10k\n- deadline: 8 weeks", rendered)
    }

    @Test
    fun `render is empty for an empty facts map`() {
        val extractor = FactsExtractor(client = FakeFactsClient())

        assertEquals("", extractor.render(emptyMap()))
    }

    // --- extract: the suspend LLM call ---

    @Test
    fun `extract sends previous facts and the new user message to the client`() = runTest {
        val client = FakeFactsClient(Result.success("""{"goal":"run a 10k","pace":"9 min/mile"}"""))
        val extractor = FactsExtractor(client = client)

        val outcome = extractor.extract(
            previousFacts = mapOf("goal" to "run a 10k"),
            recentContext = listOf(
                AgentMessage(role = AgentMessage.Role.USER, text = "I want to run a 10k."),
                AgentMessage(role = AgentMessage.Role.AGENT, text = "Great, what's your target pace?")
            ),
            newUserMessage = "Around 9 minutes per mile.",
            model = "test-model"
        )

        assertTrue(outcome.isSuccess)
        assertEquals(mapOf("goal" to "run a 10k", "pace" to "9 min/mile"), outcome.getOrThrow().facts)
        assertTrue(outcome.getOrThrow().tokensUsed > 0)
        assertEquals(1, client.callCount)
        val input = client.lastSpec?.input.orEmpty()
        assertTrue(input.contains("run a 10k"))
        assertTrue(input.contains("Around 9 minutes per mile."))
        assertEquals("test-model", client.lastSpec?.model)
    }

    @Test
    fun `extract falls back to previous facts when the reply is not valid JSON`() = runTest {
        val client = FakeFactsClient(Result.success("Sure, updated!"))
        val extractor = FactsExtractor(client = client)
        val previous = mapOf("goal" to "run a 10k")

        val outcome = extractor.extract(
            previousFacts = previous,
            recentContext = emptyList(),
            newUserMessage = "Thanks",
            model = "test-model"
        )

        assertTrue(outcome.isSuccess)
        assertEquals(previous, outcome.getOrThrow().facts)
    }

    @Test
    fun `extract surfaces the client's failure`() = runTest {
        val failure = Exception("Facts extractor unavailable.")
        val extractor = FactsExtractor(client = FakeFactsClient(Result.failure(failure)))

        val outcome = extractor.extract(
            previousFacts = emptyMap(),
            recentContext = emptyList(),
            newUserMessage = "Hi",
            model = "test-model"
        )

        assertTrue(outcome.isFailure)
        assertEquals("Facts extractor unavailable.", outcome.exceptionOrNull()?.message)
    }
}
