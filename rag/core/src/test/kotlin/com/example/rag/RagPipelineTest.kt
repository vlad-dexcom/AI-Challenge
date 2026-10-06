package com.example.rag

import com.example.core.platform.toKxPath

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.example.core.llm.EmbeddingTaskType
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.TextGenerator

private class RecordingGenerator(private val reply: Result<String> = Result.success("answer [1]")) : TextGenerator {
    val calls = mutableListOf<Pair<String?, String>>()
    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> {
        calls += systemInstruction to prompt
        return reply
    }
}

class RagPipelineTest {
    private val embedder = HashingEmbeddingClient(64)

    private fun chunk(id: String, source: String, section: String, text: String) =
        Chunk(id, source, source, section, text, 0, text.length, ChunkStrategy.STRUCTURE)

    private suspend fun index(vararg chunks: Chunk): VectorIndex {
        val vecs = embedder.embed(chunks.map { it.text }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        val meta = IndexMeta(embedder.modelName, 64, ChunkStrategy.STRUCTURE, "p", "t", "c", chunks.size, chunks.size)
        return VectorIndex(meta, chunks.mapIndexed { i, c -> IndexedChunk(c, vecs[i]) })
    }

    private suspend fun fixtureIndex() = index(
        chunk("a", "squat.md", "Squat > Bracing", "bracing the trunk before descending keeps the spine neutral"),
        chunk("b", "sleep.md", "Sleep > Stages", "deep sleep supports physical recovery and hormone release"),
        chunk("c", "protein.md", "Protein", "protein intake grams per kilogram bodyweight daily target"),
    )

    @Test fun retrieverReturnsTopKMostSimilarFirst() = runTest {
        val hits = VectorRetriever(embedder, fixtureIndex(), topK = 2).retrieve("how much protein per kilogram daily?")
        assertEquals(2, hits.size)
        assertEquals("protein.md", hits[0].chunk.source)
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test fun retrieverRejectsMismatchedEmbeddingModel() = runTest {
        val idx = fixtureIndex()
        val wrong = HashingEmbeddingClient(32)
        try { VectorRetriever(wrong, idx); throw AssertionError("expected failure") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(idx.meta.embeddingModel))
        }
    }

    @Test fun promptBuilderNumbersSourcesWithFileAndSection() = runTest {
        val hits = VectorRetriever(embedder, fixtureIndex(), 2).retrieve("protein per kilogram")
        val prompt = RagPromptBuilder.userPrompt(RagMode.RAG, "Q?", hits)
        assertTrue(prompt.startsWith("Context:\n[1] protein.md > Protein\n"))
        assertTrue(prompt.contains("\n\n[2] "))
        assertTrue(prompt.endsWith("Question: Q?"))
        assertTrue(RagPromptBuilder.systemPrompt(RagMode.RAG).contains("[1]"))
        assertTrue(RagPromptBuilder.systemPrompt(RagMode.RAG).contains(RagPromptBuilder.NOT_FOUND_HINT))
    }

    @Test fun sourceLabelOmitsEmptySection() {
        assertEquals("x.md", RagPromptBuilder.sourceLabel(chunk("1", "x.md", "", "t")))
    }

    @Test fun noRagModeSendsOnlyTheQuestionAndNeverRetrieves() = runTest {
        var retrieved = false
        val gen = RecordingGenerator()
        val pipeline = RagPipeline({ retrieved = true; emptyList() }, gen)
        val a = pipeline.ask("  What is a deload?  ", RagMode.NO_RAG).getOrThrow()
        assertFalse(retrieved)
        assertEquals("What is a deload?", gen.calls.single().second)
        assertFalse(gen.calls.single().first!!.contains("context"))
        assertTrue(a.hits.isEmpty())
        assertTrue(a.sources.isEmpty())
    }

    @Test fun ragModePutsRetrievedChunksIntoThePromptAndReportsSources() = runTest {
        val gen = RecordingGenerator()
        val pipeline = RagPipeline(VectorRetriever(embedder, fixtureIndex(), 2), gen)
        val a = pipeline.ask("protein per kilogram?", RagMode.RAG).getOrThrow()
        assertTrue(gen.calls.single().second.contains("[1] protein.md > Protein"))
        assertTrue(gen.calls.single().first!!.contains("ONLY the numbered context"))
        assertEquals("protein.md > Protein", a.sources.first())
        assertEquals(2, a.hits.size)
    }

    @Test fun compareRunsBothModes() = runTest {
        val gen = RecordingGenerator()
        val cmp = RagPipeline(VectorRetriever(embedder, fixtureIndex(), 1), gen).compare("deep sleep recovery?").getOrThrow()
        assertEquals(2, gen.calls.size)
        assertEquals(RagMode.NO_RAG, cmp.withoutRag.mode)
        assertEquals(RagMode.RAG, cmp.withRag.mode)
        assertEquals(listOf("sleep.md > Sleep > Stages"), cmp.withRag.sources)
    }

    @Test fun failuresFromRetrieverAndGeneratorBecomeResultFailures() = runTest {
        val boom = RagPipeline({ error("embed down") }, RecordingGenerator())
        assertEquals("embed down", boom.ask("q", RagMode.RAG).exceptionOrNull()!!.message)
        val noLlm = RagPipeline({ emptyList() }, RecordingGenerator(Result.failure(IllegalStateException("llm down"))))
        assertEquals("llm down", noLlm.ask("q", RagMode.NO_RAG).exceptionOrNull()!!.message)
        assertTrue(noLlm.compare("q").isFailure)
    }

    @Test fun indexStoreParseRoundTripsFromString() = runTest {
        val idx = fixtureIndex()
        val f = File.createTempFile("idx", ".json")
        try {
            IndexStore().save(idx, f.toKxPath())
            val parsed = IndexStore().parse(f.readText())
            assertEquals(idx.chunks.map { it.chunkId }, parsed.chunks.map { it.chunkId })
        } finally { f.delete() }
    }
}
