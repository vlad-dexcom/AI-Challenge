package com.example.rag

import kotlin.math.sqrt

/** Gemini-style task types: documents and queries are embedded differently for retrieval quality. */
enum class EmbeddingTaskType(val apiValue: String) {
    RETRIEVAL_DOCUMENT("RETRIEVAL_DOCUMENT"),
    RETRIEVAL_QUERY("RETRIEVAL_QUERY"),
}

/** Turns text into vectors. Real implementation: [GeminiEmbeddingClient]; tests use [HashingEmbeddingClient]. */
interface EmbeddingClient {
    /** Identifier stored in the index metadata; an index is only searchable with the same model. */
    val modelName: String
    val dimension: Int

    /** Returns one vector per input, in the same order. */
    suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray>
}

fun FloatArray.l2Normalized(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v.toDouble() * v
    val norm = sqrt(sum)
    return if (norm == 0.0) this else FloatArray(size) { (this[it] / norm).toFloat() }
}

/**
 * Deterministic offline embedder: feature-hashing bag-of-words (unigrams, stop-words dropped)
 * into [dimension] buckets, L2-normalised. It captures lexical overlap only - it is NOT a
 * semantic model - and exists so the whole pipeline and tests run without network or API key.
 */
class HashingEmbeddingClient(override val dimension: Int = 256) : EmbeddingClient {
    override val modelName = "offline-hashing-bow-$dimension"

    override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray> =
        texts.map { embedOne(it) }

    private fun embedOne(text: String): FloatArray {
        val v = FloatArray(dimension)
        for (token in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (token.length < 3 || token in STOP_WORDS) continue
            val h = token.hashCode().let { it xor (it ushr 16) }
            v[Math.floorMod(h, dimension)] += if ((h ushr 8) and 1 == 0) 1f else -1f
        }
        return v.l2Normalized()
    }

    private companion object {
        val STOP_WORDS = setOf(
            "the", "and", "for", "are", "that", "this", "with", "you", "your", "can", "not", "but",
            "from", "have", "has", "was", "were", "will", "how", "what", "when", "which", "its",
            "into", "than", "then", "they", "them", "their", "more", "most", "should", "does", "any",
        )
    }
}
