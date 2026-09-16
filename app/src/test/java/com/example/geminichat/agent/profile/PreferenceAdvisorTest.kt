package com.example.geminichat.agent.profile

import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class AdvisorFakeLlmClient(private val result: Result<String>) : LlmClient {
    var lastSpec: LlmRequestSpec? = null
        private set

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        lastSpec = spec
        return result
    }
}

class PreferenceAdvisorTest {

    @Test
    fun `parseSuggestion returns null for the JSON literal null`() {
        assertNull(PreferenceAdvisor.parseSuggestion("null"))
    }

    @Test
    fun `parseSuggestion returns null for garbage text`() {
        assertNull(PreferenceAdvisor.parseSuggestion("not json at all"))
    }

    @Test
    fun `parseSuggestion parses a valid field change`() {
        val suggestion = PreferenceAdvisor.parseSuggestion(
            """{"field": "tone", "value": "более формально", "reason": "user asked for a formal tone"}"""
        )

        assertEquals(ProfileField.TONE, suggestion?.field)
        assertEquals("более формально", suggestion?.value)
        assertEquals("user asked for a formal tone", suggestion?.reason)
    }

    @Test
    fun `parseSuggestion tolerates a markdown code fence`() {
        val suggestion = PreferenceAdvisor.parseSuggestion(
            "```json\n{\"field\": \"format\", \"value\": \"bullet list\", \"reason\": \"asked for lists\"}\n```"
        )

        assertEquals(ProfileField.FORMAT, suggestion?.field)
    }

    @Test
    fun `parseSuggestion returns null for an unrecognized field name`() {
        val suggestion = PreferenceAdvisor.parseSuggestion(
            """{"field": "favorite_color", "value": "blue", "reason": "irrelevant"}"""
        )

        assertNull(suggestion)
    }

    @Test
    fun `parseSuggestion returns null when value is missing or blank`() {
        assertNull(PreferenceAdvisor.parseSuggestion("""{"field": "tone", "value": "", "reason": "x"}"""))
        assertNull(PreferenceAdvisor.parseSuggestion("""{"field": "tone"}"""))
    }

    @Test
    fun `parseSuggestion recognizes the constraint pseudo-fields`() {
        val add = PreferenceAdvisor.parseSuggestion(
            """{"field": "add_constraint", "value": "no jumping", "reason": "mentioned knee injury"}"""
        )
        assertEquals(ProfileField.ADD_CONSTRAINT, add?.field)

        val remove = PreferenceAdvisor.parseSuggestion(
            """{"field": "remove_constraint", "value": "no jumping", "reason": "knee healed"}"""
        )
        assertEquals(ProfileField.REMOVE_CONSTRAINT, remove?.field)
    }

    @Test
    fun `suggest never throws on a malformed reply and reports no suggestion`() = runTest {
        val client = AdvisorFakeLlmClient(Result.success("complete garbage, not json"))
        val advisor = PreferenceAdvisor(client)

        val outcome = advisor.suggest(UserProfile.EMPTY, "отвечай короче", "test-model")

        assertTrue(outcome.isSuccess)
        assertNull(outcome.getOrThrow().suggestion)
    }

    @Test
    fun `suggest surfaces a valid suggestion from the client's reply`() = runTest {
        val client = AdvisorFakeLlmClient(
            Result.success("""{"field": "format", "value": "короткий список", "reason": "user asked to keep it short"}""")
        )
        val advisor = PreferenceAdvisor(client)

        val outcome = advisor.suggest(UserProfile.EMPTY, "отвечай короче списком", "test-model")

        val suggestion = outcome.getOrThrow().suggestion
        assertEquals(ProfileField.FORMAT, suggestion?.field)
        assertEquals("короткий список", suggestion?.value)
        assertTrue(outcome.getOrThrow().tokensUsed > 0)
    }

    @Test
    fun `suggest surfaces a client failure as Result failure`() = runTest {
        val client = AdvisorFakeLlmClient(Result.failure(Exception("network down")))
        val advisor = PreferenceAdvisor(client)

        val outcome = advisor.suggest(UserProfile.EMPTY, "hi", "test-model")

        assertTrue(outcome.isFailure)
    }
}
