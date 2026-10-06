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
