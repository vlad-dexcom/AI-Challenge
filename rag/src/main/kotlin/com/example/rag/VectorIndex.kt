package com.example.rag

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Header describing how an index was built. */
@Serializable
data class IndexMeta(
    val embeddingModel: String,
    val dimension: Int,
    val strategy: ChunkStrategy,
    /** Chunker parameters, e.g. `size=800, overlap=100`. */
    val strategyParams: String,
    val createdAt: String,
    val sourceCorpus: String,
    val documentCount: Int,
    val chunkCount: Int,
)

class IndexedChunk(val chunk: Chunk, val embedding: FloatArray)

data class SearchHit(val chunk: Chunk, val score: Float)

/** In-memory index: chunks + vectors. Search is brute-force cosine similarity (enough for a few hundred chunks). */
class VectorIndex(val meta: IndexMeta, val entries: List<IndexedChunk>) {
    val chunks: List<Chunk> get() = entries.map { it.chunk }

    /** Top-[k] chunks by cosine similarity to [query] (does not need to be normalised). */
    fun search(query: FloatArray, k: Int = 5): List<SearchHit> {
        require(query.size == meta.dimension) { "Query has ${query.size} dims, index has ${meta.dimension}" }
        return entries.map { SearchHit(it.chunk, cosine(query, it.embedding)) }
            .sortedByDescending { it.score }
            .take(k)
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }
    }
}
