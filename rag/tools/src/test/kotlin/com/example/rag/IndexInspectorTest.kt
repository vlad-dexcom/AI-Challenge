package com.example.rag

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Instant
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingTaskType
import com.example.core.llm.l2Normalized

class IndexInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val text = "# Sleep\n\n## Hygiene\n\n" + "Sleep eight hours in a dark cool bedroom to recover. ".repeat(20) +
        "\n\n## Protein\n\n" + "Eat protein every day to build muscle. ".repeat(20) + "\n"
    private val docs = listOf(Document("sleep.md", "Sleep", text))
    private val chunker = StructureChunker(400, 50)

    private suspend fun good() = Indexer(HashingEmbeddingClient(64)) { Instant.fromEpochMilliseconds(0) }.build(docs, chunker, "max=400, min=50", "corpus")

    private fun run(index: VectorIndex, corpus: List<Document>? = docs) =
        IndexInspector.inspect(index, "p", 1, "t", corpus)

    private fun failed(r: InspectReport) = r.checks.filter { !it.ok }.map { it.name }

    @Test fun goodIndexPassesAllChecks() = runTest {
        val r = run(good())
        assertEquals(emptyList<String>(), failed(r))
        assertTrue(r.ok)
        assertEquals(3, r.samples.size)
        assertEquals(5, r.samples[0].vectorHead.size)
        assertEquals(1.0, r.vectorStats!!.avgNorm, 1e-3)
    }

    @Test fun wrongDimensionIsReported() = runTest {
        val i = good()
        val bad = VectorIndex(i.meta, listOf(IndexedChunk(i.entries[0].chunk, FloatArray(10))) + i.entries.drop(1))
        assertTrue(failed(run(bad)).any { it.startsWith("Vector length") })
    }

    @Test fun nanAndZeroVectorsAreReported() = runTest {
        val i = good()
        val nan = FloatArray(64) { if (it == 3) Float.NaN else 0.1f }
        val bad = VectorIndex(i.meta, listOf(IndexedChunk(i.entries[0].chunk, nan), IndexedChunk(i.entries[1].chunk, FloatArray(64))) + i.entries.drop(2))
        val f = failed(run(bad))
        assertTrue(f.contains("No NaN / Infinity"))
        assertTrue(f.contains("No zero vectors"))
    }

    @Test fun missingMetadataAndDuplicateIdsAreReported() = runTest {
        val i = good()
        val c0 = i.entries[0].chunk.copy(source = "", title = "")
        val c1 = i.entries[1].chunk.copy(chunkId = c0.chunkId)
        val bad = VectorIndex(i.meta, listOf(IndexedChunk(c0, i.entries[0].embedding), IndexedChunk(c1, i.entries[1].embedding)) + i.entries.drop(2))
        val r = run(bad)
        assertTrue(failed(r).any { it.startsWith("Chunk metadata present") })
        assertTrue(failed(r).contains("Unique chunk_id"))
        assertTrue(r.checks.first { it.name.startsWith("Chunk metadata") }.detail.contains("source"))
    }

    @Test fun staleIndexIsDetectedWhenCorpusChanged() = runTest {
        val changed = listOf(docs[0].copy(text = text.replace("muscle", "stones")))
        val r = run(good(), changed)
        assertEquals(listOf("Index is up to date with the corpus (fresh re-chunk)"), failed(r))
        assertTrue(r.checks.last().detail.contains("DIFFERS"))
        // Same chunk count but different content must still be caught by the hash.
        assertEquals(good().entries.size, changed.flatMap { chunker.chunk(it) }.size)
    }

    @Test fun missingCorpusAndMetaCountMismatchFail() = runTest {
        assertTrue(failed(run(good(), null)).any { it.startsWith("Index is up to date") })
        val i = good()
        val bad = VectorIndex(i.meta.copy(chunkCount = 999), i.entries)
        assertTrue(failed(run(bad)).contains("Chunk count matches meta"))
    }

    @Test fun unparseableFileGivesFailedReport() {
        val f = tmp.newFile("fixed.json").also { it.writeText("{not json") }
        val r = IndexInspector.inspect(f, null)
        assertFalse(r.ok)
        assertEquals("fixed", r.strategy)
    }

    @Test fun pingReportsLexicalOnlyForOfflineEmbedder() = runTest {
        val r = EmbedderPing.run(HashingEmbeddingClient(256))
        assertTrue(r.lexicalOnly)
        assertEquals(256, r.vectorLength)
        assertEquals(1.0, r.norm, 1e-3)
        assertTrue(r.relatedSimilarity > r.unrelatedSimilarity)
        assertTrue(r.verdict.contains("lexical-only"))
    }

    @Test fun pingFlagsEmbedderThatIsNotSemantic() = runTest {
        val constant = object : EmbeddingClient {
            override val modelName = "fake-semantic"
            override val dimension = 2
            override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType) =
                texts.mapIndexed { i, _ -> if (i == 2) floatArrayOf(1f, 0f) else floatArrayOf(0.6f, 0.8f) }.map { it.l2Normalized() }
        }
        // related = identical vectors (1.0); unrelated = 0.6 -> PASS. Swap so the unrelated one wins -> FAIL.
        assertTrue(EmbedderPing.run(constant).verdict.startsWith("PASS"))
        val inverted = object : EmbeddingClient by constant {
            override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType) =
                listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 0f))
        }
        assertTrue(EmbedderPing.run(inverted).verdict.startsWith("FAIL"))
    }
}
