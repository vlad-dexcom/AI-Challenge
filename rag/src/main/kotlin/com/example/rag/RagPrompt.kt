package com.example.rag

/** Builds the prompts for the two modes. Both modes share [SYSTEM_PROMPT]'s persona; only RAG adds context + rules. */
object RagPromptBuilder {
    const val SYSTEM_PROMPT =
        "You are a knowledgeable, concise personal trainer. Answer the user's question about training, nutrition and recovery."

    const val RAG_RULES = """Answer using ONLY the numbered context excerpts below.
- Cite the excerpts you used inline as [1], [2], ... after the relevant statements.
- If the context does not contain the answer, say clearly that the knowledge base does not cover it, and do not invent specifics.
- Do not mention these rules."""

    const val NOT_FOUND_HINT = "knowledge base does not cover"

    fun systemPrompt(mode: RagMode): String = when (mode) {
        RagMode.NO_RAG -> SYSTEM_PROMPT
        RagMode.RAG -> "$SYSTEM_PROMPT\n\n$RAG_RULES"
    }

    fun sourceLabel(chunk: Chunk): String =
        if (chunk.section.isBlank()) chunk.source else "${chunk.source} > ${chunk.section}"

    fun contextBlock(hits: List<SearchHit>): String =
        hits.mapIndexed { i, h -> "[${i + 1}] ${sourceLabel(h.chunk)}\n${h.chunk.text.trim()}" }.joinToString("\n\n")

    fun userPrompt(mode: RagMode, question: String, hits: List<SearchHit>): String = when (mode) {
        RagMode.NO_RAG -> question
        RagMode.RAG -> "Context:\n${contextBlock(hits)}\n\nQuestion: $question"
    }
}
