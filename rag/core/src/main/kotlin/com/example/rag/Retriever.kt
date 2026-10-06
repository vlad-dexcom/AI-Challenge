package com.example.rag

import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingTaskType

/**
 * Finds the chunks relevant to a question. An interface so Day 23 can wrap it with
 * thresholding / reranking without touching [RagPipeline].
 */
fun interface Retriever {
    suspend fun retrieve(question: String): List<SearchHit>

    /** Candidate pool of up to [topK] chunks for the two-stage pipeline; the default just truncates [retrieve]. */
    suspend fun retrieve(question: String, topK: Int): List<SearchHit> = retrieve(question).take(topK)
}

/** Embeds the question as a RETRIEVAL_QUERY and returns the [topK] most similar chunks of [index]. */
class VectorRetriever(
    private val embedder: EmbeddingClient,
    private val index: VectorIndex,
    private val topK: Int = DEFAULT_TOP_K,
) : Retriever {
    companion object {
        const val DEFAULT_TOP_K = 4
    }

    init {
        require(embedder.modelName == index.meta.embeddingModel) {
            "Index was built with ${index.meta.embeddingModel}, but embedder is ${embedder.modelName}"
        }
    }

    override suspend fun retrieve(question: String): List<SearchHit> = retrieve(question, topK)

    override suspend fun retrieve(question: String, topK: Int): List<SearchHit> {
        val vector = embedder.embed(listOf(question), EmbeddingTaskType.RETRIEVAL_QUERY).single()
        return index.search(vector, topK)
    }
}
