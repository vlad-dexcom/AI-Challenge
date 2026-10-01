package com.example.rag

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

@Serializable
data class Check(val name: String, val ok: Boolean, val detail: String)

@Serializable
data class SampleChunk(val chunkId: String, val section: String, val preview: String, val vectorHead: List<Float>)

@Serializable
data class VectorStats(val zeroVectors: Int, val nonFinite: Int, val wrongLength: Int, val minNorm: Double, val avgNorm: Double, val maxNorm: Double)

@Serializable
data class InspectReport(
    val strategy: String,
    val file: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val meta: IndexMeta?,
    val chunkCount: Int,
    val vectorStats: VectorStats?,
    val checks: List<Check>,
    val samples: List<SampleChunk>,
) {
    val ok: Boolean get() = checks.all { it.ok }
}

/** Sanity checks for a saved index: metadata completeness, vector health, and staleness vs the corpus. */
object IndexInspector {
    fun inspect(file: File, corpus: List<Document>?, load: (File) -> VectorIndex = { IndexStore().load(it) }): InspectReport {
        val strategy = file.nameWithoutExtension
        val modified = java.time.Instant.ofEpochMilli(file.lastModified()).toString()
        val index = try {
            load(file)
        } catch (e: Exception) {
            return InspectReport(strategy, file.path, file.length(), modified, null, 0, null,
                listOf(Check("Index file parses", false, e.message?.take(200) ?: e.javaClass.simpleName)), emptyList())
        }
        return inspect(index, file.path, file.length(), modified, corpus).copy(strategy = strategy)
    }

    fun inspect(index: VectorIndex, path: String, sizeBytes: Long, modifiedAt: String, corpus: List<Document>?): InspectReport {
        val meta = index.meta
        val entries = index.entries
        val checks = mutableListOf<Check>()
        checks += Check("Index file parses", true, "${entries.size} chunks")
        checks += Check("Chunk count matches meta", entries.size == meta.chunkCount, "chunks=${entries.size}, meta.chunkCount=${meta.chunkCount}")

        val missing = entries.flatMap { e ->
            val c = e.chunk
            listOfNotNull(
                "chunk_id".takeIf { c.chunkId.isBlank() }, "source".takeIf { c.source.isBlank() },
                "title".takeIf { c.title.isBlank() }, "section".takeIf { c.section.isBlank() },
                "offsets".takeIf { c.startOffset < 0 || c.endOffset <= c.startOffset },
                "text".takeIf { c.text.isBlank() },
            ).map { it to c.chunkId }
        }
        val byField = missing.groupBy({ it.first }, { it.second })
        // An empty section is legitimate for text before the first heading, so it is reported but only warns via detail.
        val hard = byField.filterKeys { it != "section" }
        checks += Check(
            "Chunk metadata present (chunk_id, source, title, offsets, text)", hard.isEmpty(),
            if (hard.isEmpty()) "all present" else hard.entries.joinToString("; ") { "${it.key}: ${it.value.size} chunks (e.g. ${it.value.first().ifBlank { "<blank id>" }})" },
        )
        val emptySections = byField["section"]?.size ?: 0
        checks += Check("Chunks with a section path", emptySections == 0,
            if (emptySections == 0) "all chunks have a section" else "$emptySections chunks without section (text before the first heading, or headingless file)")

        val dups = entries.size - entries.map { it.chunk.chunkId }.toSet().size
        checks += Check("Unique chunk_id", dups == 0, "$dups duplicates")

        val wrong = entries.count { it.embedding.size != meta.dimension }
        checks += Check("Vector length == meta.dimension (${meta.dimension})", wrong == 0, "$wrong vectors with a different length")
        val nonFinite = entries.count { e -> e.embedding.any { !it.isFinite() } }
        checks += Check("No NaN / Infinity", nonFinite == 0, "$nonFinite vectors contain NaN/Infinity")

        val norms = entries.filter { e -> e.embedding.all { it.isFinite() } }.map { e ->
            sqrt(e.embedding.sumOf { it.toDouble() * it })
        }
        val zero = norms.count { it < 1e-9 }
        checks += Check("No zero vectors", zero == 0, "$zero zero vectors" + if (zero > 0 && meta.embeddingModel.startsWith("offline-")) " (chunks with no non-stop-word tokens embed to zero with the offline embedder)" else "")
        val stats = VectorStats(zero, nonFinite, wrong,
            norms.minOrNull() ?: 0.0, if (norms.isEmpty()) 0.0 else norms.average(), norms.maxOrNull() ?: 0.0)
        val normalized = norms.isNotEmpty() && norms.filter { it >= 1e-9 }.all { abs(it - 1.0) < 1e-3 }
        checks += Check("Vectors L2-normalised", normalized, "norm min/avg/max = %.4f / %.4f / %.4f".format(stats.minNorm, stats.avgNorm, stats.maxNorm))

        checks += if (corpus == null) {
            Check("Index is up to date with the corpus", false, "Corpus directory '${meta.sourceCorpus}' not found; cannot re-chunk")
        } else staleness(index, corpus)

        val samples = entries.take(3).map {
            SampleChunk(it.chunk.chunkId, it.chunk.section, it.chunk.text.replace(Regex("\\s+"), " ").take(100), it.embedding.take(5))
        }
        return InspectReport(meta.strategy.id, path, sizeBytes, modifiedAt, meta, entries.size, stats, checks, samples)
    }

    private fun staleness(index: VectorIndex, corpus: List<Document>): Check {
        val chunker = chunkerFor(index.meta) ?: return Check("Index is up to date with the corpus", false, "Cannot parse strategyParams '${index.meta.strategyParams}'")
        val fresh = corpus.flatMap { chunker.chunk(it) }
        val countOk = fresh.size == index.entries.size
        val hashOk = digest(fresh) == digest(index.chunks)
        val docsOk = corpus.size == index.meta.documentCount
        return Check(
            "Index is up to date with the corpus (fresh re-chunk)", countOk && hashOk && docsOk,
            "chunks: index=${index.entries.size} vs fresh=${fresh.size}; documents: meta=${index.meta.documentCount} vs corpus=${corpus.size}; " +
                "text hash ${if (hashOk) "matches" else "DIFFERS - re-run the index command"}",
        )
    }

    private fun digest(chunks: List<Chunk>): String {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { md.update("${it.chunkId}|${it.startOffset}|${it.endOffset}|".toByteArray()); md.update(it.text.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Rebuilds the chunker from `IndexMeta.strategyParams` (e.g. "size=800, overlap=100"); null if unparseable. */
    fun chunkerFor(meta: IndexMeta): Chunker? {
        val p = Regex("(\\w+)=(\\d+)").findAll(meta.strategyParams).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        return try {
            when (meta.strategy) {
                ChunkStrategy.FIXED -> FixedSizeChunker(p["size"] ?: return null, p["overlap"] ?: return null)
                ChunkStrategy.STRUCTURE -> StructureChunker(p["max"] ?: return null, p["min"] ?: return null)
            }
        } catch (e: IllegalArgumentException) { null }
    }
}

@Serializable
data class PingResponse(
    val embeddingModel: String,
    val lexicalOnly: Boolean,
    val vectorLength: Int,
    val norm: Double,
    val latencyMs: Long,
    val relatedSimilarity: Double,
    val unrelatedSimilarity: Double,
    val sentences: List<String>,
    val verdict: String,
)

object EmbedderPing {
    val SENTENCES = listOf(
        "How much protein should I eat to build muscle?",
        "Daily protein intake for muscle growth",
        "The train to the airport leaves at noon",
    )

    suspend fun run(embedder: EmbeddingClient, clock: () -> Long = System::nanoTime): PingResponse {
        val t0 = clock()
        val v = embedder.embed(SENTENCES, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        val ms = (clock() - t0) / 1_000_000
        val related = VectorIndex.cosine(v[0], v[1]).toDouble()
        val unrelated = VectorIndex.cosine(v[0], v[2]).toDouble()
        val lexical = embedder.modelName.startsWith("offline-")
        val verdict = when {
            lexical -> "Offline embedder is lexical-only (word overlap): it says nothing about semantic quality."
            related > unrelated -> "PASS: related pair is more similar than the unrelated pair."
            else -> "FAIL: related pair is not more similar than the unrelated pair."
        }
        return PingResponse(embedder.modelName, lexical, v[0].size, sqrt(v[0].sumOf { it.toDouble() * it }), ms, related, unrelated, SENTENCES, verdict)
    }
}
