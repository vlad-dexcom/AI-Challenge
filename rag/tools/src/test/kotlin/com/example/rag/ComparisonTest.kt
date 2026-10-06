package com.example.rag

import com.example.core.llm.HashingEmbeddingClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class ComparisonTest {
    private val doc = Document(
        "a.md", "Doc",
        "# Doc\n\nIntro text.\n\n## Alpha\n\n" + "Alpha sentence one. ".repeat(40) +
            "\n\n## Tiny\n\nShort.\n\n## Beta\n\n### Beta Sub\n\n" + "Beta sentence here. ".repeat(120) + "\n",
    )

    private val docs = listOf(
        Document("squat.md", "Squat", "# Squat\n\n## Bracing\n\nBrace your core and keep the barbell over mid-foot while squatting deep. ".repeat(5)),
        Document("sleep.md", "Sleep", "# Sleep\n\n## Hygiene\n\nSleep eight hours in a dark cool bedroom to recover from training. ".repeat(5)),
    )

    private suspend fun build(strategy: Chunker = StructureChunker(400, 50)) =
        Indexer(HashingEmbeddingClient(64)) { Instant.parse("2026-01-01T00:00:00Z") }
            .build(docs, strategy, "test", "mem")

    @Test fun statsDetectMidSentenceAndMergedSections() {
        val fixed = ChunkStats.of(FixedSizeChunker(200, 50).chunk(doc))
        val structure = ChunkStats.of(StructureChunker(600, 100).chunk(doc))
        assertTrue(fixed.endsMidSentencePct > structure.endsMidSentencePct)
        assertTrue(fixed.startsMidSentencePct > structure.startsMidSentencePct)
        // Merging tiny sections is the only way a structure chunk can contain a second heading.
        assertTrue(structure.crossesSectionPct > 0)
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
}
