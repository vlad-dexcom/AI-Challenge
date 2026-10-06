package com.example.rag.chat

import com.example.rag.Chunk
import com.example.rag.ChunkStrategy
import com.example.core.llm.EmbeddingTaskType
import com.example.core.llm.HashingEmbeddingClient
import com.example.rag.IndexMeta
import com.example.rag.IndexedChunk
import com.example.core.llm.TextGenerator
import com.example.rag.VectorIndex
import com.example.rag.VectorRetriever
import com.example.rag.LlmQueryRewriter

const val PROTEIN_TEXT = "Protein intake of 1.6 grams per kilogram of bodyweight daily supports muscle growth."

suspend fun fixtureIndex(): VectorIndex {
    val embedder = HashingEmbeddingClient(64)
    val chunks = listOf(
        Chunk("protein.md#1", "protein.md", "Protein", "Protein", PROTEIN_TEXT, 0, PROTEIN_TEXT.length, ChunkStrategy.STRUCTURE),
        Chunk("squat.md#1", "squat.md", "Squat", "Squat", "Goblet squats with dumbbells are knee friendly.", 0, 49, ChunkStrategy.STRUCTURE),
    )
    val vecs = embedder.embed(chunks.map { it.text }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
    val meta = IndexMeta(embedder.modelName, 64, ChunkStrategy.STRUCTURE, "p", "t", "c", 2, 2)
    return VectorIndex(meta, chunks.mapIndexed { i, c -> IndexedChunk(c, vecs[i]) })
}

suspend fun fixtureRetriever() = VectorRetriever(HashingEmbeddingClient(64), fixtureIndex(), 3)

/** Scripted model: routes by system prompt and records every prompt. */
class FakeLlm(
    var extractor: (String) -> Result<String> = { Result.success("{}") },
    var rewrite: (String) -> Result<String> = { Result.success("protein intake muscle growth") },
    var answer: (String) -> Result<String> = { Result.success(ANSWER) },
) : TextGenerator {
    val extractorPrompts = mutableListOf<String>()
    val rewritePrompts = mutableListOf<String>()
    val answerPrompts = mutableListOf<String>()
    val answerSystems = mutableListOf<String>()
    var summaries = 0

    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> = when {
        systemInstruction == MemoryExtractor.SYSTEM -> { extractorPrompts += prompt; extractor(prompt) }
        systemInstruction == ContextualRewriter.SYSTEM || systemInstruction == LlmQueryRewriter.SYSTEM -> { rewritePrompts += prompt; rewrite(prompt) }
        systemInstruction == Summarizer.SYSTEM -> { summaries++; Result.success("summary text") }
        else -> { answerPrompts += prompt; answerSystems += systemInstruction.orEmpty(); answer(prompt) }
    }

    companion object {
        const val ANSWER = """{"answerable":true,"answer":"Eat 1.6 g/kg [1].","sources":[{"file":"protein.md","section":"Protein","chunkId":"protein.md#1"}],"quotes":[{"chunkId":"protein.md#1","text":"Protein intake of 1.6 grams per kilogram of bodyweight daily"}]}"""
        const val IDK = """{"answerable":false,"answer":"","sources":[],"quotes":[]}"""
    }
}
