package com.example.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingTaskType
import com.example.core.llm.GenerationOptions
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.TextGenerator

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
        assertEquals(GenerationOptions(0.0, true, com.example.core.llm.CallPurpose.JUDGE), gen.calls.single().third)
        assertTrue(gen.calls.single().second.contains("a / b"))
        assertTrue(Judge.parse("not json").isFailure)
    }

    @Test fun judgeOutOfCorpusPromptExplainsTheExpectedRefusal() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("""{"verdict":"correct","reason":"refuses"}""")))
        Judge(gen).judge("q", "answer", emptyList(), outOfCorpus = true).getOrThrow()
        assertTrue(gen.calls.single().second.contains("NO answer"))
    }
}
