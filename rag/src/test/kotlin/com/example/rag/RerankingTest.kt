package com.example.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chunk(id: String, source: String, section: String, text: String) =
    Chunk(id, source, source, section, text, 0, text.length, ChunkStrategy.STRUCTURE)

private fun hit(id: String, score: Float, source: String = "$id.md", section: String = "", text: String = "text of $id") =
    SearchHit(chunk(id, source, section, text), score)

/** Records prompts and replies from a script; fails the test if called more often than scripted. */
private class ScriptedGenerator(private val replies: MutableList<Result<String>>) : TextGenerator {
    val calls = mutableListOf<Triple<String?, String, GenerationOptions>>()
    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        generate(systemInstruction, prompt, GenerationOptions())

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
        calls += Triple(systemInstruction, prompt, options)
        return if (replies.isEmpty()) Result.success("answer") else replies.removeAt(0)
    }
}

class FilterAndRerankerTest {
    @Test fun thresholdFilterKeepsHitsAtOrAboveTheCutoffInOrder() = runTest {
        val hits = listOf(hit("a", 0.8f), hit("b", 0.65f), hit("c", 0.64f))
        assertEquals(listOf("a", "b"), ThresholdFilter(0.65f).filter("q", hits).map { it.chunk.chunkId })
        assertTrue(ThresholdFilter(0.9f).filter("q", hits).isEmpty())
    }

    @Test fun heuristicRerankerPromotesLexicalAndHeadingMatches() = runTest {
        val hits = listOf(
            hit("generic", 0.70f, "a.md", "Intro", "general advice about training and lifting weights"),
            hit("match", 0.66f, "b.md", "Protein intake", "protein grams per kilogram of body weight each day"),
        )
        val out = HeuristicReranker().rerank("how much protein per kilogram", hits, 2)
        assertEquals(listOf("match", "generic"), out.map { it.hit.chunk.chunkId })
        assertEquals(0.66f, out[0].hit.score)
    }

    @Test fun heuristicRerankerTrimsAndPenalisesNearDuplicatesFromTheSameSection() = runTest {
        val text = "protein intake grams kilogram daily target muscle"
        val hits = listOf(
            hit("a", 0.80f, "p.md", "Protein", text),
            hit("b", 0.79f, "p.md", "Protein", text),
            hit("c", 0.75f, "q.md", "Other", "sleep recovery hormones"),
        )
        val out = HeuristicReranker().rerank("protein intake", hits, 2)
        assertEquals(listOf("a", "c"), out.map { it.hit.chunk.chunkId })
        assertTrue(HeuristicReranker().rerank("x", emptyList(), 3).isEmpty())
    }

    @Test fun llmRerankerOrdersByModelScoresAndTrims() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("1: 2\n2: 9\n3: 5")))
        val out = LlmReranker(gen).rerank("q", listOf(hit("a", 0.9f), hit("b", 0.7f), hit("c", 0.6f)), 2)
        assertEquals(listOf("b", "c"), out.map { it.hit.chunk.chunkId })
        assertEquals(0.0, gen.calls.single().third.temperature)
    }

    @Test fun llmRerankerFallsBackToCosineOrderOnFailureOrGarbage() = runTest {
        val hits = listOf(hit("a", 0.9f), hit("b", 0.7f))
        for (reply in listOf(Result.failure<String>(IllegalStateException("429")), Result.success("no idea"))) {
            val out = LlmReranker(ScriptedGenerator(mutableListOf(reply))).rerank("q", hits, 1)
            assertEquals(listOf("a"), out.map { it.hit.chunk.chunkId })
        }
    }

    @Test fun llmRerankerParsingIgnoresOutOfRangeAndClampsScores() {
        assertEquals(mapOf(1 to 10.0, 2 to 0.0), LlmReranker.parse("[1]: 15\n2 = 0\n7: 3", 2))
    }
}

class QueryRewriterTest {
    @Test fun usesTheRewriteAndSendsHistory() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("\"Search query: protein intake grams per kilogram muscle\"")))
        val out = LlmQueryRewriter(gen).rewrite("how much protein do I need", listOf(HistoryMessage("user", "I lift 4x a week")))
        assertEquals("protein intake grams per kilogram muscle", out.query)
        assertNull(out.fallbackReason)
        assertTrue(gen.calls.single().second.contains("I lift 4x a week"))
    }

    @Test fun fallsBackToTheOriginalOnFailureEmptyMultilineLongAndDrift() = runTest {
        val q = "how much protein do I need"
        val bad = listOf(
            Result.failure<String>(IllegalStateException("boom")),
            Result.success("  "),
            Result.success("line one\nline two"),
            Result.success("protein ".repeat(100)),
            Result.success("squat depth knee valgus"),
        )
        for (r in bad) {
            val out = LlmQueryRewriter(ScriptedGenerator(mutableListOf(r))).rewrite(q, emptyList())
            assertEquals(q, out.query)
            assertTrue(out.fallbackReason != null)
        }
    }

    @Test fun russianQuestionMustComeBackTranslated() {
        val ru = "Сколько белка нужно для роста мышц?"
        assertEquals("protein intake muscle growth", LlmQueryRewriter.validate(ru, "protein intake muscle growth").query)
        assertEquals(ru, LlmQueryRewriter.validate(ru, "Белок для мышц").query)
    }
}

class TwoStagePipelineTest {
    private val embedder = HashingEmbeddingClient(64)

    private suspend fun fixture(): VectorRetriever {
        val chunks = listOf(
            chunk("p", "protein.md", "Protein", "protein intake grams per kilogram bodyweight daily target"),
            chunk("s", "sleep.md", "Sleep", "deep sleep supports physical recovery and hormone release"),
            chunk("q", "squat.md", "Squat", "bracing the trunk before descending keeps the spine neutral"),
        )
        val vecs = embedder.embed(chunks.map { it.text }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        val meta = IndexMeta(embedder.modelName, 64, ChunkStrategy.STRUCTURE, "p", "t", "c", 3, 3)
        return VectorRetriever(embedder, VectorIndex(meta, chunks.mapIndexed { i, c -> IndexedChunk(c, vecs[i]) }), 3)
    }

    @Test fun stagesReportRetrievedFilteredAndRerankedAndTrimToTopKAfter() = runTest {
        val gen = ScriptedGenerator(mutableListOf())
        val p = RagPipeline(fixture(), gen)
        val cfg = RagConfig(topKBefore = 3, topKAfter = 1, threshold = 0.1f, filter = true, rerank = true)
        val t = p.retrieve("protein intake grams per kilogram", cfg)
        assertEquals(3, t.retrieved.size)
        assertTrue(t.filtered.size in 1..3)
        assertEquals(listOf("p"), t.finalHits.map { it.chunk.chunkId })
        assertTrue(gen.calls.isEmpty())
    }

    @Test fun nothingPassingTheFilterSkipsTheModelAndSaysSo() = runTest {
        val gen = ScriptedGenerator(mutableListOf())
        val a = RagPipeline(fixture(), gen).ask("protein intake", RagMode.RAG, RagConfig(3, 2, threshold = 0.999f, filter = true)).getOrThrow()
        assertTrue(a.insufficientContext)
        assertTrue(a.hits.isEmpty())
        assertTrue(a.answer.contains(RagPromptBuilder.NOT_FOUND_HINT))
        assertTrue(gen.calls.isEmpty())
        assertEquals(3, a.trace!!.retrieved.size)
    }

    @Test fun withoutTheFilterWeakChunksStillReachThePrompt() = runTest {
        val gen = ScriptedGenerator(mutableListOf())
        val a = RagPipeline(fixture(), gen).ask("zzz unrelated", RagMode.RAG, RagConfig(3, 2, threshold = 0.999f)).getOrThrow()
        assertFalse(a.insufficientContext)
        assertEquals(2, a.hits.size)
        assertEquals(1, gen.calls.size)
    }

    @Test fun rewriteChangesTheSearchQueryButNotTheQuestionInThePrompt() = runTest {
        val searched = mutableListOf<String>()
        val base = fixture()
        val retriever = object : Retriever {
            override suspend fun retrieve(question: String) = base.retrieve(question).also { searched += question }
            override suspend fun retrieve(question: String, topK: Int) = base.retrieve(question, topK).also { searched += question }
        }
        val gen = ScriptedGenerator(mutableListOf(Result.success("Original?")))
        val rewriter = QueryRewriter { _, history -> assertTrue(history.isEmpty()); RewriteOutcome("protein intake grams") }
        val a = RagPipeline(retriever, gen, rewriter).ask("How much protein do I need?", RagMode.RAG, RagConfig(3, 2, rewrite = true)).getOrThrow()
        assertEquals(listOf("protein intake grams"), searched)
        assertEquals("protein intake grams", a.trace!!.searchQuery)
        assertTrue(gen.calls.single().second.endsWith("Question: How much protein do I need?"))
    }

    @Test fun historyIsForwardedToTheRewriter() = runTest {
        var seen: List<HistoryMessage>? = null
        val rewriter = QueryRewriter { q, h -> seen = h; RewriteOutcome(q) }
        val h = listOf(HistoryMessage("user", "earlier"))
        RagPipeline(fixture(), ScriptedGenerator(mutableListOf()), rewriter).ask("q", RagMode.RAG, RagConfig(3, 2, rewrite = true), h).getOrThrow()
        assertEquals(h, seen)
    }

    @Test fun defaultConfigKeepsDay22Behaviour() = runTest {
        val gen = ScriptedGenerator(mutableListOf())
        val a = RagPipeline(fixture(), gen).ask("protein intake", RagMode.RAG).getOrThrow()
        assertEquals(3, a.hits.size)
        assertFalse(a.insufficientContext)
    }

    @Test fun rewriteIsSkippedWhenNoRewriterIsConfigured() = runTest {
        val t = RagPipeline(fixture(), ScriptedGenerator(mutableListOf())).retrieve("protein", RagConfig(3, 2, rewrite = true))
        assertEquals("protein", t.searchQuery)
    }
}

class SweepAndModesTest {
    private fun q(id: String, source: String?) = EvalQuestion(id, if (source == null) "out_of_corpus" else "direct", "question $id", source)

    private val pooled = listOf(
        PooledQuestion(q("1", "a.md"), listOf(hit("x", 0.80f, "b.md"), hit("y", 0.78f, "a.md"), hit("z", 0.50f, "c.md"))),
        PooledQuestion(q("2", "a.md"), listOf(hit("x", 0.90f, "a.md"), hit("y", 0.40f, "b.md"))),
        PooledQuestion(q("3", null), listOf(hit("x", 0.55f, "a.md"))),
    )

    @Test fun sweepMetricsCountRanksRejectionsAndPrecision() = runTest {
        val r = Sweeper.evaluate(pooled, threshold = 0.6f, before = 5, after = 3, rerank = false)
        assertEquals(0.5, r.hitAt1, 1e-9)
        assertEquals(1.0, r.hitAt3, 1e-9)
        assertEquals(0.75, r.mrr, 1e-9)
        assertEquals(1.0, r.outOfCorpusRejected, 1e-9)
        assertEquals(0.0, r.inCorpusRejected, 1e-9)
        assertEquals((0.5 + 1.0) / 2, r.precision, 1e-9)
        assertEquals(1.0, r.avgKept, 1e-9)
    }

    @Test fun aHighThresholdRejectsInCorpusQuestionsAndTopKBeforeLimitsThePool() = runTest {
        assertEquals(1.0, Sweeper.evaluate(pooled, 0.95f, 5, 3, false).inCorpusRejected, 1e-9)
        assertEquals(0.5, Sweeper.evaluate(pooled, 0.0f, 1, 3, false).hitAt3, 1e-9)
    }

    @Test fun gridCoversEveryCombinationWithAfterNotAboveBefore() = runTest {
        val rows = Sweeper.grid(pooled)
        assertTrue(rows.all { it.topKAfter <= it.topKBefore })
        assertEquals(Sweeper.THRESHOLDS.size * 2 * (Sweeper.BEFORE.sumOf { b -> Sweeper.AFTER.count { it <= b } }), rows.size)
        assertTrue(Sweeper.renderMarkdown(rows, 0.6f, 10, 4, 3).contains("### Threshold"))
    }

    @Test fun modeSpecsHaveTheExpectedStages() {
        val specs = ModeSpecs.all(10, 4, 0.65f).associateBy { it.id }
        assertFalse(specs.getValue("A").rag)
        assertEquals(RagConfig(4, 4, 0.65f), specs.getValue("B").config)
        assertTrue(specs.getValue("C").config.filter && !specs.getValue("C").config.rerank)
        assertTrue(specs.getValue("D").config.rerank && !specs.getValue("D").llmRerank && specs.getValue("D2").llmRerank)
        assertTrue(specs.getValue("E").config.rewrite && !specs.getValue("E").config.filter)
        assertTrue(specs.getValue("F").config.let { it.filter && it.rerank && it.rewrite })
    }

    @Test fun modeSummaryAggregatesRetrievalFactsRejectionAndCalls() {
        val spec = ModeSpecs.all(10, 4, 0.65f).first { it.id == "C" }
        val items = listOf(
            ModeItem("C", "eval", "q1", "direct", "x", rank = 1, finalCount = 2),
            ModeItem("C", "eval", "q2", "direct", "x", rank = null, finalCount = 0),
            ModeItem("C", "eval", "q3", "out_of_corpus", "x", insufficient = true, answer = "n/a", admitsNoInfo = true, llmCalls = 0),
            ModeItem("C", "control", "c1", "specific", "x", rank = 2, finalCount = 3, answer = "a", factsHit = 2, factsTotal = 3, judge = "PARTIAL", llmCalls = 2, llmMs = 4000),
        )
        val s = ModesEvaluator.summarize(spec, items)
        assertEquals(0.5, s.hitAt1!!, 1e-9)
        assertEquals(0.5, s.mrr!!, 1e-9)
        assertEquals(2, s.factsHit); assertEquals(3, s.factsTotal)
        assertEquals(1, s.judgePartial)
        assertEquals(1, s.oocRejected); assertEquals(1, s.oocTotal)
        assertEquals(1, s.inCorpusRejected); assertEquals(3, s.inCorpusTotal)
        assertEquals(1.0, s.avgLlmCalls, 1e-9)
        assertTrue(ModesEvaluator.renderMarkdown(ModesReport("t", listOf(s), items)).contains("C + filter"))
    }
}

class InfraTest {
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun cachedGeneratorCallsTheModelOnceCountsUsageAndRecordsLatency() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("one"), Result.success("two")))
        val usage = LlmUsage()
        val cached = CachedTextGenerator(gen, "m", usage, DiskCache(tmp.newFolder("c")))
        assertEquals("one", cached.generate("s", "p").getOrThrow())
        assertEquals("one", cached.generate("s", "p").getOrThrow())
        assertEquals("two", cached.generate("s", "other").getOrThrow())
        assertEquals(2, gen.calls.size)
        assertEquals(3, usage.calls)
    }

    @Test fun failuresAndDifferentOptionsAreNotServedFromTheCache() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.failure(IllegalStateException("x")), Result.success("ok"), Result.success("json")))
        val cached = CachedTextGenerator(gen, "m", cache = DiskCache(tmp.newFolder("c")))
        assertTrue(cached.generate("s", "p").isFailure)
        assertEquals("ok", cached.generate("s", "p").getOrThrow())
        assertEquals("json", cached.generate("s", "p", GenerationOptions(temperature = 0.0, json = true)).getOrThrow())
        assertEquals(3, gen.calls.size)
    }

    @Test fun cachedEmbeddingsAreReusedAcrossCalls() = runTest {
        var calls = 0
        val inner = object : EmbeddingClient {
            override val modelName = "m"; override val dimension = 2
            override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray> {
                calls += texts.size
                return texts.map { floatArrayOf(it.length.toFloat(), 1f) }
            }
        }
        val c = CachedEmbeddingClient(inner, DiskCache(tmp.newFolder("e")))
        val a = c.embed(listOf("ab", "abc"), EmbeddingTaskType.RETRIEVAL_QUERY)
        val b = c.embed(listOf("abc", "zzzz"), EmbeddingTaskType.RETRIEVAL_QUERY)
        assertEquals(3, calls)
        assertEquals(3f, b[0][0]); assertEquals(4f, b[1][0]); assertEquals(2f, a[0][0])
    }

    @Test fun judgeUsesTemperatureZeroJsonAndParsesTheVerdict() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("""{"verdict":"Partial","reason":"only two of five"}""")))
        val r = Judge(gen).judge("q", "answer", listOf(listOf("a", "b")), outOfCorpus = false).getOrThrow()
        assertEquals(Verdict.PARTIAL, r.verdict)
        assertEquals("only two of five", r.reason)
        assertEquals(GenerationOptions(0.0, true), gen.calls.single().third)
        assertTrue(gen.calls.single().second.contains("a / b"))
        assertTrue(Judge.parse("not json").isFailure)
    }

    @Test fun judgeOutOfCorpusPromptExplainsTheExpectedRefusal() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("""{"verdict":"correct","reason":"refuses"}""")))
        Judge(gen).judge("q", "answer", emptyList(), outOfCorpus = true).getOrThrow()
        assertTrue(gen.calls.single().second.contains("NO answer"))
    }
}
