package com.example.rag

/**
 * Strategy 2: split along markdown headings.
 *  - each heading starts a new section (heading line included in its text);
 *  - a section shorter than [minChars] is merged into the preceding chunk (or, for the first
 *    section, into the following one) as long as the result stays <= [maxChars];
 *  - a section longer than [maxChars] is split on paragraph boundaries (blank lines), and an
 *    oversized single paragraph is split on sentence boundaries.
 * Chunks are always contiguous slices of the source, so offsets stay exact.
 */
class StructureChunker(
    private val maxChars: Int = 1500,
    private val minChars: Int = 300,
) : Chunker {
    init {
        require(minChars in 0 until maxChars) { "minChars must be < maxChars" }
    }

    override val strategy = ChunkStrategy.STRUCTURE

    private data class Range(val start: Int, val end: Int, val section: String)

    override fun chunk(document: Document): List<Chunk> {
        val text = document.text
        val headings = findHeadings(text)
        val boundaries = (listOf(0) + headings.map { it.start } + text.length).distinct().sorted()

        val pieces = mutableListOf<Range>()
        for (i in 0 until boundaries.size - 1) {
            val s = boundaries[i]
            val e = boundaries[i + 1]
            if (text.substring(s, e).isBlank()) continue
            val path = sectionPathAt(headings, s)
            if (e - s <= maxChars) pieces += Range(s, e, path) else pieces += splitLarge(text, s, e, path)
        }

        val merged = mutableListOf<Range>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            if (last != null && last.end == p.start &&
                ((last.end - last.start) < minChars || (p.end - p.start) < minChars) &&
                p.end - last.start <= maxChars
            ) {
                merged[merged.size - 1] = Range(last.start, p.end, last.section)
            } else {
                merged += p
            }
        }

        return merged.mapIndexed { i, r ->
            Chunk(
                chunkId = "${document.source}#structure-$i",
                source = document.source,
                title = document.title,
                section = r.section,
                text = text.substring(r.start, r.end),
                startOffset = r.start,
                endOffset = r.end,
                strategy = strategy,
            )
        }
    }

    /** Greedy packing of paragraphs (then sentences) into <= maxChars pieces within [s, e). */
    private fun splitLarge(text: String, s: Int, e: Int, path: String): List<Range> {
        val units = mutableListOf<IntRange>()
        for (para in splitAfter(text, s, e, Regex("\\n\\s*\\n"))) {
            if (para.last - para.first + 1 <= maxChars) units += para
            else units += splitAfter(text, para.first, para.last + 1, Regex("(?<=[.!?])\\s+"))
                .flatMap { hardWrap(it) }
        }
        val out = mutableListOf<Range>()
        var curStart = -1
        var curEnd = -1
        for (u in units) {
            if (curStart < 0) { curStart = u.first; curEnd = u.last + 1; continue }
            if (u.last + 1 - curStart <= maxChars) curEnd = u.last + 1
            else { out += Range(curStart, curEnd, path); curStart = u.first; curEnd = u.last + 1 }
        }
        if (curStart >= 0) out += Range(curStart, curEnd, path)
        return out.filter { text.substring(it.start, it.end).isNotBlank() }
    }

    /** Splits [s, e) after each separator match so the pieces tile the range with no gaps. */
    private fun splitAfter(text: String, s: Int, e: Int, sep: Regex): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var from = s
        for (m in sep.findAll(text.substring(s, e))) {
            val cut = s + m.range.last + 1
            if (cut > from && cut < e) { result += from until cut; from = cut }
        }
        if (from < e) result += from until e
        return result
    }

    private fun hardWrap(r: IntRange): List<IntRange> {
        if (r.last - r.first + 1 <= maxChars) return listOf(r)
        return r.first.until(r.last + 1).step(maxChars).map { it..minOf(it + maxChars - 1, r.last) }
    }
}
