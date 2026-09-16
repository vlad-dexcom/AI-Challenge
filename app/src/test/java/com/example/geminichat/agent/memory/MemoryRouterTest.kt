package com.example.geminichat.agent.memory

import com.example.geminichat.agent.AgentMessage
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the spec it was asked to complete, and returns a fixed canned routing JSON. */
private class FakeRouterClient(
    private val result: Result<String> = Result.success("""{"working":{},"long_term":{}}""")
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

class MemoryRouterTest {

    // --- parseRouting: pure JSON parsing, no LLM involved ---

    @Test
    fun `parseRouting reads separate working and long_term objects`() {
        val parsed = MemoryRouter.parseRouting(
            """{"working":{"task":"plan"},"long_term":{"goal_event":"half marathon"}}"""
        )

        assertEquals(mapOf("task" to "plan"), parsed?.working)
        assertEquals(mapOf("goal_event" to "half marathon"), parsed?.longTerm)
    }

    @Test
    fun `parseRouting tolerates a markdown code fence around the JSON`() {
        val parsed = MemoryRouter.parseRouting(
            "```json\n{\"working\":{},\"long_term\":{\"age\":\"34\"}}\n```"
        )

        assertEquals(mapOf("age" to "34"), parsed?.longTerm)
    }

    @Test
    fun `parseRouting defaults missing layers to empty maps`() {
        val parsed = MemoryRouter.parseRouting("""{"working":{"task":"plan"}}""")

        assertEquals(mapOf("task" to "plan"), parsed?.working)
        assertEquals(emptyMap<String, String>(), parsed?.longTerm)
    }

    @Test
    fun `parseRouting returns null for non-JSON text`() {
        assertNull(MemoryRouter.parseRouting("Sure! Here's what I remember."))
    }

    @Test
    fun `parseRouting returns null for blank text`() {
        assertNull(MemoryRouter.parseRouting("   "))
    }

    // --- applyGuardRules: the deterministic safety net over the LLM's raw output ---

    private val router = MemoryRouter(client = FakeRouterClient())

    @Test
    fun `applyGuardRules copies a pinned item through unchanged even if the router tries to drop it`() {
        val pinned = MemoryItem(
            key = "injury_left_shoulder",
            value = "no overhead press",
            source = MemorySource.USER,
            turn = 1,
            pinned = true
        )
        val previousLongTerm = MemorySnapshot(mapOf(pinned.key to pinned))
        // The router's reply omits the pinned key entirely and tries to add an unrelated one.
        val parsed = ParsedRouting(working = emptyMap(), longTerm = mapOf("goal_event" to "marathon"))

        val outcome = router.applyGuardRules(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = previousLongTerm,
            parsed = parsed,
            turn = 2,
            tokensUsed = 10
        )

        assertEquals(pinned, outcome.longTerm.items[pinned.key])
        assertEquals("marathon", outcome.longTerm.items["goal_event"]?.value)
    }

    @Test
    fun `applyGuardRules never lets the router overwrite a pinned item's value`() {
        val pinned = MemoryItem(
            key = "diet",
            value = "vegetarian",
            source = MemorySource.USER,
            turn = 1,
            pinned = true
        )
        val previousLongTerm = MemorySnapshot(mapOf(pinned.key to pinned))
        val parsed = ParsedRouting(working = emptyMap(), longTerm = mapOf("diet" to "vegan"))

        val outcome = router.applyGuardRules(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = previousLongTerm,
            parsed = parsed,
            turn = 2,
            tokensUsed = 10
        )

        assertEquals("vegetarian", outcome.longTerm.items["diet"]?.value)
    }

    @Test
    fun `applyGuardRules normalizes keys to snake_case`() {
        val parsed = ParsedRouting(working = mapOf("Preferred Stack!" to "Kotlin"), longTerm = emptyMap())

        val outcome = router.applyGuardRules(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = MemorySnapshot.EMPTY,
            parsed = parsed,
            turn = 1,
            tokensUsed = 5
        )

        assertEquals("Kotlin", outcome.working.items["preferred_stack"]?.value)
        assertNull(outcome.working.items["Preferred Stack!"])
    }

    @Test
    fun `applyGuardRules truncates values longer than the max length`() {
        val hugeValue = "x".repeat(MemoryRouter.MAX_VALUE_LENGTH + 50)
        val parsed = ParsedRouting(working = emptyMap(), longTerm = mapOf("dump" to hugeValue))

        val outcome = router.applyGuardRules(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = MemorySnapshot.EMPTY,
            parsed = parsed,
            turn = 1,
            tokensUsed = 5
        )

        assertEquals(MemoryRouter.MAX_VALUE_LENGTH, outcome.longTerm.items["dump"]?.value?.length)
    }

    @Test
    fun `applyGuardRules caps items per layer, keeping pinned and dropping the oldest unpinned`() {
        val pinned = MemoryItem("pinned_fact", "always kept", MemorySource.USER, turn = 1, pinned = true)
        val existingUnpinned = (1..MemoryRouter.MAX_ITEMS_PER_LAYER).associate { i ->
            "old_$i" to MemoryItem("old_$i", "value $i", MemorySource.ROUTER, turn = i, pinned = false)
        }
        val previousLongTerm = MemorySnapshot(existingUnpinned + (pinned.key to pinned))
        // Router proposes one brand-new fact on top of an already-full layer.
        val proposed = existingUnpinned.mapValues { it.value.value } + ("new_fact" to "new value")
        val parsed = ParsedRouting(working = emptyMap(), longTerm = proposed)

        val outcome = router.applyGuardRules(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = previousLongTerm,
            parsed = parsed,
            turn = 99,
            tokensUsed = 5
        )

        assertTrue(outcome.longTerm.items.size <= MemoryRouter.MAX_ITEMS_PER_LAYER)
        assertEquals(pinned, outcome.longTerm.items[pinned.key]) // pinned never dropped
        assertTrue(outcome.longTerm.items.containsKey("new_fact")) // newest kept
        assertFalse(outcome.longTerm.items.containsKey("old_1")) // oldest unpinned dropped first
    }

    // --- route: the suspend LLM call ---

    @Test
    fun `route sends both current layers and the new message to the client`() = runTest {
        val client = FakeRouterClient(
            Result.success("""{"working":{"task":"4-week plan"},"long_term":{"age":"34"}}""")
        )
        val realRouter = MemoryRouter(client = client)

        val outcome = realRouter.route(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = MemorySnapshot.EMPTY,
            recentContext = listOf(AgentMessage(AgentMessage.Role.USER, "I'm 34 and want a plan.")),
            newUserMessage = "Let's do 4 weeks.",
            turn = 2,
            model = "test-model"
        )

        assertTrue(outcome.isSuccess)
        val result = outcome.getOrThrow()
        assertEquals("34", result.longTerm.items["age"]?.value)
        assertEquals("4-week plan", result.working.items["task"]?.value)
        assertTrue(result.tokensUsed > 0)
        assertEquals(1, client.callCount)
        assertEquals("test-model", client.lastSpec?.model)
        assertTrue(client.lastSpec?.input.orEmpty().contains("Let's do 4 weeks."))
    }

    @Test
    fun `route falls back to previous layers unchanged when the reply is not valid JSON`() = runTest {
        val client = FakeRouterClient(Result.success("Sure, got it!"))
        val realRouter = MemoryRouter(client = client)
        val previousLongTerm = MemorySnapshot(
            mapOf("age" to MemoryItem("age", "34", MemorySource.ROUTER, turn = 1))
        )

        val outcome = realRouter.route(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = previousLongTerm,
            recentContext = emptyList(),
            newUserMessage = "Thanks",
            turn = 2,
            model = "test-model"
        )

        assertTrue(outcome.isSuccess)
        assertEquals(previousLongTerm, outcome.getOrThrow().longTerm)
    }

    @Test
    fun `route surfaces the client's failure`() = runTest {
        val failure = Exception("Router unavailable.")
        val realRouter = MemoryRouter(client = FakeRouterClient(Result.failure(failure)))

        val outcome = realRouter.route(
            previousWorking = MemorySnapshot.EMPTY,
            previousLongTerm = MemorySnapshot.EMPTY,
            recentContext = emptyList(),
            newUserMessage = "Hi",
            turn = 1,
            model = "test-model"
        )

        assertTrue(outcome.isFailure)
        assertEquals("Router unavailable.", outcome.exceptionOrNull()?.message)
    }
}
