package com.example.webconsole

import com.example.core.platform.toKxPath

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingException
import com.example.core.llm.EmbeddingTaskType
import com.example.core.llm.GeminiEmbeddingClient
import com.example.core.llm.GeminiTextGenerator
import com.example.rag.LlmProvider
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.TextGenerator
import com.example.rag.Chunk
import com.example.rag.ChunkStats
import com.example.rag.ChunkStrategy
import com.example.rag.Chunker
import com.example.rag.CorpusLoader
import com.example.rag.Document
import com.example.rag.EmbedderPing
import com.example.rag.EvalMetrics
import com.example.rag.EvalSet
import com.example.rag.Evaluator
import com.example.rag.FixedSizeChunker
import com.example.rag.IndexInspector
import com.example.rag.IndexMeta
import com.example.rag.IndexStore
import com.example.rag.InspectReport
import com.example.rag.PingResponse
import com.example.rag.StructureChunker

@Serializable
data class ChunkRequest(
    val text: String,
    val source: String = "pasted.md",
    val title: String = "Pasted text",
    val fixedSize: Int = 800,
    val overlap: Int = 100,
    val minChars: Int = 300,
    val maxChars: Int = 1500,
)

@Serializable
data class ChunkView(val chunk: Chunk, val length: Int, val startsMidSentence: Boolean, val endsMidSentence: Boolean)

@Serializable
data class StrategyResult(val strategy: String, val params: String, val stats: ChunkStats?, val chunks: List<ChunkView>)

@Serializable
data class ChunkResponse(val fixed: StrategyResult, val structure: StrategyResult)

@Serializable
data class SearchRequest(val strategy: String, val query: String, val k: Int = 5)

/** Per call purpose (rewrite, cited, ...) token and timing statistics of the local model. */
@Serializable
data class PurposeStatsView(
    val purpose: String, val calls: Int,
    val promptTokensAvg: Double, val promptTokensP90: Int, val promptTokensMax: Int,
    val completionTokensAvg: Double, val completionTokensP90: Int, val completionTokensMax: Int,
    val generationTokPerSec: Double, val promptTokPerSec: Double, val totalMillisAvg: Double, val loadMillisTotal: Long,
)

@Serializable
data class SearchHitView(val score: Float, val chunk: Chunk)

@Serializable
data class SearchResponse(val embeddingModel: String, val hits: List<SearchHitView>)

@Serializable
data class FileList(val files: List<String>)

@Serializable
data class FileContent(val name: String, val text: String)

@Serializable
private data class ErrorBody(val error: String)

class ApiException(val status: Int, message: String) : Exception(message)

/** Request handling kept separate from the HTTP plumbing so it can be unit-tested directly. */
class UiApi(
    private val corpusDir: File,
    private val indexDir: File,
    private val apiKey: String,
    private val embedderFactory: ((IndexMeta) -> EmbeddingClient?) = { null },
    private val evalFile: String = "rag/eval/questions.json",
    private val controlFile: String = "rag/eval/control-questions.json",
    private val generatorFactory: ((String) -> TextGenerator?) = { null },
    sessionsDir: File = File("rag/sessions"),
    ollamaUrl: String = com.example.core.llm.OllamaChatClient.DEFAULT_URL,
    ollamaModel: String = com.example.core.llm.OllamaChatClient.DEFAULT_MODEL,
    ollamaEmbedModel: String = LlmProvider.DEFAULT_EMBED_MODEL,
    localIndexDir: File = File(LlmProvider.LOCAL_INDEX_DIR),
    localGeneratorFactory: ((String) -> TextGenerator?)? = null,
    localEmbedderFactory: ((IndexMeta) -> EmbeddingClient?)? = null,
    localModels: (() -> List<String>)? = null,
    localBaselineGeneratorFactory: ((String) -> TextGenerator?)? = null,
    ollamaTuning: com.example.core.llm.OllamaTuning = com.example.core.llm.OllamaTuning.DEFAULT,
    ollamaPrompts: com.example.rag.PromptProfile = com.example.rag.PromptProfile.DEFAULT,
) {
    /** Token counts and timings of every local call since the last reset (sizing data for context windows and limits). */
    private val llmStats = com.example.core.llm.LlmCallStats()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val generators = java.util.concurrent.ConcurrentHashMap<String, TextGenerator>()

    private val localProvider = LlmProvider(LlmProvider.OLLAMA, apiKey, ollamaUrl, ollamaModel, ollamaEmbedModel, ollamaTuning, llmStats, ollamaPrompts)
    private val localGenerators = java.util.concurrent.ConcurrentHashMap<String, TextGenerator>()

    private val localRag = LocalRag(
        localIndexDir,
        embedderFor = { meta -> localEmbedderFactory?.invoke(meta) ?: localProvider.embedderFor(meta.embeddingModel, meta.dimension) },
        generatorFor = { model -> localGeneratorFactory?.invoke(model) ?: localGenerators.getOrPut(model) { localProvider.generator(model) } },
        defaultModel = ollamaModel,
        models = localModels ?: { installedChatModels(com.example.core.llm.OllamaChatClient(ollamaUrl, ollamaModel)) },
        promptProfile = localProvider.promptProfile,
    )

    /** The same local stack as it was before Day 29: original prompts, no tuning (generator defaults). */
    private val baselineProvider = LlmProvider(LlmProvider.OLLAMA, apiKey, ollamaUrl, ollamaModel, ollamaEmbedModel, com.example.core.llm.OllamaTuning.DEFAULT, llmStats, com.example.rag.PromptProfile.DEFAULT)
    private val baselineGenerators = java.util.concurrent.ConcurrentHashMap<String, TextGenerator>()

    private val localBaselineRag = LocalRag(
        localIndexDir,
        embedderFor = localRag.embedderFor,
        generatorFor = { model -> localBaselineGeneratorFactory?.invoke(model) ?: baselineGenerators.getOrPut(model) { baselineProvider.generator(model) } },
        defaultModel = ollamaModel,
        models = localRag.models,
        promptProfile = baselineProvider.promptProfile,
    )

    private val chatApi = ChatApi(
        indexDir, File(controlFile), apiKey,
        embedderFor = { embedderFactory(it) ?: defaultEmbedder(it) },
        generatorFor = { model -> generatorFactory(model) ?: apiKey.takeIf { it.isNotBlank() }?.let { generators.getOrPut(model) { GeminiTextGenerator(it, model) } } },
        local = localRag,
        localBaseline = localBaselineRag,
        optimization = OptimizationInfo.describe(ollamaModel, baselineProvider.tuning, baselineProvider.promptProfile, localProvider.tuning, localProvider.promptProfile),
    )

    private val sessionApi = com.example.webconsole.SessionApi(
        com.example.rag.chat.SessionStore(sessionsDir.toKxPath()), indexDir, apiKey,
        embedderFor = { embedderFactory(it) ?: defaultEmbedder(it) },
        generatorFor = { model -> generatorFactory(model) ?: apiKey.takeIf { it.isNotBlank() }?.let { generators.getOrPut(model) { GeminiTextGenerator(it, model) } } },
        local = localRag,
    )

    fun chatConfig(): String = chatApi.config()

    fun llmStats(): String = json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(PurposeStatsView.serializer()),
        runBlocking { llmStats.summary() }.map {
            PurposeStatsView(it.purpose.key, it.calls, it.promptTokensAvg, it.promptTokensP90, it.promptTokensMax, it.completionTokensAvg, it.completionTokensP90, it.completionTokensMax,
                it.generationTokPerSec, it.promptTokPerSec, it.totalMillisAvg, it.loadMillisTotal)
        },
    )

    fun resetLlmStats(): String { runBlocking { llmStats.reset() }; return "{}" }

    /** Sessions are read-modify-write JSON files: serialise them even though other requests now run in parallel. */
    private val sessionLock = Any()

    fun sessions(method: String, path: String, body: String): String = synchronized(sessionLock) { sessionApi.handle(method, path, body) }

    fun chat(body: String): String = chatApi.chat(body)

    fun files(): String = json.encodeToString(FileList.serializer(),
        FileList(if (corpusDir.isDirectory) CorpusLoader.load(corpusDir.toKxPath()).map { it.source } else emptyList()))

    fun file(name: String): String {
        val f = File(corpusDir, name).canonicalFile
        if (!f.path.startsWith(corpusDir.canonicalPath + File.separator) || !f.isFile || f.extension != "md") {
            throw ApiException(404, "No such corpus file: $name")
        }
        return json.encodeToString(FileContent.serializer(), FileContent(name, f.readText().replace("\r\n", "\n")))
    }

    fun chunk(body: String): String {
        val req = parse(ChunkRequest.serializer(), body)
        val doc = Document(req.source, req.title, req.text.replace("\r\n", "\n"))
        val fixed = guard { FixedSizeChunker(req.fixedSize, req.overlap) }
        val structure = guard { StructureChunker(req.maxChars, req.minChars) }
        val resp = ChunkResponse(
            result(fixed, "size=${req.fixedSize}, overlap=${req.overlap}", doc),
            result(structure, "max=${req.maxChars}, min=${req.minChars}", doc),
        )
        return json.encodeToString(ChunkResponse.serializer(), resp)
    }

    fun search(body: String): String {
        val req = parse(SearchRequest.serializer(), body)
        val file = File(indexDir, "${req.strategy}.json")
        if (req.strategy !in ChunkStrategy.entries.map { it.id } || !file.isFile) {
            throw ApiException(404, "No saved index for '${req.strategy}'. Run: ./gradlew :rag:run --args=\"index\"")
        }
        if (req.query.isBlank()) throw ApiException(400, "Query is empty")
        val index = IndexStore().load(file.toKxPath())
        val embedder = embedderFactory(index.meta) ?: defaultEmbedder(index.meta)
            ?: throw ApiException(400, "Index was built with ${index.meta.embeddingModel}; set GEMINI_API_KEY to query it.")
        val vector = try {
            runBlocking { embedder.embed(listOf(req.query), EmbeddingTaskType.RETRIEVAL_QUERY) }.single()
        } catch (e: EmbeddingException) {
            throw ApiException(502, e.message ?: "Embedding failed")
        }
        val hits = index.search(vector, req.k.coerceIn(1, 20)).map { SearchHitView(it.score, it.chunk) }
        return json.encodeToString(SearchResponse.serializer(), SearchResponse(index.meta.embeddingModel, hits))
    }

    fun inspect(strategy: String): String {
        val file = File(indexDir, "$strategy.json")
        if (strategy !in ChunkStrategy.entries.map { it.id } || !file.isFile) {
            throw ApiException(404, "No saved index for '$strategy'. Run: ./gradlew :rag:run --args=\"index\"")
        }
        val report = IndexInspector.inspect(file, if (corpusDir.isDirectory) CorpusLoader.load(corpusDir.toKxPath()) else null)
        return json.encodeToString(InspectReport.serializer(), report)
    }

    fun ping(strategy: String): String {
        val file = File(indexDir, "$strategy.json")
        if (strategy !in ChunkStrategy.entries.map { it.id } || !file.isFile) throw ApiException(404, "No saved index for '$strategy'.")
        val meta = IndexStore().load(file.toKxPath()).meta
        val embedder = embedderFactory(meta) ?: defaultEmbedder(meta)
            ?: throw ApiException(400, "No GEMINI_API_KEY set: cannot ping ${meta.embeddingModel}. Set the env var or add it to local.properties and restart the ui.")
        val resp = try {
            runBlocking { EmbedderPing.run(embedder) }
        } catch (e: EmbeddingException) {
            throw ApiException(502, e.message ?: "Embedding failed")
        }
        return json.encodeToString(PingResponse.serializer(), resp)
    }

    fun eval(strategy: String): String {
        val file = File(indexDir, "$strategy.json")
        if (strategy !in ChunkStrategy.entries.map { it.id } || !file.isFile) throw ApiException(404, "No saved index for '$strategy'.")
        val qFile = File(evalFile)
        if (!qFile.isFile) throw ApiException(404, "Eval set not found: $evalFile")
        val index = IndexStore().load(file.toKxPath())
        val embedder = embedderFactory(index.meta) ?: defaultEmbedder(index.meta)
            ?: throw ApiException(400, "Index was built with ${index.meta.embeddingModel}; set GEMINI_API_KEY to run the eval.")
        val metrics = try {
            runBlocking { Evaluator.run(index, embedder, EvalSet.load(qFile)) }
        } catch (e: EmbeddingException) {
            throw ApiException(502, e.message ?: "Embedding failed")
        }
        return json.encodeToString(EvalMetrics.serializer(), metrics)
    }

    private fun defaultEmbedder(meta: IndexMeta): EmbeddingClient? = when {
        meta.embeddingModel.startsWith("offline-hashing-bow-") -> HashingEmbeddingClient(meta.dimension)
        apiKey.isNotBlank() -> GeminiEmbeddingClient(apiKey, meta.embeddingModel, meta.dimension)
        else -> null
    }

    private fun result(chunker: Chunker, params: String, doc: Document): StrategyResult {
        val chunks = chunker.chunk(doc)
        return StrategyResult(
            chunker.strategy.id, params,
            if (chunks.isEmpty()) null else ChunkStats.of(chunks),
            chunks.map {
                ChunkView(it, it.text.length, ChunkStats.startsMidSentence(it.text), ChunkStats.endsMidSentence(it.text))
            },
        )
    }

    private fun <T> parse(s: kotlinx.serialization.KSerializer<T>, body: String): T = try {
        json.decodeFromString(s, body)
    } catch (e: Exception) {
        throw ApiException(400, "Invalid request: ${e.message?.take(200)}")
    }

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ApiException(400, e.message ?: "Invalid parameters")
    }

    fun errorJson(message: String) = json.encodeToString(ErrorBody.serializer(), ErrorBody(message))
}

/** Chat models installed in Ollama (embedding models are hidden), empty when Ollama is not reachable. */
internal fun installedChatModels(client: com.example.core.llm.OllamaChatClient): List<String> =
    runBlocking { client.models() }.getOrDefault(emptyList()).filterNot { "embed" in it.lowercase() }

/** Local-only visualisation server (JDK built-in HTTP server, bound to loopback). */
class UiServer(private val api: UiApi, port: Int) {
    private val server = HttpServer.create(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 0)
    val port: Int get() = server.address.port

    init {
        server.createContext("/") { ex -> handle(ex) { route(ex) } }
        // A small pool so independent requests (e.g. the Cloud and Local sides of a comparison) run concurrently.
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "ui-http").apply { isDaemon = true } }
    }

    private fun route(ex: HttpExchange): Pair<String, String> {
        val path = ex.requestURI.path
        val get = ex.requestMethod == "GET"
        val post = ex.requestMethod == "POST"
        return when {
            get && (path == "/" || path == "/index.html") -> {
                val html = UiServer::class.java.getResourceAsStream("/ui/index.html")?.readBytes()?.decodeToString()
                    ?: throw ApiException(500, "UI resource missing")
                "text/html; charset=utf-8" to html
            }
            get && path == "/ui/markdown.js" -> "application/javascript; charset=utf-8" to
                (UiServer::class.java.getResourceAsStream("/ui/markdown.js")?.readBytes()?.decodeToString()
                    ?: throw ApiException(500, "UI resource missing"))
            get && path == "/ui/optimization-summary.json" -> JSON to
                (UiServer::class.java.getResourceAsStream("/ui/optimization-summary.json")?.readBytes()?.decodeToString()
                    ?: throw ApiException(404, "optimization-summary.json missing: run rag/eval/local-rag/make_summary.py"))
            get && path == "/api/files" -> JSON to api.files()
            get && path == "/api/file" -> JSON to api.file(queryParam(ex, "name") ?: throw ApiException(400, "name required"))
            get && path == "/api/index/inspect" -> JSON to api.inspect(queryParam(ex, "strategy") ?: throw ApiException(400, "strategy required"))
            get && path == "/api/embedder/ping" -> JSON to api.ping(queryParam(ex, "strategy") ?: throw ApiException(400, "strategy required"))
            get && path == "/api/eval" -> JSON to api.eval(queryParam(ex, "strategy") ?: throw ApiException(400, "strategy required"))
            get && path == "/api/chat/config" -> JSON to api.chatConfig()
            get && path == "/api/llm/stats" -> JSON to api.llmStats()
            post && path == "/api/llm/stats/reset" -> JSON to api.resetLlmStats()
            post && path == "/api/chat" -> JSON to api.chat(ex.requestBody.readBytes().decodeToString())
 path.startsWith("/api/sessions") && ex.requestMethod in setOf("GET", "POST", "PUT", "DELETE") ->
                JSON to api.sessions(ex.requestMethod, path, ex.requestBody.readBytes().decodeToString())
            post && path == "/api/chunk" -> JSON to api.chunk(ex.requestBody.readBytes().decodeToString())
            post && path == "/api/search" -> JSON to api.search(ex.requestBody.readBytes().decodeToString())
            else -> throw ApiException(404, "Not found")
        }
    }

    private fun queryParam(ex: HttpExchange, name: String) =
        ex.requestURI.rawQuery?.split("&")?.map { it.split("=", limit = 2) }
            ?.firstOrNull { it[0] == name }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun handle(ex: HttpExchange, block: () -> Pair<String, String>) {
        val (status, type, body) = try {
            block().let { Triple(200, it.first, it.second) }
        } catch (e: ApiException) {
            Triple(e.status, JSON, api.errorJson(e.message ?: "error"))
        } catch (e: Exception) {
            Triple(500, JSON, api.errorJson("Internal error: ${e.message}"))
        }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun start() = server.start()
    fun stop() = server.stop(0)

    private companion object {
        const val JSON = "application/json; charset=utf-8"
    }
}
