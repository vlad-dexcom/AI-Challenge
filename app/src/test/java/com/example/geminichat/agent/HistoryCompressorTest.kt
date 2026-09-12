package com.example.geminichat.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the spec it was asked to complete, and always returns a fixed canned summary. */
private class FakeSummarizerClient(
    private val result: Result<String> = Result.success("Condensed summary of the chat so far.")
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

class HistoryCompressorTest {

    private fun turn(index: Int) = listOf(
        AgentMessage(role = AgentMessage.Role.USER, text = "User message $index"),
        AgentMessage(role = AgentMessage.Role.AGENT, text = "Agent reply $index")
    )

    // --- pendingFoldRange: pure decision logic, no LLM involved ---

    @Test
    fun `no fold needed while history is within the recent window`() {
        val compressor = HistoryCompressor(client = FakeSummarizerClient(), keepLastN = 6, chunkSize = 10)

        assertNull(compressor.pendingFoldRange(historySize = 6, summarizedCount = 0))
        assertNull(compressor.pendingFoldRange(historySize = 0, summarizedCount = 0))
    }

    @Test
    fun `no fold needed until a full chunk of aged-out messages accumulates`() {
        val compressor = HistoryCompressor(client = FakeSummarizerClient(), keepLastN = 6, chunkSize = 10)

        // 15 messages total, 6 kept as the recent tail -> 9 aged-out, short of the chunk of 10.
        assertNull(compressor.pendingFoldRange(historySize = 15, summarizedCount = 0))
    }

    @Test
    fun `fold range covers exactly the newly aged-out chunk once threshold is hit`() {
        val compressor = HistoryCompressor(client = FakeSummarizerClient(), keepLastN = 6, chunkSize = 10)

        // 16 messages, 6 kept -> 10 aged-out, exactly the chunk size.
        val range = compressor.pendingFoldRange(historySize = 16, summarizedCount = 0)
        assertEquals(0 until 10, range)
    }

    @Test
    fun `subsequent fold only covers the newly aged-out portion`() {
        val compressor = HistoryCompressor(client = FakeSummarizerClient(), keepLastN = 6, chunkSize = 10)

        // Already folded the first 10; now at 26 messages, 20 are aged-out -> 10 new ones pending.
        val range = compressor.pendingFoldRange(historySize = 26, summarizedCount = 10)
        assertEquals(10 until 20, range)
    }

    @Test
    fun `recentTail returns only the last keepLastN messages`() {
        val compressor = HistoryCompressor(client = FakeSummarizerClient(), keepLastN = 4, chunkSize = 10)
        val history = (0 until 3).flatMap { turn(it) } // 6 messages

        val tail = compressor.recentTail(history)

        assertEquals(4, tail.size)
        assertEquals(history.takeLast(4), tail)
    }

    // --- fold: the suspend LLM call ---

    @Test
    fun `fold sends both previous summary and new turns to the client`() = runTest {
        val client = FakeSummarizerClient(Result.success("  Updated summary.  "))
        val compressor = HistoryCompressor(client = client)

        val outcome = compressor.fold(
            previousSummary = "User is training for a 10k.",
            messagesToFold = turn(0),
            model = "test-model"
        )

        assertTrue(outcome.isSuccess)
        assertEquals("Updated summary.", outcome.getOrThrow().summary)
        assertTrue(outcome.getOrThrow().tokensUsed > 0)
        assertEquals(1, client.callCount)
        val input = client.lastSpec?.input.orEmpty()
        assertTrue(input.contains("User is training for a 10k."))
        assertTrue(input.contains("User message 0"))
        assertTrue(input.contains("Agent reply 0"))
        assertEquals("test-model", client.lastSpec?.model)
    }

    @Test
    fun `fold with no previous summary marks it as none in the prompt`() = runTest {
        val client = FakeSummarizerClient()
        val compressor = HistoryCompressor(client = client)

        compressor.fold(previousSummary = null, messagesToFold = turn(0), model = "test-model")

        assertTrue(client.lastSpec?.input.orEmpty().contains("(none)"))
    }

    @Test
    fun `fold surfaces the client's failure`() = runTest {
        val failure = Exception("Summarizer unavailable.")
        val compressor = HistoryCompressor(client = FakeSummarizerClient(Result.failure(failure)))

        val outcome = compressor.fold(previousSummary = null, messagesToFold = turn(0), model = "test-model")

        assertTrue(outcome.isFailure)
        assertEquals("Summarizer unavailable.", outcome.exceptionOrNull()?.message)
    }
}
