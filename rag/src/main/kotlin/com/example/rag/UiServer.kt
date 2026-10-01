package com.example.rag

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress

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
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun files(): String = json.encodeToString(FileList.serializer(),
        FileList(if (corpusDir.isDirectory) CorpusLoader.load(corpusDir).map { it.source } else emptyList()))

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
        val index = IndexStore().load(file)
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

/** Local-only visualisation server (JDK built-in HTTP server, bound to loopback). */
class UiServer(private val api: UiApi, port: Int) {
    private val server = HttpServer.create(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 0)
    val port: Int get() = server.address.port

    init {
        server.createContext("/") { ex -> handle(ex) { route(ex) } }
        server.executor = null
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
            get && path == "/api/files" -> JSON to api.files()
            get && path == "/api/file" -> JSON to api.file(queryParam(ex, "name") ?: throw ApiException(400, "name required"))
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
