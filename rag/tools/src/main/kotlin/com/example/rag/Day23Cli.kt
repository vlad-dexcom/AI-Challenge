package com.example.rag

import com.example.core.platform.toKxPath

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.TextGenerator

private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
private val CACHE_DIR = File("rag/cache")

internal fun flag(opts: Map<String, String>, name: String): Boolean =
    opts[name]?.lowercase() in setOf("true", "on", "yes", "1", "llm", "heuristic")

/** Stage settings from CLI options (`--filter on --rerank llm --rewrite on --threshold 0.6 --before 15 --after 4`). */
internal fun ragConfigFrom(opts: Map<String, String>): RagConfig = RagConfig(
    topKBefore = opts["before"]?.toInt() ?: RagConfig.DEFAULT.topKBefore,
    topKAfter = opts["after"]?.toInt() ?: opts["k"]?.toInt() ?: RagConfig.DEFAULT.topKAfter,
    threshold = opts["threshold"]?.toFloat() ?: RagConfig.DEFAULT.threshold,
    filter = flag(opts, "filter"), rerank = flag(opts, "rerank"), rewrite = flag(opts, "rewrite"),
)

private fun loadIndex(opts: Map<String, String>, dir: File, embedder: EmbeddingClient): VectorIndex {
    val file = File(dir, "${opts["strategy"] ?: "structure"}.json")
    require(file.isFile) { "Missing index ${file.path}; run the index command first." }
    return IndexStore().load(file.toKxPath()).also {
        require(it.meta.embeddingModel == embedder.modelName) { "Index was built with ${it.meta.embeddingModel}, but embedder is ${embedder.modelName}." }
    }
}

internal fun cachedEmbedder(embedder: EmbeddingClient): EmbeddingClient =
    CachedEmbeddingClient(embedder, DiskCache(File(CACHE_DIR, "embeddings")))

internal suspend fun runSweep(opts: Map<String, String>, dir: File, embedder: EmbeddingClient) {
    val questions = EvalSet.load(File(opts["questions"] ?: "rag/eval/questions.json"))
    val problems = EvalSet.validate(questions, CorpusLoader.load(File(opts["corpus"] ?: "rag/corpus").toKxPath()))
    if (problems.isNotEmpty()) { System.err.println("Invalid eval set:\n" + problems.joinToString("\n")); exitProcess(1) }
    val index = loadIndex(opts, dir, embedder)
    val pooled = Sweeper.prepare(index, cachedEmbedder(embedder), questions)
    val rows = Sweeper.grid(pooled)
    val out = File(opts["report"] ?: "rag/eval").also { it.mkdirs() }
    File(out, "sweep.json").writeText(pretty.encodeToString(ListSerializer(SweepRow.serializer()), rows))
    val d = RagConfig.DEFAULT
    val controlFile = File(opts["control"] ?: "rag/eval/control-questions.json")
    val controlTop1 = if (controlFile.isFile) {
        val control = ControlSet.load(controlFile)
        val ev = control.map { EvalQuestion(it.id, it.category, it.question, if (it.inCorpus) it.expectedSources.first().source else null) }
        Sweeper.prepare(index, cachedEmbedder(embedder), ev).map { Triple(it.question.id + " (control, " + it.question.type + ")", it.pool.first().score, it.question.inCorpus) }
    } else emptyList()
    val top1 = pooled.map { Triple(it.question.id + " (eval, " + it.question.type + ")", it.pool.first().score, it.question.inCorpus) } + controlTop1
    val md = Sweeper.renderTop1(top1) + "\n" + Sweeper.renderMarkdown(rows, opts["threshold"]?.toFloat() ?: d.threshold, opts["before"]?.toInt() ?: d.topKBefore, opts["after"]?.toInt() ?: d.topKAfter, questions.size)
    File(out, "sweep.md").writeText(md)
    println(md)
    println("Written to ${out.path}/sweep.json and sweep.md (${rows.size} grid rows)")
}

internal suspend fun runModesEval(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, llm: LlmProvider) {
    if (!llm.available) { System.err.println(llm.missingMessage()); exitProcess(2) }
    val corpus = CorpusLoader.load(File(opts["corpus"] ?: "rag/corpus").toKxPath())
    val eval = EvalSet.load(File(opts["questions"] ?: "rag/eval/questions.json"))
    val control = ControlSet.load(File(opts["control"] ?: "rag/eval/control-questions.json"))
    val problems = EvalSet.validate(eval, corpus) + ControlSet.validate(control, corpus)
    if (problems.isNotEmpty()) { System.err.println("Invalid question sets:\n" + problems.joinToString("\n")); exitProcess(1) }
    val index = loadIndex(opts, dir, embedder)
    val model = opts["model"] ?: llm.defaultModel
    val cache = DiskCache(if (flag(opts, "no-cache")) null else File(CACHE_DIR, "llm"))
    val usage = LlmUsage()
    // Answers use temperature 0.2 as on Day 22; rewriter, reranker and judge pass temperature 0 per call.
    val generator = CachedTextGenerator(llm.generator(model), "$model@0.2", usage, cache)
    val judge = Judge(CachedTextGenerator(llm.generator(model), "$model@judge", LlmUsage(), cache))
    val cfg = ragConfigFrom(opts)
    val specs = ModeSpecs.all(cfg.topKBefore, cfg.topKAfter, cfg.threshold)
    val report = ModesEvaluator(index, cachedEmbedder(embedder), generator, usage, judge).run(specs, eval, control) { println(it) }
    val out = File(opts["report"] ?: "rag/eval").also { it.mkdirs() }
    File(out, "modes-report.json").writeText(pretty.encodeToString(ModesReport.serializer(), report))
    val md = ModesEvaluator.renderMarkdown(report)
    File(out, "modes-report.md").writeText(md)
    println(md)
    println("Written to ${out.path}/modes-report.json and modes-report.md")
}

/** Builds the pipeline used by `ask` / the UI with the stages selected in [cfg]. */
fun stagedPipeline(retriever: Retriever, generator: TextGenerator, cfg: RagConfig, llmRerank: Boolean, citations: Boolean = false): RagPipeline =
    RagPipeline(retriever, generator, LlmQueryRewriter(generator), if (llmRerank) LlmReranker(generator) else HeuristicReranker(), config = cfg, citations = citations)
