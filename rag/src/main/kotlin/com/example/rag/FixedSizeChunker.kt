package com.example.rag

/**
 * Strategy 1: a sliding window of [size] characters moving forward by `size - overlap`.
 * Ignores document structure entirely (cuts may land mid-word), which is exactly what makes
 * it a useful baseline. The `section` metadata is still filled in (the heading in effect at
 * the chunk start) so both strategies expose the same metadata.
 */
class FixedSizeChunker(
    private val size: Int = 800,
    private val overlap: Int = 100,
) : Chunker {
    init {
        require(size > 0) { "size must be positive" }
        require(overlap in 0 until size) { "overlap must be in [0, size)" }
    }

    override val strategy = ChunkStrategy.FIXED

    override fun chunk(document: Document): List<Chunk> {
        val text = document.text
        val headings = findHeadings(text)
        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            val slice = text.substring(start, end)
            if (slice.isNotBlank()) {
                chunks += Chunk(
                    chunkId = "${document.source}#fixed-${chunks.size}",
                    source = document.source,
                    title = document.title,
                    section = sectionPathAt(headings, start),
                    text = slice,
                    startOffset = start,
                    endOffset = end,
                    strategy = strategy,
                )
            }
            if (end == text.length) break
            start = end - overlap
        }
        return chunks
    }
}
