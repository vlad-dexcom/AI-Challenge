package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake client whose replies are fixed strings, regardless of whether it's being asked to
 * continue the chat ([LlmAgent]) or to summarize a chunk of history ([HistoryCompressor]) —
 * good enough to make total token cost comparable and deterministic across runs.
 */
private class ComparisonFakeClient : LlmClient {
    override suspend fun complete(spec: LlmRequestSpec): Result<String> =
        Result.success("Noted, continuing the plan with more detail as requested.")
}

/**
 * Day 9's headline deliverable: a reproducible, automated "tokens before/after" comparison —
 * the same long simulated dialog run twice, once replaying the full raw history every turn
 * (pre-Day-9 behavior) and once with [HistoryCompressor] folding aged-out turns into a
 * summary. Compression should cost meaningfully fewer cumulative prompt tokens once the
 * conversation runs long enough for folding to kick in.
 *
 * (Comparing *response quality* between the two modes needs a real model call and a human/LLM
 * judge — this test only proves the token-savings half of the story; see
 * docs/day9-context-compression.md for the quality-comparison methodology.)
 */
class ContextCompressionComparisonTest {

    private val config = AgentConfig(
        id = "comparison-agent",
        displayName = "Comparison Agent",
        description = "An agent used only to compare compressed vs. uncompressed history.",
        systemInstruction = "You are a concise assistant.",
        model = "test-model"
    )

    private fun userTurn(index: Int) =
        "Turn $index: please expand on the training plan in detail, considering everything so far."

    @Test
    fun `compression uses meaningfully fewer cumulative prompt tokens than full history`() = runTest {
        val turnCount = 30

        val fullHistoryTotal = runWithoutCompression(turnCount)
        val compressedTotal = runWithCompression(turnCount, keepLastN = 6, chunkSize = 10)

        assertTrue(
            "expected compression ($compressedTotal) to cost fewer cumulative prompt tokens " +
                "than full history ($fullHistoryTotal)",
            compressedTotal < fullHistoryTotal
        )
        // Sanity check the saving is substantial, not just noise, once folding has kicked in
        // multiple times over a 30-turn conversation.
        assertTrue(
            "expected compression to save at least 30% of prompt tokens, got " +
                "$compressedTotal vs $fullHistoryTotal",
            compressedTotal < fullHistoryTotal * 0.7
        )
    }

    /** Mirrors [com.example.geminichat.ChatViewModel]'s pre-Day-9 behavior: full raw history every turn. */
    private suspend fun runWithoutCompression(turnCount: Int): Int {
        val client = ComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        var history = emptyList<AgentMessage>()
        var totalPromptTokens = 0

        repeat(turnCount) { i ->
            val userMessage = userTurn(i)
            val response = agent.handle(AgentRequest(userMessage = userMessage, history = history)).getOrThrow()
            totalPromptTokens += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return totalPromptTokens
    }

    /** Mirrors [com.example.geminichat.ChatViewModel.prepareRequestContext] with compression enabled. */
    private suspend fun runWithCompression(turnCount: Int, keepLastN: Int, chunkSize: Int): Int {
        val client = ComparisonFakeClient()
        val agent = LlmAgent(config = config, client = client)
        val compressor = HistoryCompressor(client = client, keepLastN = keepLastN, chunkSize = chunkSize)

        var history = emptyList<AgentMessage>()
        var summary: String? = null
        var summarizedCount = 0
        var totalPromptTokens = 0

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
            }

            val userMessage = userTurn(i)
            val response = agent.handle(
                AgentRequest(
                    userMessage = userMessage,
                    history = compressor.recentTail(history),
                    summary = summary
                )
            ).getOrThrow()
            totalPromptTokens += response.tokenUsage.promptTokens
            history = history + AgentMessage(AgentMessage.Role.USER, userMessage) +
                AgentMessage(AgentMessage.Role.AGENT, response.text)
        }
        return totalPromptTokens
    }
}
