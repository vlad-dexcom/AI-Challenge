package com.example.rag

/** Size and boundary-quality statistics for a set of chunks (computed from an index, no re-chunking). */
data class ChunkStats(
    val count: Int,
    val avgChars: Int,
    val minChars: Int,
    val maxChars: Int,
    /** % of chunks whose text does not end at a sentence/paragraph end (cut mid-sentence or mid-word). */
    val endsMidSentencePct: Double,
    /** % of chunks whose first character is lowercase, i.e. they start in the middle of a sentence. */
    val startsMidSentencePct: Double,
    /** % of chunks that contain a heading line after their first line, i.e. they straddle two sections. */
    val crossesSectionPct: Double,
) {
    companion object {
        fun of(chunks: List<Chunk>): ChunkStats {
            require(chunks.isNotEmpty()) { "no chunks" }
            val sizes = chunks.map { it.text.length }
            fun pct(n: Int) = 100.0 * n / chunks.size
            return ChunkStats(
                count = chunks.size,
                avgChars = sizes.average().toInt(),
                minChars = sizes.min(),
                maxChars = sizes.max(),
                endsMidSentencePct = pct(chunks.count { endsMidSentence(it.text) }),
                startsMidSentencePct = pct(chunks.count { it.text.trimStart().firstOrNull()?.isLowerCase() == true }),
                crossesSectionPct = pct(chunks.count { crossesSection(it.text) }),
            )
        }

        internal fun endsMidSentence(text: String): Boolean {
            val last = text.trimEnd().lastOrNull() ?: return false
            return last !in ".!?:)\"'`*|"
        }

        internal fun crossesSection(text: String): Boolean {
            val lines = text.trimStart().split("\n")
            return findHeadings(lines.drop(1).joinToString("\n")).isNotEmpty()
        }
    }
}

/** A comparison query with the article that should answer it. */
data class ComparisonQuery(val text: String, val expectedSource: String)

data class QueryResult(
    val query: ComparisonQuery,
    /** Rank (1-based) of the first chunk from the expected source, or null if not in top-k. */
    val firstRelevantRank: Int?,
    val top: List<SearchHit>,
)

object Comparison {
    val DEFAULT_QUERIES = listOf(
        ComparisonQuery("How deep should I squat and how do I brace my core?", "01-squat-technique.md"),
        ComparisonQuery("What is the correct hip hinge setup for a deadlift?", "02-deadlift-technique.md"),
        ComparisonQuery("How should I position my shoulder blades during the bench press?", "03-bench-press-and-pushing.md"),
        ComparisonQuery("How many hours of sleep do I need to recover from training?", "07-sleep-and-recovery.md"),
        ComparisonQuery("How much protein should I eat per day to build muscle?", "08-nutrition-protein-and-calories.md"),
        ComparisonQuery("What is a good warm-up before lifting heavy?", "10-warm-up-and-mobility.md"),
        ComparisonQuery("Is creatine safe and how much should I take?", "17-supplements-and-ergogenic-aids.md"),
        ComparisonQuery("How do I know if pain is an injury or normal soreness?", "11-injury-prevention-and-rehab.md"),
    )

    suspend fun runQueries(index: VectorIndex, embedder: EmbeddingClient, queries: List<ComparisonQuery>, k: Int = 3): List<QueryResult> {
        val vectors = embedder.embed(queries.map { it.text }, EmbeddingTaskType.RETRIEVAL_QUERY)
        return queries.zip(vectors) { q, v ->
            val hits = index.search(v, k)
            QueryResult(q, hits.indexOfFirst { it.chunk.source == q.expectedSource }.takeIf { it >= 0 }?.plus(1), hits)
        }
    }

    fun renderMarkdown(
        fixed: VectorIndex,
        structure: VectorIndex,
        fixedResults: List<QueryResult>,
        structureResults: List<QueryResult>,
    ): String = buildString {
        val fs = ChunkStats.of(fixed.chunks)
        val ss = ChunkStats.of(structure.chunks)
        appendLine("# Chunking strategy comparison")
        appendLine()
        appendLine("Embedding model: `${fixed.meta.embeddingModel}` (${fixed.meta.dimension} dims). Corpus: `${fixed.meta.sourceCorpus}` (${fixed.meta.documentCount} documents).")
        if (fixed.meta.embeddingModel.startsWith("offline-")) {
            appendLine()
            appendLine("> The embeddings are from the offline **hashing** fallback (lexical overlap only), not a real semantic model - retrieval numbers below are illustrative only.")
        }
        appendLine()
        appendLine("## Chunk statistics")
        appendLine()
        appendLine("| Metric | fixed (${fixed.meta.strategyParams}) | structure (${structure.meta.strategyParams}) |")
        appendLine("|---|---|---|")
        appendLine("| Chunks | ${fs.count} | ${ss.count} |")
        appendLine("| Avg size (chars) | ${fs.avgChars} | ${ss.avgChars} |")
        appendLine("| Min size | ${fs.minChars} | ${ss.minChars} |")
        appendLine("| Max size | ${fs.maxChars} | ${ss.maxChars} |")
        appendLine("| Ends mid-sentence | ${"%.1f".format(fs.endsMidSentencePct)}% | ${"%.1f".format(ss.endsMidSentencePct)}% |")
        appendLine("| Starts mid-sentence | ${"%.1f".format(fs.startsMidSentencePct)}% | ${"%.1f".format(ss.startsMidSentencePct)}% |")
        appendLine("| Straddles two sections | ${"%.1f".format(fs.crossesSectionPct)}% | ${"%.1f".format(ss.crossesSectionPct)}% |")
        appendLine()
        appendLine("## Sample queries (top-3 by cosine similarity)")
        appendLine()
        appendLine("| # | Query | Expected source | fixed: first relevant rank | structure: first relevant rank |")
        appendLine("|---|---|---|---|---|")
        fixedResults.indices.forEach { i ->
            val q = fixedResults[i].query
            appendLine("| ${i + 1} | ${q.text} | `${q.expectedSource}` | ${fixedResults[i].firstRelevantRank ?: "-"} | ${structureResults[i].firstRelevantRank ?: "-"} |")
        }
        val fHit = fixedResults.count { it.firstRelevantRank == 1 }
        val sHit = structureResults.count { it.firstRelevantRank == 1 }
        appendLine()
        appendLine("Top-1 hits on expected source: fixed **$fHit/${fixedResults.size}**, structure **$sHit/${structureResults.size}**.")
        appendLine()
        appendLine("### Top-3 chunks per query")
        fixedResults.indices.forEach { i ->
            appendLine()
            appendLine("**Q${i + 1}: ${fixedResults[i].query.text}**")
            appendLine()
            for ((name, res) in listOf("fixed" to fixedResults[i], "structure" to structureResults[i])) {
                appendLine("- $name")
                res.top.forEach { h ->
                    appendLine("  - %.3f `%s` — %s".format(h.score, h.chunk.chunkId, h.chunk.section.ifEmpty { "(no section)" }))
                }
            }
        }
    }
}
