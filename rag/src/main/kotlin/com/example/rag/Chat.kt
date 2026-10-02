package com.example.rag

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
)

@Serializable
data class ChatResponse(val question: String, val results: List<ChatTurn>)

@Serializable
data class ChatControlQuestion(val id: String, val category: String, val question: String)

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

@Serializable
data class ChatConfig(
    val strategies: List<String>,
    val defaultStrategy: String,
    val defaultTopK: Int,
    val defaultTopKBefore: Int = RagConfig.DEFAULT.topKBefore,
    val defaultThreshold: Float = RagConfig.DEFAULT.threshold,
    val defaultModel: String,
    val keyConfigured: Boolean,
    val controlQuestions: List<ChatControlQuestion>,
)

/**
 * Extension point: turns a request into the context-preparation + generation pipeline.
 * Day 23 (rewrite/threshold/rerank) and Day 25 (memory) can return a pipeline built around a different [Retriever].
 */
fun interface PipelineProvider {
    fun pipelineFor(request: ChatRequest, needsRetrieval: Boolean): ChatPipeline
}

/** A pipeline plus the stage settings to call it with and a counter of the LLM calls it makes. */
class ChatPipeline(val pipeline: RagPipeline, val config: RagConfig, val usage: LlmUsage)

/** Chat API logic, independent of the HTTP plumbing and of [RagPipeline] internals. */
class ChatApi(
    private val indexDir: File,
    private val controlFile: File,
    private val apiKey: String,
    private val embedderFor: (IndexMeta) -> EmbeddingClient?,
    private val generatorFor: (String) -> TextGenerator?,
    private val clock: () -> Long = System::nanoTime,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val provider = PipelineProvider { req, needsRetrieval ->
        val retriever = if (needsRetrieval) {
            val file = File(indexDir, "${req.strategy}.json")
            if (!file.isFile) throw ApiException(404, "No saved index for '${req.strategy}'. Run: ./gradlew :rag:run --args=\"index\"")
            val index = IndexStore().load(file)
            val embedder = embedderFor(index.meta)
                ?: throw ApiException(400, "Index was built with ${index.meta.embeddingModel}; set GEMINI_API_KEY (env var or local.properties) and restart the ui.")
            VectorRetriever(embedder, index, req.topK)
        } else Retriever { emptyList() }
        val model = req.model ?: GeminiTextGenerator.DEFAULT_MODEL
        val raw = generatorFor(model)
            ?: throw ApiException(400, "GEMINI_API_KEY is not set: cannot generate answers. Set the env var or add it to local.properties and restart the ui.")
        val usage = LlmUsage()
        val generator = CachedTextGenerator(raw, model, usage)
        val config = if (req.staged) RagConfig(req.topKBefore, req.topK, req.threshold, req.filter, req.rerank, req.rewrite) else RagConfig.PLAIN
        ChatPipeline(stagedPipeline(retriever, generator, config, req.llmRerank), config, usage)
    }

    fun config(): String {
        val questions = if (controlFile.isFile) {
            ControlSet.load(controlFile).map { ChatControlQuestion(it.id, it.category, it.question) }
        } else emptyList()
        val strategies = ChunkStrategy.entries.map { it.id }.filter { File(indexDir, "$it.json").isFile }
        return json.encodeToString(
            ChatConfig.serializer(),
            ChatConfig(strategies, "structure", VectorRetriever.DEFAULT_TOP_K, RagConfig.DEFAULT.topKBefore, RagConfig.DEFAULT.threshold, GeminiTextGenerator.DEFAULT_MODEL, apiKey.isNotBlank(), questions),
        )
    }

    fun chat(body: String): String {
        val req = try {
            json.decodeFromString(ChatRequest.serializer(), body)
        } catch (e: Exception) {
            throw ApiException(400, "Invalid request: ${e.message?.take(200)}")
        }
        val question = req.question.trim()
        if (question.isEmpty()) throw ApiException(400, "Question is empty")
        if (question.length > MAX_QUESTION) throw ApiException(400, "Question is too long (max $MAX_QUESTION characters)")
        if (req.topK !in 1..20) throw ApiException(400, "topK must be between 1 and 20")
        if (req.topKBefore !in 1..50) throw ApiException(400, "topK before must be between 1 and 50")
        if (req.staged && req.topK > req.topKBefore) throw ApiException(400, "topK after (${req.topK}) must not exceed topK before (${req.topKBefore})")
        if (req.threshold.isNaN() || req.threshold !in 0f..1f) throw ApiException(400, "threshold must be between 0 and 1")
        if (req.strategy !in ChunkStrategy.entries.map { it.id }) throw ApiException(400, "Unknown strategy '${req.strategy}'")
        if (req.model != null && !MODEL_RE.matches(req.model)) throw ApiException(400, "Invalid model name")

        val modes = if (req.mode == ChatMode.COMPARE) listOf(ChatMode.WITHOUT_RAG, ChatMode.WITH_RAG) else listOf(req.mode)
        val results = modes.map { mode ->
            val cp = provider.pipelineFor(req, mode == ChatMode.WITH_RAG)
            val start = clock()
            val outcome = runBlocking { cp.pipeline.ask(question, if (mode == ChatMode.WITH_RAG) RagMode.RAG else RagMode.NO_RAG, cp.config) }
            toTurn(mode, outcome, (clock() - start) / 1_000_000, cp)
        }
        return json.encodeToString(ChatResponse.serializer(), ChatResponse(question, results))
    }

    private fun toTurn(mode: ChatMode, outcome: Result<RagAnswer>, latencyMs: Long, cp: ChatPipeline): ChatTurn {
        val a = outcome.getOrElse {
            return ChatTurn(mode, error = it.message ?: it::class.simpleName ?: "Unknown error", latencyMs = latencyMs)
        }
        val chunks = a.hits.map { ChatChunk(it.chunk.chunkId, it.chunk.source, it.chunk.section, it.chunk.text, it.score) }
        val sources = a.hits.map { ChatSource(RagPromptBuilder.sourceLabel(it.chunk), it.score, it.chunk.chunkId) }
        return ChatTurn(
            mode, a.answer, sources, chunks,
            notFound = mode == ChatMode.WITH_RAG && (a.insufficientContext || looksLikeNotFound(a.answer)),
            latencyMs = latencyMs,
            debug = buildMap {
                put("retrieved", (a.trace?.retrieved?.size ?: chunks.size).toString())
                a.trace?.let { put("filtered", it.filtered.size.toString()); put("reranked", it.reranked.size.toString()); put("searchQuery", it.searchQuery) }
            },
            trace = a.trace?.let { traceView(it, cp.config) },
            insufficient = a.insufficientContext,
            llmCalls = cp.usage.calls,
        )
    }

    private fun traceView(t: RetrievalTrace, config: RagConfig): ChatTrace {
        fun view(h: SearchHit, rerank: Double? = null) = ChatStageHit(h.chunk.chunkId, RagPromptBuilder.sourceLabel(h.chunk), h.score, rerank)
        return ChatTrace(
            t.originalQuery, t.searchQuery, t.rewriteFallback,
            t.retrieved.map { view(it) }, t.filtered.map { view(it) }, t.reranked.map { view(it.hit, it.rerankScore) },
            threshold = if (config.filter) config.threshold else null, topKBefore = config.topKBefore, topKAfter = config.topKAfter,
        )
    }

    companion object {
        private val NOT_FOUND_PHRASES = listOf(
            RagPromptBuilder.NOT_FOUND_HINT, "does not contain", "doesn't contain", "does not include",
            "not enough information", "no information", "not covered", "does not mention",
        )

        /** Heuristic substring match on the model's refusal wording (the prompt only asks it to say the knowledge base lacks the answer). */
        fun looksLikeNotFound(answer: String): Boolean = answer.lowercase().let { a -> NOT_FOUND_PHRASES.any { it in a } }

        private const val MAX_QUESTION = 2000
        private val MODEL_RE = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
