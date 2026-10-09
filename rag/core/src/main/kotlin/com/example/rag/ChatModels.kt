package com.example.rag

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Chat modes of the web UI. [COMPARE] answers the same question in both single modes. */
@Serializable
enum class ChatMode {
    @SerialName("rag") WITH_RAG,
    @SerialName("no_rag") WITHOUT_RAG,
    @SerialName("compare") COMPARE,
}

/**
 * Single-shot request: only the current question is sent, the backend keeps no history (Day 25 will add memory).
 * New optional fields (history, task state, filters, ...) can be added here without breaking the front end.
 */
@Serializable
data class ChatRequest(
    val question: String,
    val mode: ChatMode = ChatMode.WITH_RAG,
    val strategy: String = "structure",
    /** Chunks that go into the prompt (topK-after). */
    val topK: Int = VectorRetriever.DEFAULT_TOP_K,
    val model: String? = null,
    /** Day 23 stages; with all three off the request behaves as on Day 22 (top-[topK] by cosine). */
    val filter: Boolean = false,
    val rerank: Boolean = false,
    val rewrite: Boolean = false,
    /** Use the LLM reranker instead of the heuristic one when [rerank] is on. */
    val llmRerank: Boolean = false,
    val threshold: Float = RagConfig.DEFAULT.threshold,
    val topKBefore: Int = RagConfig.DEFAULT.topKBefore,
    /** Day 24: JSON contract with verified sources/quotes and "I don't know" in the RAG answer. */
    val citations: Boolean = true,
    /** `gemini` (cloud, default) or `ollama` (local generation and retrieval over the local index). */
    val provider: String = "gemini",
) {
    val staged: Boolean get() = filter || rerank || rewrite
}

@Serializable
data class ChatSource(val label: String, val score: Float, val chunkId: String)

@Serializable
data class ChatChunk(val chunkId: String, val source: String, val section: String, val text: String, val score: Float)

/**
 * One answer in one mode. The extension point for later days: [sources]/[chunks] (Day 24 citations),
 * [debug] (Day 23 thresholds/rewritten query, Day 25 memory used, ...).
 */
@Serializable
data class ChatTurn(
    val mode: ChatMode,
    val answer: String? = null,
    val sources: List<ChatSource> = emptyList(),
    val chunks: List<ChatChunk> = emptyList(),
    /** RAG mode only: the model said the knowledge base does not cover the question. */
    val notFound: Boolean = false,
    val latencyMs: Long = 0,
    val error: String? = null,
    val debug: Map<String, String> = emptyMap(),
    /** Per-stage view (RAG mode only). */
    val trace: ChatTrace? = null,
    /** The filter rejected every candidate, so the model was not called. */
    val insufficient: Boolean = false,
    val llmCalls: Int = 0,
    /** Day 24 (RAG mode with citations): verified sources/quotes, or the "I don't know" state. */
    val structured: StructuredAnswer? = null,
)

@Serializable
data class ChatResponse(val question: String, val results: List<ChatTurn>)

/** One chunk at one stage: [cosine] always, [rerankScore] only at the reranked stage (a different scale, not comparable). */
@Serializable
data class ChatStageHit(val chunkId: String, val label: String, val cosine: Float, val rerankScore: Double? = null)

/** What each stage of the Day 23 pipeline did: the debug view of the UI. */
@Serializable
data class ChatTrace(
    val originalQuery: String,
    val searchQuery: String,
    val rewriteFallback: String? = null,
    val retrieved: List<ChatStageHit> = emptyList(),
    val filtered: List<ChatStageHit> = emptyList(),
    val reranked: List<ChatStageHit> = emptyList(),
    val threshold: Float? = null,
    val topKBefore: Int = 0,
    val topKAfter: Int = 0,
)
