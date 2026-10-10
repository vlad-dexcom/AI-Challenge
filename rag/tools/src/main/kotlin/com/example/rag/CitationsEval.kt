package com.example.rag

import com.example.core.platform.toKxPath

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.CallPurpose
import com.example.core.llm.GenerationOptions
import com.example.core.llm.TextGenerator

/** One question of the Day 24 set. [expected] = (source file, section keyword) pairs; empty for out-of-corpus questions. */
data class CitationQuestion(val id: String, val lang: String, val category: String, val question: String, val expected: List<ExpectedSource>) {
    val inCorpus: Boolean get() = category != "out_of_corpus"
}

@Serializable
data class ManualVerdict(val verdict: String, val note: String)

@Serializable
data class CitationItem(
    val id: String,
    val lang: String,
    val category: String,
    val question: String,
    val topCosine: Float?,
    val passedFilter: Int,
    val idk: Boolean,
    val idkReason: IdkReason?,
    val answer: String,
    val sources: List<String>,
    val quotes: List<QuoteCheck>,
    val attempts: Int,
    val sourcesPresent: Boolean,
    val quotesPresent: Boolean,
    val quotesVerbatim: Boolean,
    val citedAllRetrieved: Boolean,
    val expectedSourceCited: Boolean?,
    val idkCorrect: Boolean,
    /** LLM-judged (same model family as the answerer): does the answer follow from the quotes alone? null for IDK answers. */
    val meaningJudge: String? = null,
    val meaningJudgeReason: String? = null,
    val manual: ManualVerdict? = null,
    val error: String? = null,
)

@Serializable
data class CitationsReport(val spec: String, val items: List<CitationItem>)

/** LLM-as-judge for "does the meaning of the answer match the quotes". Temperature 0; the verdict is LLM-judged and shares the answerer's biases. */
class QuoteJudge(private val generator: TextGenerator) {
    suspend fun judge(question: String, answer: String, quotes: List<String>): Result<Pair<String, String>> {
        val prompt = "Question: $question\n\nAnswer:\n$answer\n\nQuotes (the only evidence):\n" + quotes.joinToString("\n") { "- $it" } +
            "\n\nDoes every claim of the answer follow from the quotes, and do the quotes not contradict it? " +
            "SUPPORTED = yes; PARTIAL = some claims go beyond the quotes; UNSUPPORTED = the answer is not backed by or contradicts the quotes. " +
            "The answer may be in another language than the quotes. Reply with JSON only: {\"verdict\":\"supported|partial|unsupported\",\"reason\":\"<one short sentence>\"}"
        val reply = generator.generate("You are a strict fact-checker. Judge only against the given quotes.", prompt, GenerationOptions(0.0, true, CallPurpose.JUDGE))
            .getOrElse { return Result.failure(it) }
        val verdict = Regex("\"verdict\"\\s*:\\s*\"(supported|partial|unsupported)\"", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)?.uppercase()
            ?: return Result.failure(IllegalArgumentException("Unparsable judge reply: ${reply.take(100)}"))
        val reason = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(reply)?.groupValues?.get(1).orEmpty()
        return Result.success(verdict to reason)
    }
}

class CitationsEvaluator(
    private val pipeline: RagPipeline,
    private val config: RagConfig,
    private val judge: QuoteJudge?,
) {
    suspend fun run(questions: List<CitationQuestion>, manual: Map<String, ManualVerdict> = emptyMap(), onProgress: (String) -> Unit = {}): CitationsReport {
        val items = questions.map { q ->
            onProgress("${q.id}: ${q.question}")
            evaluate(q).copy(manual = manual[q.id])
        }
        return CitationsReport(
            "${questions.size} questions (${questions.count { it.inCorpus }} in corpus, ${questions.count { !it.inCorpus }} out of corpus); " +
                "threshold ${config.threshold}, topK ${config.topKBefore}->${config.topKAfter}, rewrite ${if (config.rewrite) "on" else "off"}", items,
        )
    }

    private suspend fun evaluate(q: CitationQuestion): CitationItem {
        val a = pipeline.ask(q.question, RagMode.RAG, config).getOrElse {
            return CitationItem(q.id, q.lang, q.category, q.question, null, 0, false, null, "", emptyList(), emptyList(), 0, false, false, false, false, null, false, error = it.message)
        }
        val st = a.structured ?: error("pipeline was built without citations")
        val v = st.verification
        val labels = st.sources.map { s -> if (s.section.isBlank()) s.file else "${s.file} > ${s.section}" }
        val expectedHit = if (q.expected.isEmpty()) null
        else {
            val cited = a.hits.filter { h -> st.sources.any { it.chunkId == h.chunk.chunkId } }
            q.expected.all { e -> cited.any { it.chunk.source == e.source && ControlScorer.coversSection(it.chunk, e.section) } }
        }
        var verdict: String? = null
        var reason: String? = null
        if (!st.idk && judge != null) {
            judge.judge(q.question, st.answer, st.quotes.filter { it.ok }.map { it.text }).fold({ verdict = it.first; reason = it.second }, { reason = "judge failed: ${it.message}" })
        }
        return CitationItem(
            q.id, q.lang, q.category, q.question, a.trace?.retrieved?.firstOrNull()?.score, a.trace?.filtered?.size ?: 0,
            st.idk, st.idkReason, st.answer, labels, st.quotes, v.attempts,
            sourcesPresent = st.sources.isNotEmpty(),
            quotesPresent = st.quotes.any { it.ok },
            quotesVerbatim = st.quotes.isNotEmpty() && st.quotes.all { it.ok },
            citedAllRetrieved = v.sources.isNotEmpty() && v.sources.all { it.ok },
            expectedSourceCited = expectedHit,
            idkCorrect = st.idk == !q.inCorpus,
            meaningJudge = verdict, meaningJudgeReason = reason,
        )
    }

    companion object {
        /** The 10 control questions plus two Russian ones (one in the corpus, one out of it). */
        fun defaultQuestions(control: List<ControlQuestion>): List<CitationQuestion> =
            control.map { CitationQuestion(it.id, "en", it.category, it.question, it.expectedSources) } + listOf(
                CitationQuestion("ru01", "ru", "specific", "Что такое NEAT и почему он падает на диете?", listOf(ExpectedSource("09-fat-loss-and-body-composition.md", "NEAT"))),
                CitationQuestion("ru02", "ru", "out_of_corpus", "Кто выиграл чемпионат мира по пауэрлифтингу в этом году?", emptyList()),
            )

        private fun mark(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "–" }

        fun renderMarkdown(report: CitationsReport): String = buildString {
            val items = report.items
            appendLine("# Day 24 citations report\n")
            appendLine("Set: ${report.spec}. Automatic checks are computed in code; \"meaning\" is LLM-judged (same model family as the answerer, treat it as a noisy second opinion); \"manual\" is the author's own verdict.\n")
            appendLine("| id | lang | category | top cosine | IDK | sources | quotes (ok/total) | verbatim | cited ⊆ retrieved | expected source cited | IDK correct | meaning (LLM) | manual |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (i in items) {
                appendLine(
                    "| ${i.id} | ${i.lang} | ${i.category} | ${i.topCosine?.let { "%.3f".format(it) } ?: "–"} | ${if (i.idk) "yes (${i.idkReason})" else "no"} | " +
                        "${if (i.idk) "–" else i.sources.size} | ${if (i.idk) "–" else "${i.quotes.count { it.ok }}/${i.quotes.size}"} | ${if (i.idk) "–" else mark(i.quotesVerbatim)} | " +
                        "${if (i.idk) "–" else mark(i.citedAllRetrieved)} | ${mark(i.expectedSourceCited.takeIf { !i.idk })} | ${mark(i.idkCorrect)} | ${i.meaningJudge ?: "–"} | ${i.manual?.verdict ?: "–"} |"
                )
            }
            val answered = items.filter { !it.idk && it.error == null }
            appendLine("\n**Totals.** answered ${answered.size}/${items.size}; sources present in ${answered.count { it.sourcesPresent }}/${answered.size} answers; " +
                "verified quotes present in ${answered.count { it.quotesPresent }}/${answered.size}; all quotes verbatim in ${answered.count { it.quotesVerbatim }}/${answered.size}; " +
                "IDK exactly on out-of-corpus: ${items.count { it.idkCorrect }}/${items.size}; meaning SUPPORTED ${answered.count { it.meaningJudge == "SUPPORTED" }}/${answered.size} (LLM-judged).\n")
            appendLine("## Answers\n")
            for (i in items) {
                appendLine("### ${i.id} (${i.lang}, ${i.category}) ${i.question}\n")
                appendLine(i.answer.trim().prependIndent("> ") + "\n")
                if (!i.idk) {
                    appendLine("Sources: " + i.sources.joinToString("; ") + "\n")
                    i.quotes.forEach { appendLine("- ${if (it.ok) "✓" else "⚠ ${it.status}"} `${it.resolvedChunkId ?: it.chunkId}`: “${it.text}”") }
                    appendLine()
                }
                i.meaningJudge?.let { appendLine("Meaning (LLM-judged): **$it** - ${i.meaningJudgeReason}\n") }
                i.manual?.let { appendLine("Manual: **${it.verdict}** - ${it.note}\n") }
                i.error?.let { appendLine("Error: $it\n") }
            }
        }
    }
}

private val reportJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

internal suspend fun runCitationsEval(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, llm: LlmProvider) {
    if (!llm.available) { System.err.println(llm.missingMessage()); kotlin.system.exitProcess(2) }
    val corpus = CorpusLoader.load(File(opts["corpus"] ?: "rag/corpus").toKxPath())
    val control = ControlSet.load(File(opts["control"] ?: "rag/eval/control-questions.json"))
    val problems = ControlSet.validate(control, corpus)
    if (problems.isNotEmpty()) { System.err.println("Invalid control set:\n" + problems.joinToString("\n")); kotlin.system.exitProcess(1) }
    val file = File(dir, "${opts["strategy"] ?: "structure"}.json")
    require(file.isFile) { "Missing index ${file.path}; run the index command first." }
    val index = IndexStore().load(file.toKxPath())
    val model = opts["model"] ?: llm.defaultModel
    val cache = DiskCache(if (flag(opts, "no-cache")) null else File("rag/cache/llm"))
    val usage = LlmUsage()
    val generator = CachedTextGenerator(llm.generator(model), "$model@cite", usage, cache)
    val judge = QuoteJudge(CachedTextGenerator(llm.generator(model), "$model@quote-judge", LlmUsage(), cache))
    val cfg = RagConfig(filter = true, rerank = true, rewrite = flag(opts, "rewrite"), threshold = opts["threshold"]?.toFloat() ?: RagConfig.DEFAULT.threshold)
    val pipeline = stagedPipeline(VectorRetriever(cachedEmbedder(embedder), index, cfg.topKAfter), generator, cfg, false, citations = true, profile = llm.promptProfile)
    val out = File(opts["report"] ?: "rag/eval").also { it.mkdirs() }
    val manualFile = File(out, "citations-manual.json")
    val manual = if (manualFile.isFile) Json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), ManualVerdict.serializer()), manualFile.readText()) else emptyMap()
    val report = CitationsEvaluator(pipeline, cfg, judge).run(CitationsEvaluator.defaultQuestions(control), manual) { println(it) }
    File(out, "citations-report.json").writeText(reportJson.encodeToString(CitationsReport.serializer(), report))
    val md = CitationsEvaluator.renderMarkdown(report)
    File(out, "citations-report.md").writeText(md)
    println(md)
    println("Written to ${out.path}/citations-report.json and citations-report.md (${usage.calls} answer-side LLM calls)")
}
