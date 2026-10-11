package com.example.aigateway

import com.example.core.llm.OllamaTuning
import com.example.core.platform.toKxPath
import com.example.rag.CachedTextGenerator
import com.example.rag.IndexStore
import com.example.rag.LlmProvider
import com.example.rag.LlmUsage
import com.example.rag.PromptProfile
import com.example.rag.VectorIndex
import com.example.rag.VectorRetriever
import com.example.rag.chat.ChatEngine
import com.example.rag.chat.ChatOptions
import com.example.rag.chat.ChatSession
import com.example.rag.chat.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RagSettings(
    val enabled: Boolean = false,
    /** Local (Ollama-embedded) index directory; relative paths resolve against the working directory. */
    val indexDir: String = "rag/index-local",
    val sessionsDir: String = "rag/sessions-gateway",
    val strategy: String = "structure",
    val maxQuestionChars: Int = 2000,
)

@Serializable
data class RagSource(val label: String, val score: Float)

@Serializable
data class RagQuote(val chunkId: String, val text: String, val verified: Boolean)

@Serializable
data class RagReply(
    val session: String,
    val turn: Int,
    val answer: String,
    val idk: Boolean,
    val sources: List<RagSource>,
    val quotes: List<RagQuote>,
    val goal: String?,
    val searchQuery: String?,
    val llmCalls: Int,
    val wallMs: Long,
)

/** Retrieval-augmented chat with per-device sessions; the gateway only does auth, limits and JSON around it. */
interface RagService {
    suspend fun ask(device: String, sessionId: String?, text: String): Result<RagReply>
    fun reset(device: String, sessionId: String): Boolean
    fun status(): String?
}

/** The Day 25-29 stack on the local model: filter + rerank + rewrite, verified citations, "I don't know", task memory, rolling summary. */
class LocalRagService(private val settings: RagSettings, private val upstreamUrl: String, private val model: String) : RagService {
    private val provider = LlmProvider(
        LlmProvider.OLLAMA, "", upstreamUrl, model, LlmProvider.DEFAULT_EMBED_MODEL,
        OllamaTuning.RECOMMENDED, localPrompts = PromptProfile.parse("local"),
    )
    private val options = ChatOptions(strategy = settings.strategy, provider = LlmProvider.OLLAMA)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val stores = ConcurrentHashMap<String, SessionStore>()

    @Volatile private var cached: Pair<Long, VectorIndex>? = null

    private fun indexFile() = File(settings.indexDir, "${settings.strategy}.json")

    override fun status(): String? = if (indexFile().isFile) null else "No local index at ${indexFile().path}. Run: ./gradlew :rag:tools:run --args=\"index --provider ollama\""

    private fun index(): VectorIndex {
        val f = indexFile()
        val stamp = f.lastModified()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return IndexStore().load(f.toKxPath()).also { cached = stamp to it }
    }

    private fun store(device: String) = stores.getOrPut(device) {
        SessionStore(File(settings.sessionsDir, device.replace(Regex("[^A-Za-z0-9_-]"), "_")).toKxPath())
    }

    override fun reset(device: String, sessionId: String): Boolean = SessionStore.isValidId(sessionId) && store(device).delete(sessionId)

    override suspend fun ask(device: String, sessionId: String?, text: String): Result<RagReply> {
        status()?.let { return Result.failure(IllegalStateException(it)) }
        val st = store(device)
        val session: ChatSession = sessionId?.takeIf(SessionStore::isValidId)?.let(st::load) ?: st.create()
        return locks.getOrPut(device + "/" + session.id) { Mutex() }.withLock {
            runCatching {
                val idx = index()
                val embedder = provider.embedderFor(idx.meta.embeddingModel, idx.meta.dimension)
                    ?: error("The index was built with ${idx.meta.embeddingModel}, which the local provider cannot query.")
                val usage = LlmUsage()
                val engine = ChatEngine(VectorRetriever(embedder, idx, options.topKBefore), CachedTextGenerator(provider.generator(model), model, usage), usage, provider.promptProfile)
                val updated = engine.send(session, text, options).getOrThrow()
                st.save(updated)
                val msg = updated.messages.last { it.role == "assistant" }
                val info = msg.info
                val s = info?.structured
                RagReply(
                    session = updated.id, turn = msg.turn, answer = msg.text, idk = s?.idk ?: false,
                    sources = info?.sources.orEmpty().map { RagSource(it.label, it.score) },
                    quotes = s?.quotes.orEmpty().map { RagQuote(it.chunkId, it.text, it.ok) },
                    goal = updated.memory.goal, searchQuery = info?.trace?.searchQuery,
                    llmCalls = info?.llmCalls ?: 0, wallMs = info?.wallMs ?: 0,
                )
            }
        }
    }
}
