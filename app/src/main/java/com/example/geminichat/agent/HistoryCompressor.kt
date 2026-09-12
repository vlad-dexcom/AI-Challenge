package com.example.geminichat.agent

/**
 * Outcome of folding a chunk of aged-out messages into the running summary (see
 * [HistoryCompressor.fold]). [summary] replaces whatever summary was previously stored;
 * [tokensUsed] is the (estimated) cost of the summarization call itself, tracked separately
 * from the cost of the actual chat turn so the two aren't conflated in the UI.
 */
data class CompressionOutcome(
    val summary: String,
    val tokensUsed: Int
)

/**
 * Implements Day 9's context-compression policy: keep the most recent [keepLastN] messages
 * verbatim, and once at least [chunkSize] older messages have piled up beyond that window,
 * fold them into a single running summary via a real LLM call (so quality/token trade-offs
 * can be compared against sending the full history — see
 * [com.example.geminichat.ChatViewModel]).
 *
 * Split into a pure decision step ([pendingFoldRange], [recentTail]) and a suspend step
 * ([fold]) so the "when do we need to summarize" policy can be unit-tested without any
 * network/LLM dependency.
 */
class HistoryCompressor(
    private val client: LlmClient,
    private val keepLastN: Int = DEFAULT_KEEP_LAST_N,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {
    companion object {
        const val DEFAULT_KEEP_LAST_N = 6
        const val DEFAULT_CHUNK_SIZE = 10

        private const val SUMMARIZER_SYSTEM_INSTRUCTION =
            "You are a conversation summarizer. You will be given an optional previous " +
                "summary and a new chunk of conversation turns. Produce a single updated, " +
                "concise summary (a short paragraph) that preserves the facts, decisions, " +
                "user preferences, and any unresolved questions needed to continue the " +
                "conversation naturally. Merge the previous summary with the new turns " +
                "instead of just appending to it. Respond with only the summary text, no " +
                "preamble or commentary."
    }

    /**
     * The tail of [history] that is always sent verbatim, regardless of compression state.
     */
    fun recentTail(history: List<AgentMessage>): List<AgentMessage> = history.takeLast(keepLastN)

    /**
     * Returns the index range (into [history]) of messages that need to be folded into the
     * summary right now, or `null` if there aren't enough newly aged-out messages yet.
     *
     * "Aged out" means older than the last [keepLastN] messages. We don't re-summarize on
     * every single turn (that would be an LLM call per message); instead we wait until at
     * least [chunkSize] aged-out messages have accumulated since [summarizedCount] and fold
     * all of them in one shot.
     */
    fun pendingFoldRange(historySize: Int, summarizedCount: Int): IntRange? {
        val olderCount = (historySize - keepLastN).coerceAtLeast(0)
        val pending = olderCount - summarizedCount
        if (pending < chunkSize) return null
        return summarizedCount until olderCount
    }

    /**
     * Calls the LLM to merge [previousSummary] (if any) with [messagesToFold] into an updated
     * summary. [model] is the caller's currently selected chat model — summarization reuses it
     * rather than hard-coding a separate one.
     */
    suspend fun fold(
        previousSummary: String?,
        messagesToFold: List<AgentMessage>,
        model: String
    ): Result<CompressionOutcome> {
        val transcript = messagesToFold.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else "Agent"
            "$speaker: ${message.text}"
        }
        val previousSummaryBlock = previousSummary?.takeIf { it.isNotBlank() }
            ?: "(none)"
        val input = "Previous summary:\n$previousSummaryBlock\n\n" +
            "New conversation turns to fold in:\n$transcript\n\n" +
            "Write the updated summary now."

        val spec = LlmRequestSpec(
            model = model,
            input = input,
            systemInstruction = SUMMARIZER_SYSTEM_INSTRUCTION
        )

        val requestTokens = TokenEstimator.estimate(input) +
            TokenEstimator.estimate(SUMMARIZER_SYSTEM_INSTRUCTION)

        return client.complete(spec).map { text ->
            val summary = text.trim()
            CompressionOutcome(
                summary = summary,
                tokensUsed = requestTokens + TokenEstimator.estimate(summary)
            )
        }
    }
}
