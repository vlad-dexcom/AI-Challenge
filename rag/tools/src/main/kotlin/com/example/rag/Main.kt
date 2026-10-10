package com.example.rag

import com.example.core.platform.toKxPath

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.GeminiEmbeddingClient
import com.example.core.llm.EmbedPrompts
import com.example.core.llm.EmbeddingException
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.OllamaChatClient
import com.example.core.llm.OllamaEmbeddingClient

private const val USAGE = """
Usage (run from the repo root via Gradle):
  ./gradlew :rag:tools:run --args="index   [--corpus rag/corpus] [--out rag/index] [--embedder gemini|offline|ollama:<model>]"
  ./gradlew :rag:tools:run --args="compare [--out rag/index] [--embedder gemini|offline]"
  ./gradlew :rag:tools:run --args="eval    [--embedder gemini|offline] [--k 5] [--strategy fixed|structure] [--questions rag/eval/questions.json] [--out dir]"
  ./gradlew :rag:tools:run --args="ask     \"question\" [--mode both|rag|no-rag] [--k 4] [--strategy structure] [--model gemini-3.5-flash]"
  ./gradlew :rag:tools:run --args="rag-eval [--questions rag/eval/control-questions.json] [--k 4] [--strategy structure] [--out rag/eval]"
  ./gradlew :rag:tools:run --args="sweep   [--strategy structure] [--questions rag/eval/questions.json] [--report rag/eval]"
  ./gradlew :rag:tools:run --args="modes-eval [--threshold 0.6] [--before 15] [--after 4] [--model gemini-3.5-flash] [--no-cache true] [--questions ...] [--report rag/eval]"

index    builds one JSON index per chunking strategy (fixed.json, structure.json) in --out.
compare  loads both indexes, prints chunk stats + sample-query results, writes comparison-report.md.
eval     runs the retrieval eval set against each saved index (hit@1/3/5, MRR, top-1 score stats) and writes rag/eval/report-<embedder>.md.
Default --out is rag/index for gemini and rag/index-offline for offline (so both sets of indexes can live side by side).
--provider gemini|ollama (or LLM_PROVIDER) picks generation + default embedder: ollama = fully local (answers via --ollama-model, retrieval via --ollama-embed-model, index rag/index-local).
ask      Day 22: answers one question WITHOUT RAG, WITH RAG (top-k chunks from the index in the prompt), or both.
rag-eval Day 22: runs the 10 control questions in both modes, writes rag/eval/control-results.json and control-report.md.
sweep    Day 23: grid over threshold x topK-before x topK-after on the eval set (no LLM calls), writes rag/eval/sweep.{json,md}.
modes-eval Day 23: compares no RAG / plain / +filter / +rerank / +rewrite / all on the eval + control sets (real Gemini; answers cached in rag/cache).
ask also takes: --filter on --rerank on|llm --rewrite on --threshold 0.6 --before 15 --after 4 (stages of the Day 23 pipeline).
citations-eval  Day 24: 12 questions through the cited pipeline -> rag/eval/citations-report.{md,json} (--rewrite on, --no-cache).
chat     Day 25: REPL chat with RAG + task memory over rag/sessions (--session id, --memory full|history-only|none, --keep 4, --batch 2).
scenarios-eval  Day 25: replays rag/eval/scenarios/*.json in FULL / HISTORY_ONLY / NONE variants -> rag/eval/scenarios-report.{md,json} (--variants full,history-only, --scenario id, --delay-ms 0, --no-cache true).
ui       moved: ./gradlew :web-console:run --args="--port 8080" (Chat tab + chunk visualiser).
--embedder gemini  (default if GEMINI_API_KEY is set) calls the Gemini embeddings REST API.
--embedder offline deterministic hashing embedder, no network (lexical only, for tests/demos).
--embedder ollama:<model> local embeddings via Ollama /api/embed (e.g. ollama:embeddinggemma-2:270m), index in rag/index-local-<model>; --embed-prompts off disables task prompts, --ollama-url overrides the server.
--prompt-profile default|local|rewrite,partial,lang,compact (or RAG_PROMPT_PROFILE) selects the wording of the local prompts (default local); the cloud always uses the original.
--ollama-tuning "numCtx=8192,keepAlive=30m,rewrite.maxTokens=96,cited.temperature=0" (or OLLAMA_TUNING) sets sampling/limits for local calls, optionally per call purpose (answer, cited, repair, rewrite, rerank, memory, summary, judge); default = the recommended tuning, `none` = no overrides.
The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] !in setOf("index", "compare", "eval", "ui", "ask", "rag-eval", "sweep", "modes-eval", "citations-eval", "chat", "scenarios-eval")) {
        println(USAGE.trim()); exitProcess(if (args.isEmpty()) 0 else 1)
    }
    val positional = if (args[0] == "ask" && args.size > 1 && !args[1].startsWith("--")) args[1] else null
    val opts = args.drop(if (positional != null) 2 else 1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val key = resolveGeminiApiKey()
    val question = positional ?: opts["question"]
    if (args[0] == "ui") {
        System.err.println("The web UI moved to its own module: ./gradlew :web-console:run [--args=\"--port 8080\"]")
        exitProcess(1)
    }
    val llm = try { LlmProvider.from(opts, key) } catch (e: IllegalArgumentException) { System.err.println(e.message); exitProcess(1) }
    val embedderName = opts["embedder"] ?: llm.defaultEmbedderName
    val embedder: EmbeddingClient = when (embedderName) {
        "gemini" -> {
            if (key.isBlank()) { System.err.println("GEMINI_API_KEY is not set (env var or local.properties)."); exitProcess(2) }
            GeminiEmbeddingClient(key)
        }
        "offline" -> HashingEmbeddingClient()
        else -> if (embedderName.startsWith(LlmProvider.OLLAMA_EMBEDDER_PREFIX)) localEmbedder(embedderName.removePrefix(LlmProvider.OLLAMA_EMBEDDER_PREFIX), llm, opts) else { System.err.println("Unknown embedder '$embedderName'"); exitProcess(1) }
    }
    val outDir = File(opts["out"] ?: when {
        embedderName == "gemini" -> "rag/index"
        embedderName == llm.defaultEmbedderName && llm.isLocal -> LlmProvider.LOCAL_INDEX_DIR
        embedderName.startsWith("ollama:") -> "rag/index-local-" + embedderName.removePrefix("ollama:").replace(Regex("[^A-Za-z0-9.]+"), "-")
        else -> "rag/index-offline"
    })
    println("Embedder: ${embedder.modelName} (${embedder.dimension} dims)")

    runBlocking {
        when (args[0]) {
            "index" -> runIndex(File(opts["corpus"] ?: "rag/corpus"), outDir, embedder)
            "compare" -> runCompare(outDir, embedder)
            "eval" -> runEval(opts, outDir, embedder, embedderName)
            "ask" -> runAsk(opts, question, outDir, embedder, llm)
            "rag-eval" -> runRagEval(opts, outDir, embedder, llm)
            "sweep" -> runSweep(opts, outDir, embedder)
            "modes-eval" -> runModesEval(opts, outDir, embedder, llm)
            "citations-eval" -> runCitationsEval(opts, outDir, embedder, llm)
            "chat" -> com.example.rag.chat.runChatRepl(opts, outDir, embedder, llm)
            "scenarios-eval" -> com.example.rag.chat.runScenariosEval(opts, outDir, embedder, llm)
        }
    }
    exitProcess(0)
}

/** `--embedder ollama:<model>`: local embeddings; task prompts follow the model family unless `--embed-prompts off`. */
private fun localEmbedder(model: String, llm: LlmProvider, opts: Map<String, String>): EmbeddingClient = runBlocking {
    val url = llm.ollamaUrl
    val prompts = if (opts["embed-prompts"] == "off") EmbedPrompts.NONE else EmbedPrompts.defaultsFor(model)
    try {
        OllamaEmbeddingClient.connect(model, prompts, url)
    } catch (e: EmbeddingException) {
        System.err.println(e.message); exitProcess(2)
    }
}

/** GEMINI_API_KEY from the environment or `local.properties` (never committed); empty if neither is set. */
fun resolveGeminiApiKey(): String {
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
    val docs = CorpusLoader.load(corpus.toKxPath())
    val words = docs.sumOf { it.text.split(Regex("\\s+")).size }
    println("Corpus: ${docs.size} documents, $words words (~${words / 500} pages)")
    val store = IndexStore()
    for ((chunker, params) in CHUNKERS) {
        val index = Indexer(embedder).build(docs, chunker, params, corpus.path)
        val file = File(out, "${chunker.strategy.id}.json")
        store.save(index, file.toKxPath())
        println("  ${chunker.strategy.id}: ${index.meta.chunkCount} chunks -> ${file.path} (${file.length() / 1024} KB)")
    }
}

private suspend fun runCompare(dir: File, embedder: EmbeddingClient) {
    val store = IndexStore()
    val fixed = store.load(File(dir, "fixed.json").toKxPath())
    val structure = store.load(File(dir, "structure.json").toKxPath())
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
    val problems = EvalSet.validate(questions, CorpusLoader.load(corpusDir.toKxPath()))
    if (problems.isNotEmpty()) { System.err.println("Invalid eval set:\n" + problems.joinToString("\n")); exitProcess(1) }
    val strategies = opts["strategy"]?.let { listOf(it) } ?: ChunkStrategy.entries.map { it.id }
    val store = IndexStore()
    val metrics = strategies.map { st ->
        val file = File(dir, "$st.json")
        require(file.isFile) { "Missing index ${file.path}; run the index command first." }
        val index = store.load(file.toKxPath())
        require(index.meta.embeddingModel == embedder.modelName) {
            "Index $st was built with ${index.meta.embeddingModel}, but embedder is ${embedder.modelName}."
        }
        Evaluator.run(index, embedder, questions, (opts["k"] ?: "5").toInt())
    }
    val md = Evaluator.renderMarkdown(embedderName, metrics)
    val report = File("rag/eval/report-${embedderName.replace(':', '-')}.md")
    report.writeText(md)
    println(md)
    println("Report written to ${report.path}")
}

private fun buildPipeline(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, llm: LlmProvider): RagPipeline {
    if (!llm.available) { System.err.println(llm.missingMessage()); exitProcess(2) }
    val file = File(dir, "${opts["strategy"] ?: "structure"}.json")
    require(file.isFile) { "Missing index ${file.path}; run the index command first." }
    val index = IndexStore().load(file.toKxPath())
    val retriever = VectorRetriever(embedder, index, (opts["k"] ?: VectorRetriever.DEFAULT_TOP_K.toString()).toInt())
    val generator = llm.generator(opts["model"])
    return stagedPipeline(retriever, generator, if (staged(opts)) ragConfigFrom(opts) else RagConfig.PLAIN, opts["rerank"] == "llm", citations = opts["citations"] != "off", profile = llm.promptProfile)
}

private fun staged(opts: Map<String, String>) = flag(opts, "filter") || flag(opts, "rerank") || flag(opts, "rewrite")

private suspend fun runAsk(opts: Map<String, String>, question: String?, dir: File, embedder: EmbeddingClient, llm: LlmProvider) {
    if (question.isNullOrBlank()) { System.err.println("Usage: ask \"<question>\" [--mode both|rag|no-rag]"); exitProcess(1) }
    val pipeline = buildPipeline(opts, dir, embedder, llm)
    val modes = when (val m = opts["mode"] ?: "both") {
        "both" -> listOf(RagMode.NO_RAG, RagMode.RAG)
        "rag" -> listOf(RagMode.RAG)
        "no-rag" -> listOf(RagMode.NO_RAG)
        else -> { System.err.println("Unknown mode '$m'"); exitProcess(1) }
    }
    for (mode in modes) {
        val a = pipeline.ask(question, mode, if (staged(opts)) ragConfigFrom(opts) else RagConfig.PLAIN).getOrElse { System.err.println("${mode.name} failed: ${it.message}"); exitProcess(3) }
        println("\n=== ${if (mode == RagMode.RAG) "WITH RAG" else "WITHOUT RAG"} ===\n${a.answer}")
        if (mode == RagMode.RAG) {
            a.trace?.takeIf { staged(opts) }?.let { t ->
                println("\nSearch query: ${t.searchQuery}${t.rewriteFallback?.let { " (rewrite discarded: $it)" } ?: ""}")
                println("Retrieved ${t.retrieved.size} -> filtered ${t.filtered.size} -> reranked ${t.reranked.size}")
            }
            val st = a.structured
            if (st != null) {
                if (st.idk) { println("\n(I don't know mode: ${st.idkReason}; no sources by design)"); continue }
                println("\nSources (verified):")
                st.sources.forEach { println("  ${it.file} > ${it.section}  [${it.chunkId}]") }
                println("Quotes:")
                st.quotes.forEach { println("  ${if (it.ok) "OK " else "!! ${it.status}"} \"${it.text.replace("\n", " ")}\"") }
                println("Verification: ${st.verification.summary()}")
                continue
            }
            println("\nSources:")
            a.hits.forEachIndexed { i, h -> println("  [${i + 1}] ${RagPromptBuilder.sourceLabel(h.chunk)}  (score %.3f)".format(h.score)) }
        }
    }
}

private suspend fun runRagEval(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, llm: LlmProvider) {
    val questions = ControlSet.load(File(opts["questions"] ?: "rag/eval/control-questions.json"))
    val problems = ControlSet.validate(questions, CorpusLoader.load(File(opts["corpus"] ?: "rag/corpus").toKxPath()))
    if (problems.isNotEmpty()) { System.err.println("Invalid control set:\n" + problems.joinToString("\n")); exitProcess(1) }
    val pipeline = buildPipeline(opts, dir, embedder, llm)
    val results = ControlScorer.run(pipeline, questions)
    val out = File(opts["out"] ?: "rag/eval")
    out.mkdirs()
    File(out, "control-results.json").writeText(
        Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(ListSerializer(ControlResult.serializer()), results)
    )
    val md = ControlScorer.renderMarkdown(questions, results)
    File(out, "control-report.md").writeText(md)
    println(md)
    println("Written to ${out.path}/control-results.json and control-report.md")
}
