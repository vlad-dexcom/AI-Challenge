package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
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
}
