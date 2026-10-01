package com.example.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class IndexingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val docs = listOf(
        Document("squat.md", "Squat", "# Squat\n\n## Bracing\n\nBrace your core and keep the barbell over mid-foot while squatting deep. ".repeat(5)),
        Document("sleep.md", "Sleep", "# Sleep\n\n## Hygiene\n\nSleep eight hours in a dark cool bedroom to recover from training. ".repeat(5)),
    )

    private suspend fun build(strategy: Chunker = StructureChunker(400, 50)) =
        Indexer(HashingEmbeddingClient(64)) { Instant.parse("2026-01-01T00:00:00Z") }
            .build(docs, strategy, "test", "mem")

    @Test fun indexerEmbedsEveryChunkAndFillsMetadata() = runTest {
        val index = build()
        assertEquals(index.entries.size, index.meta.chunkCount)
        assertEquals("offline-hashing-bow-64", index.meta.embeddingModel)
        assertEquals(64, index.meta.dimension)
        assertEquals(ChunkStrategy.STRUCTURE, index.meta.strategy)
        assertEquals("2026-01-01T00:00:00Z", index.meta.createdAt)
        assertEquals(2, index.meta.documentCount)
        assertTrue(index.entries.all { it.embedding.size == 64 && it.chunk.chunkId.isNotBlank() })
    }

    @Test fun storeRoundTripsMetadataChunksAndVectors() = runTest {
        val index = build(FixedSizeChunker(200, 20))
        val file = tmp.newFile("idx.json")
        IndexStore().save(index, file)
        val loaded = IndexStore().load(file)
        assertEquals(index.meta, loaded.meta)
        assertEquals(index.chunks, loaded.chunks)
        index.entries.zip(loaded.entries).forEach { (a, b) ->
            assertArrayEquals(a.embedding, b.embedding, 1e-4f)
        }
    }

    @Test fun searchRanksRelevantSourceFirst() = runTest {
        val index = build()
        val q = HashingEmbeddingClient(64).embed(listOf("how many hours of sleep to recover"), EmbeddingTaskType.RETRIEVAL_QUERY)[0]
        assertEquals("sleep.md", index.search(q, 1).single().chunk.source)
    }

    @Test fun comparisonReportMentionsBothStrategies() = runTest {
        val embedder = HashingEmbeddingClient(64)
        val f = build(FixedSizeChunker(200, 20))
        val s = build()
        val queries = listOf(ComparisonQuery("sleep hours recover", "sleep.md"))
        val md = Comparison.renderMarkdown(f, s, Comparison.runQueries(f, embedder, queries), Comparison.runQueries(s, embedder, queries))
        assertTrue(md.contains("Chunk statistics") && md.contains("fixed") && md.contains("structure"))
        assertTrue(md.contains("hashing"))
    }

    @Test fun corpusLoaderUsesFirstH1AsTitle() {
        tmp.newFile("b.md").writeText("# Nice Title\n\nbody")
        tmp.newFile("a.md").writeText("no heading")
        val loaded = CorpusLoader.load(tmp.root)
        assertEquals(listOf("a.md", "b.md"), loaded.map { it.source })
        assertEquals(listOf("a", "Nice Title"), loaded.map { it.title })
    }
}
