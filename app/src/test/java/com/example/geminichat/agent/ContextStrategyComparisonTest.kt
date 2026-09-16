package com.example.geminichat.agent

import com.example.geminichat.agent.memory.MemoryAssembler
import com.example.geminichat.agent.memory.MemoryRouter
import com.example.geminichat.agent.memory.MemorySnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake client whose replies are fixed strings regardless of what it's asked (chat turn,
 * summarization, or facts extraction) — good enough to make total token cost comparable and
 * deterministic across strategies and runs. Facts extraction gets a small, growing JSON blob so
 * the strategy's cost realistically increases a little as more facts accumulate.
 */
private class StrategyComparisonFakeClient : LlmClient {
    private var factsCallCount = 0

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        val looksLikeFactsExtraction = spec.systemInstruction?.contains("key-value memory") == true
        return if (looksLikeFactsExtraction) {
            factsCallCount++
            Result.success("""{"goal":"ship the spec","turn":"$factsCallCount"}""")
        } else {
            Result.success("Noted, continuing the plan with more detail as requested.")
        }
    }
}

/**
 * Fake client for [ContextStrategy.MEMORY_LAYERS]: recognizes [MemoryRouter]'s system
 * instruction and returns a small, deterministic routing JSON — a shoulder injury and age are
 * classified into long-term memory starting from turn 2 and never removed again (mirrors a
 * real profile fact that stays true for the rest of the conversation), while working memory
 * just tracks "the current turn's task", growing and changing every turn. Any other request
 * (the actual chat turn) gets a fixed reply, same as [StrategyComparisonFakeClient].
 */
private class MemoryLayersFakeClient : LlmClient {
    private var routingCallCount = 0

    override suspend fun complete(spec: LlmRequestSpec): Result<String> {
        val looksLikeRouting = spec.systemInstruction?.contains("WORKING memory") == true
        if (!looksLikeRouting) {
            return Result.success("Noted, continuing the plan with more detail as requested.")
        }
        routingCallCount++
        val longTerm = if (routingCallCount >= 2) {
            """{"age":"34-year-old runner","injury_shoulder":"no overhead press, mentioned turn 2"}"""
        } else {
            "{}"
        }
        return Result.success(
            """{"working":{"task":"working on turn $routingCallCount"},"long_term":$longTerm}"""
        )
    }
}

/**
 * Day 10's headline deliverable: the same 30-turn simulated "gathering a spec" dialog run once
 * per [ContextStrategy], with a deterministic fake client, so their cumulative prompt-token
 * cost is directly, reproducibly comparable (mirrors
 * [com.example.geminichat.agent.ContextCompressionComparisonTest] from Day 9, extended to all
 * four strategies).
 *
 * Response quality and stability still need a live model + human judgement — see
 * docs/day10-context-strategies.md for that methodology; this test only proves the token-cost
 * half automatically.
 */
class ContextStrategyComparisonTest {

    private val config = AgentConfig(
        id = "comparison-agent",
        displayName = "Comparison Agent",
        description = "An agent used only to compare context strategies.",
        systemInstruction = "You are a concise assistant gathering a spec.",
        model = "test-model"
    )

    private fun userTurn(index: Int) =
        "Turn $index: here is another requirement for the spec, please account for everything " +
            "discussed so far and ask a clarifying question if needed."

    @Test
    fun `sliding window and facts cost meaningfully fewer tokens than full history over a long dialog`() = runTest {
        val turnCount = 30

        val fullHistoryTotal = runFullHistory(turnCount)
        val slidingWindowTotal = runSlidingWindow(turnCount)
        val summaryTotal = runSummary(turnCount)
        val factsTotal = runFacts(turnCount)

        assertTrue(
            "sliding window ($slidingWindowTotal) should cost fewer tokens than full history ($fullHistoryTotal)",
            slidingWindowTotal < fullHistoryTotal
        )
        assertTrue(
            "summary ($summaryTotal) should cost fewer tokens than full history ($fullHistoryTotal)",
            summaryTotal < fullHistoryTotal
        )
        assertTrue(
            "facts ($factsTotal) should cost fewer tokens than full history ($fullHistoryTotal)",
            factsTotal < fullHistoryTotal
        )
    }

    /** Mirrors [ContextStrategy.FULL_HISTORY]: the full raw history every turn. */
    private suspend fun runFullHistory(turnCount: Int): Int {
        val client = StrategyComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        var history = emptyList<AgentMessage>()
        var total = 0

        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val response = agent.handle(AgentRequest(userMessage = userMessage, history = history)).getOrThrow()
            total += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return total
    }

    /** Mirrors [ContextStrategy.SLIDING_WINDOW]: only the last N messages, no LLM overhead. */
    private suspend fun runSlidingWindow(turnCount: Int): Int {
        val client = StrategyComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        var history = emptyList<AgentMessage>()
        var total = 0

        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val response = agent.handle(
                AgentRequest(
                    userMessage = userMessage,
                    history = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
                )
            ).getOrThrow()
            total += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return total
    }

    /** Mirrors [ContextStrategy.SUMMARY] (Day 9's [HistoryCompressor]-backed folding). */
    private suspend fun runSummary(turnCount: Int): Int {
        val client = StrategyComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        val compressor = HistoryCompressor(client = client)

        var history = emptyList<AgentMessage>()
        var summary: String? = null
        var summarizedCount = 0
        var total = 0

        repeat(turnCount) { i ->
            val foldRange = compressor.pendingFoldRange(history.size, summarizedCount)
            if (foldRange != null) {
                val outcome = compressor.fold(
                    previousSummary = summary,
                    messagesToFold = history.subList(foldRange.first, foldRange.last + 1),
                    model = config.model
                ).getOrThrow()
                summary = outcome.summary
                summarizedCount = foldRange.last + 1
                total += outcome.tokensUsed
            }

            val userMessage = userTurn(i)
            val response = agent.handle(
                AgentRequest(
                    userMessage = userMessage,
                    history = compressor.recentTail(history),
                    summary = summary
                )
            ).getOrThrow()
            total += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return total
    }

    /** Mirrors [ContextStrategy.FACTS] (Day 10's [FactsExtractor]-backed sticky memory). */
    private suspend fun runFacts(turnCount: Int): Int {
        val client = StrategyComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        val extractor = FactsExtractor(client = client)

        var history = emptyList<AgentMessage>()
        var facts = emptyMap<String, String>()
        var total = 0

        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val recentContext = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
            val outcome = extractor.extract(
                previousFacts = facts,
                recentContext = recentContext,
                newUserMessage = userMessage,
                model = config.model
            ).getOrThrow()
            facts = outcome.facts
            total += outcome.tokensUsed

            val response = agent.handle(
                AgentRequest(
                    userMessage = userMessage,
                    history = recentContext,
                    facts = extractor.render(facts).ifBlank { null }
                )
            ).getOrThrow()
            total += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return total
    }

    /**
     * Mirrors [ContextStrategy.MEMORY_LAYERS] (Day 11's [MemoryRouter]-backed long-term/working
     * split). Returns the cumulative token cost *and* the final long-term/working memory
     * snapshots, so a single run can feed both the cost comparison and the recall assertion.
     */
    private suspend fun runMemoryLayers(turnCount: Int): MemoryLayersRunResult {
        val client = MemoryLayersFakeClient()
        val agent = LlmAgent(config = config, client = client)
        val router = MemoryRouter(client = client)

        var history = emptyList<AgentMessage>()
        var working = MemorySnapshot.EMPTY
        var longTerm = MemorySnapshot.EMPTY
        var total = 0

        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val recentContext = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
            val turn = i + 1

            val routing = router.route(
                previousWorking = working,
                previousLongTerm = longTerm,
                recentContext = recentContext,
                newUserMessage = userMessage,
                turn = turn,
                model = config.model
            ).getOrThrow()
            working = routing.working
            longTerm = routing.longTerm
            total += routing.tokensUsed

            val assembled = MemoryAssembler.assemble(longTerm, working)
            val response = agent.handle(
                AgentRequest(
                    userMessage = userMessage,
                    history = recentContext,
                    longTermMemory = assembled.longTermBlock.ifBlank { null },
                    workingMemory = assembled.workingBlock.ifBlank { null }
                )
            ).getOrThrow()
            total += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }

        return MemoryLayersRunResult(
            totalTokens = total,
            finalHistory = history,
            finalLongTermBlock = MemoryAssembler.assemble(longTerm, working).longTermBlock
        )
    }

    private data class MemoryLayersRunResult(
        val totalTokens: Int,
        val finalHistory: List<AgentMessage>,
        val finalLongTermBlock: String
    )

    @Test
    fun `memory layers cost fewer tokens than full history over a long dialog`() = runTest {
        // Full history's cost grows quadratically with turn count (every past turn is resent
        // every time), while memory layers' routing overhead per turn stays roughly constant —
        // a longer dialog is used here so that crossover is clearly visible.
        val turnCount = 60

        val fullHistoryTotal = runFullHistory(turnCount)
        val memoryLayersTotal = runMemoryLayers(turnCount).totalTokens
        assertTrue(
            "memory layers ($memoryLayersTotal) should cost fewer tokens than full history ($fullHistoryTotal)",
            memoryLayersTotal < fullHistoryTotal
        )
    }

    /**
     * The key "how does the memory model affect answers" proof point from the plan: a fact
     * mentioned once early in the dialog (a shoulder injury, turn 2) must still be visible in
     * the prompt at turn 30 under [ContextStrategy.MEMORY_LAYERS] — because it was classified
     * into long-term memory, which is rendered every turn regardless of how old it is — while
     * under [ContextStrategy.SLIDING_WINDOW] the same raw turn has long since scrolled out of
     * the last [ContextStrategy.SLIDING_WINDOW_SIZE] messages and is invisible to the model.
     */
    @Test
    fun `memory layers still recall an early fact at turn 30, unlike a plain sliding window`() = runTest {
        val turnCount = 30

        val memoryLayersResult = runMemoryLayers(turnCount)
        assertTrue(
            "expected the long-term block to still contain the shoulder injury at turn $turnCount",
            memoryLayersResult.finalLongTermBlock.contains("injury_shoulder")
        )

        val client = StrategyComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        var history = emptyList<AgentMessage>()
        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val windowedHistory = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
            val response = agent.handle(
                AgentRequest(userMessage = userMessage, history = windowedHistory)
            ).getOrThrow()
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        val finalWindow = history.takeLast(ContextStrategy.SLIDING_WINDOW_SIZE)
        assertFalse(
            "the sliding window at turn $turnCount should no longer contain turn 1's raw text",
            finalWindow.any { it.text == userTurn(0) }
        )
    }
}
