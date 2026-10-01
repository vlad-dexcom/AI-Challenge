package com.example.rag

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage (run from the repo root via Gradle):
  ./gradlew :rag:run --args="index   [--corpus rag/corpus] [--out rag/index] [--embedder gemini|offline]"
  ./gradlew :rag:run --args="compare [--out rag/index] [--embedder gemini|offline]"
  ./gradlew :rag:run --args="eval    [--embedder gemini|offline] [--k 5] [--strategy fixed|structure] [--questions rag/eval/questions.json] [--out dir]"
  ./gradlew :rag:run --args="ui      [--port 8080] [--corpus rag/corpus] [--out rag/index]"

index    builds one JSON index per chunking strategy (fixed.json, structure.json) in --out.
compare  loads both indexes, prints chunk stats + sample-query results, writes comparison-report.md.
eval     runs the retrieval eval set against each saved index (hit@1/3/5, MRR, top-1 score stats) and writes rag/eval/report-<embedder>.md.
Default --out is rag/index for gemini and rag/index-offline for offline (so both sets of indexes can live side by side).
ui       serves the chunk visualiser at http://localhost:<port> (Ctrl+C to stop).
--embedder gemini  (default if GEMINI_API_KEY is set) calls the Gemini embeddings REST API.
--embedder offline deterministic hashing embedder, no network (lexical only, for tests/demos).
The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] !in setOf("index", "compare", "eval", "ui")) {
        println(USAGE.trim()); exitProcess(if (args.isEmpty()) 0 else 1)
    }
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val key = apiKey()
    if (args[0] == "ui") {
        val server = UiServer(UiApi(File(opts["corpus"] ?: "rag/corpus"), File(opts["out"] ?: "rag/index"), key), (opts["port"] ?: "8080").toInt())
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
    val outDir = File(opts["out"] ?: if (embedderName == "gemini") "rag/index" else "rag/index-offline")
    println("Embedder: ${embedder.modelName} (${embedder.dimension} dims)")

    runBlocking {
        when (args[0]) {
            "index" -> runIndex(File(opts["corpus"] ?: "rag/corpus"), outDir, embedder)
            "compare" -> runCompare(outDir, embedder)
            "eval" -> runEval(opts, outDir, embedder, embedderName)
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

private suspend fun runEval(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, embedderName: String) {
    val corpusDir = File(opts["corpus"] ?: "rag/corpus")
    val questions = EvalSet.load(File(opts["questions"] ?: "rag/eval/questions.json"))
    val problems = EvalSet.validate(questions, CorpusLoader.load(corpusDir))
    if (problems.isNotEmpty()) { System.err.println("Invalid eval set:\n" + problems.joinToString("\n")); exitProcess(1) }
    val strategies = opts["strategy"]?.let { listOf(it) } ?: ChunkStrategy.entries.map { it.id }
    val store = IndexStore()
    val metrics = strategies.map { st ->
        val file = File(dir, "$st.json")
        require(file.isFile) { "Missing index ${file.path}; run the index command first." }
        val index = store.load(file)
        require(index.meta.embeddingModel == embedder.modelName) {
            "Index $st was built with ${index.meta.embeddingModel}, but embedder is ${embedder.modelName}."
        }
        Evaluator.run(index, embedder, questions, (opts["k"] ?: "5").toInt())
    }
    val md = Evaluator.renderMarkdown(embedderName, metrics)
    val report = File("rag/eval/report-$embedderName.md")
    report.writeText(md)
    println(md)
    println("Report written to ${report.path}")
}
