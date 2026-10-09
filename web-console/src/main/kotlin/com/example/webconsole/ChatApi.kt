package com.example.webconsole

import com.example.core.platform.toKxPath

import com.example.core.llm.EmbeddingClient
import com.example.core.llm.GeminiTextGenerator
import com.example.core.llm.TextGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import com.example.rag.CachedTextGenerator
import com.example.rag.ChatChunk
import com.example.rag.ChatMode
import com.example.rag.ChatRequest
import com.example.rag.ChatResponse
import com.example.rag.ChatSource
import com.example.rag.ChatStageHit
import com.example.rag.ChatTrace
import com.example.rag.ChatTurn
import com.example.rag.ChunkStrategy
import com.example.rag.ControlSet
import com.example.rag.IndexMeta
import com.example.rag.IndexStore
import com.example.rag.LlmUsage
import com.example.rag.RagAnswer
import com.example.rag.RagConfig
import com.example.rag.RagMode
import com.example.rag.RagPipeline
import com.example.rag.RagPromptBuilder
import com.example.rag.RetrievalTrace
import com.example.rag.Retriever
import com.example.rag.SearchHit
import com.example.rag.VectorRetriever
import com.example.rag.stagedPipeline

/** What the web UI needs to serve the `ollama` provider: its own index directory, retrieval embedder and answer generator. */
class LocalRag(
    val indexDir: File,
    val embedderFor: (IndexMeta) -> EmbeddingClient?,
    val generatorFor: (String) -> TextGenerator?,
    val defaultModel: String,
    val models: () -> List<String>,
)

@Serializable
data class ChatControlQuestion(val id: String, val category: String, val question: String)

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
    /** Local (Ollama) provider: its indexes, default model, installed models. */
    val localStrategies: List<String> = emptyList(),
    val localDefaultModel: String = "",
    val localModels: List<String> = emptyList(),
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
    private val local: LocalRag? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val provider = PipelineProvider { req, needsRetrieval ->
        val useLocal = req.provider == LOCAL || req.provider == HYBRID
        val route = providerRoute(req.provider)
        val retriever = if (needsRetrieval) {
            val file = File(route.indexDir, "${req.strategy}.json")
            if (!file.isFile) throw ApiException(404, if (useLocal) "No local index for '${req.strategy}'. Run: ./gradlew :rag:tools:run --args=\"index --provider ollama\"" else "No saved index for '${req.strategy}'. Run: ./gradlew :rag:run --args=\"index\"")
            val index = IndexStore().load(file.toKxPath())
            val embedder = route.embedderFor(index.meta)
                ?: throw ApiException(400, if (useLocal) "Index was built with ${index.meta.embeddingModel}, which the local provider cannot query." else "Index was built with ${index.meta.embeddingModel}; set GEMINI_API_KEY (env var or local.properties) and restart the ui.")
            VectorRetriever(embedder, index, req.topK)
        } else Retriever { emptyList() }
        val model = req.model ?: route.defaultModel
        val raw = route.generatorFor(model)
            ?: throw ApiException(400, "GEMINI_API_KEY is not set: cannot generate answers. Set the env var or add it to local.properties and restart the ui.")
        val usage = LlmUsage()
        val generator = CachedTextGenerator(raw, model, usage)
        val config = if (req.staged) RagConfig(req.topKBefore, req.topK, req.threshold, req.filter, req.rerank, req.rewrite) else RagConfig.PLAIN
        ChatPipeline(stagedPipeline(retriever, generator, config, req.llmRerank, req.citations), config, usage)
    }

    private class Route(val indexDir: File, val embedderFor: (IndexMeta) -> EmbeddingClient?, val generatorFor: (String) -> TextGenerator?, val defaultModel: String)

    private fun providerRoute(provider: String): Route = when (provider) {
        LOCAL -> local?.let { Route(it.indexDir, it.embedderFor, it.generatorFor, it.defaultModel) }
            ?: throw ApiException(400, "The local provider is not configured")
        HYBRID -> local?.let { Route(it.indexDir, it.embedderFor, generatorFor, GeminiTextGenerator.DEFAULT_MODEL) }
            ?: throw ApiException(400, "The local provider is not configured")
        "gemini" -> Route(indexDir, embedderFor, generatorFor, GeminiTextGenerator.DEFAULT_MODEL)
        else -> throw ApiException(400, "Unknown provider '$provider'")
    }

    fun config(): String {
        val questions = if (controlFile.isFile) {
            ControlSet.load(controlFile).map { ChatControlQuestion(it.id, it.category, it.question) }
        } else emptyList()
        val strategies = ChunkStrategy.entries.map { it.id }.filter { File(indexDir, "$it.json").isFile }
        return json.encodeToString(
            ChatConfig.serializer(),
            ChatConfig(strategies, "structure", VectorRetriever.DEFAULT_TOP_K, RagConfig.DEFAULT.topKBefore, RagConfig.DEFAULT.threshold, GeminiTextGenerator.DEFAULT_MODEL, apiKey.isNotBlank(), questions,
                localStrategies = local?.let { l -> ChunkStrategy.entries.map { it.id }.filter { File(l.indexDir, "$it.json").isFile } }.orEmpty(),
                localDefaultModel = local?.defaultModel.orEmpty(),
                localModels = local?.models?.invoke().orEmpty(),
            ),
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
        if (req.model?.let { !MODEL_RE.matches(it) } == true) throw ApiException(400, "Invalid model name")

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
        val st = a.structured
        val shown = if (st == null) a.hits else a.hits.filter { h -> !st.idk && st.sources.any { it.chunkId == h.chunk.chunkId } }
        val sources = shown.map { ChatSource(RagPromptBuilder.sourceLabel(it.chunk), it.score, it.chunk.chunkId) }
        return ChatTurn(
            mode, a.answer, sources, chunks,
            notFound = mode == ChatMode.WITH_RAG && (st?.idk ?: (a.insufficientContext || looksLikeNotFound(a.answer))),
            latencyMs = latencyMs,
            debug = buildMap {
                put("retrieved", (a.trace?.retrieved?.size ?: chunks.size).toString())
                a.trace?.let { put("filtered", it.filtered.size.toString()); put("reranked", it.reranked.size.toString()); put("searchQuery", it.searchQuery) }
            },
            trace = a.trace?.let { traceView(it, cp.config) },
            insufficient = a.insufficientContext,
            llmCalls = cp.usage.calls,
            structured = st,
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
        const val LOCAL = "ollama"

        /** Local retrieval (Ollama embeddings over the local index) + cloud answer (Gemini): separates retrieval quality from answer quality. */
        const val HYBRID = "hybrid"
        private val MODEL_RE = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}
