package com.example.rag

import kotlinx.serialization.Serializable

/** A source document to be indexed. [source] is a stable relative path (e.g. `01-squat-technique.md`). */
data class Document(val source: String, val title: String, val text: String)

/** Which chunking strategy produced a chunk. [id] is also used in index file names. */
@Serializable
enum class ChunkStrategy(val id: String) {
    FIXED("fixed"),
    STRUCTURE("structure"),
}

/**
 * One indexed piece of text plus the metadata needed to cite it later (Day 22+).
 * [startOffset]/[endOffset] are character offsets into the source document, so
 * `document.text.substring(startOffset, endOffset) == text`.
 */
@Serializable
data class Chunk(
    val chunkId: String,
    val source: String,
    val title: String,
    /** Heading path such as `Squat > Bracing > Common faults`; empty before the first heading. */
    val section: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val strategy: ChunkStrategy,
)
