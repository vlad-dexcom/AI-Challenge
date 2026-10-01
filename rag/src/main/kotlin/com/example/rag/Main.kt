package com.example.rag

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage (run from the repo root via Gradle):
  ./gradlew :rag:run --args="index   [--corpus rag/corpus] [--out rag/index] [--embedder gemini|offline]"
  ./gradlew :rag:run --args="compare [--out rag/index] [--embedder gemini|offline]"
  ./gradlew :rag:run --args="ui      [--port 8080] [--corpus rag/corpus] [--out rag/index]"

index    builds one JSON index per chunking strategy (fixed.json, structure.json) in --out.
compare  loads both indexes, prints chunk stats + sample-query results, writes comparison-report.md.
ui       serves the chunk visualiser at http://localhost:<port> (Ctrl+C to stop).
--embedder gemini  (default if GEMINI_API_KEY is set) calls the Gemini embeddings REST API.
--embedder offline deterministic hashing embedder, no network (lexical only, for tests/demos).
The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] !in setOf("index", "compare", "ui")) {
        println(USAGE.trim()); exitProcess(if (args.isEmpty()) 0 else 1)
    }
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val outDir = File(opts["out"] ?: "rag/index")
    val key = apiKey()
    if (args[0] == "ui") {
        val server = UiServer(UiApi(File(opts["corpus"] ?: "rag/corpus"), outDir, key), (opts["port"] ?: "8080").toInt())
        server.start()
        println("Chunk visualiser: http://localhost:${server.port}  (Ctrl+C to stop)")
        Thread.currentThread().join()
    }
    val embedderName = opts["embedder"] ?: if (key.isNotBlank()) "gemini" else "offline"
    val embedder: EmbeddingClient = when (embedderName) {
        "gemini" -> {
            if (key.isBlank()) { System.err.println("GEMINI_API_KEY is not set (env var or local.properties)."); exitProcess(2) }
            GeminiEmbeddingClient(key)
        }
        "offline" -> HashingEmbeddingClient()
        else -> { System.err.println("Unknown embedder '$embedderName'"); exitProcess(1) }
    }
    println("Embedder: ${embedder.modelName} (${embedder.dimension} dims)")

    runBlocking {
        when (args[0]) {
            "index" -> runIndex(File(opts["corpus"] ?: "rag/corpus"), outDir, embedder)
            "compare" -> runCompare(outDir, embedder)
        }
    }
    exitProcess(0)
}

private fun apiKey(): String {
    System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    val props = File("local.properties")
    if (props.exists()) {
        props.readLines().firstOrNull { it.trim().startsWith("GEMINI_API_KEY=") }
            ?.substringAfter("=")?.trim()?.let { return it }
    }
    return ""
}

private val CHUNKERS: List<Pair<Chunker, String>> = listOf(
    FixedSizeChunker(800, 100) to "size=800, overlap=100",
    StructureChunker(1500, 300) to "max=1500, min=300",
)

private suspend fun runIndex(corpus: File, out: File, embedder: EmbeddingClient) {
    val docs = CorpusLoader.load(corpus)
    val words = docs.sumOf { it.text.split(Regex("\\s+")).size }
    println("Corpus: ${docs.size} documents, $words words (~${words / 500} pages)")
    val store = IndexStore()
    for ((chunker, params) in CHUNKERS) {
        val index = Indexer(embedder).build(docs, chunker, params, corpus.path)
        val file = File(out, "${chunker.strategy.id}.json")
        store.save(index, file)
        println("  ${chunker.strategy.id}: ${index.meta.chunkCount} chunks -> ${file.path} (${file.length() / 1024} KB)")
    }
}

private suspend fun runCompare(dir: File, embedder: EmbeddingClient) {
    val store = IndexStore()
    val fixed = store.load(File(dir, "fixed.json"))
    val structure = store.load(File(dir, "structure.json"))
    for (idx in listOf(fixed, structure)) {
        require(idx.meta.embeddingModel == embedder.modelName) {
            "Index ${idx.meta.strategy.id} was built with ${idx.meta.embeddingModel}, but embedder is ${embedder.modelName}; use matching --embedder."
        }
    }
    val queries = Comparison.DEFAULT_QUERIES
    val md = Comparison.renderMarkdown(
        fixed, structure,
        Comparison.runQueries(fixed, embedder, queries),
        Comparison.runQueries(structure, embedder, queries),
    )
    val report = File(dir, "comparison-report.md")
    report.writeText(md)
    println(md)
    println("Report written to ${report.path}")
}
