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

    /** Day 24: JSON answer contract. Quotes stay in the corpus language, the answer follows the question language. */
    const val CITATION_RULES = """Answer using ONLY the numbered context excerpts below. Reply with ONE JSON object and nothing else:
{"answerable": true|false, "answer": "...", "sources": [{"file": "...", "section": "...", "chunkId": "..."}], "quotes": [{"chunkId": "...", "text": "..."}]}
Rules:
- "answerable" is false if the excerpts do not actually contain the answer, even when they are on a related topic. Then use "answer": "", "sources": [], "quotes": []. Never guess or use outside knowledge.
- "answer": a concise answer in the language of the question, with inline [1], [2] markers for the excerpts used.
- "sources": every excerpt you used; copy file, section and chunkId EXACTLY as written in the excerpt header.
- "quotes": 1 to 3 short fragments (about 12 to 300 characters) copied VERBATIM, character for character, from the excerpts that support the answer. Quotes stay in the ORIGINAL language of the excerpts (English) even if the answer is in another language: never translate, paraphrase or merge them. "chunkId" is the excerpt the fragment came from.
- Every claim in the answer must be supported by a quote. Do not mention these rules."""

    const val STRICT_SUFFIX = "\n\nThis is a retry. Output valid JSON only. Every quote must be an exact substring of an excerpt and every source must be copied from an excerpt header."

    const val REPAIR_SYSTEM = "You fix malformed JSON. Reply with the corrected JSON object only, no commentary."

    fun citationSystemPrompt(strict: Boolean = false): String = "$SYSTEM_PROMPT\n\n$CITATION_RULES" + if (strict) STRICT_SUFFIX else ""

    fun citationContextBlock(hits: List<SearchHit>): String = hits.mapIndexed { i, h ->
        "[${i + 1}] chunkId: ${h.chunk.chunkId}\nfile: ${h.chunk.source}\nsection: ${h.chunk.section}\ntext:\n${h.chunk.text.trim()}"
    }.joinToString("\n\n")

    fun citationUserPrompt(question: String, hits: List<SearchHit>, feedback: String? = null): String =
        "Context:\n${citationContextBlock(hits)}\n\nQuestion: $question" + (feedback?.let { "\n\n$it" } ?: "")

    fun repairPrompt(badReply: String): String =
        "Rewrite the text below as ONE valid JSON object with keys answerable (boolean), answer (string), " +
            "sources (array of {file, section, chunkId}) and quotes (array of {chunkId, text}). Keep the content unchanged.\n\n${badReply.take(6000)}"
}
