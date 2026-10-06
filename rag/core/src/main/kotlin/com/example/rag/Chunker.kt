package com.example.rag

/** Splits a [Document] into [Chunk]s. Implementations must be deterministic. */
interface Chunker {
    val strategy: ChunkStrategy
    fun chunk(document: Document): List<Chunk>
}

val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")

/** A heading line located in a document (code fences are skipped so `# comment` is not a heading). */
data class Heading(val level: Int, val text: String, val start: Int)

fun findHeadings(text: String): List<Heading> {
    val result = mutableListOf<Heading>()
    var inFence = false
    var pos = 0
    for (line in text.split("\n")) {
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (!inFence) {
            HEADING_REGEX.matchEntire(line.trimEnd('\r'))?.let {
                result += Heading(it.groupValues[1].length, it.groupValues[2], pos)
            }
        }
        pos += line.length + 1
    }
    return result
}

/** Heading path (`H1 > H2 > H3`) in effect at [offset]. */
fun sectionPathAt(headings: List<Heading>, offset: Int): String {
    val stack = ArrayDeque<Heading>()
    for (h in headings) {
        if (h.start > offset) break
        while (stack.isNotEmpty() && stack.last().level >= h.level) stack.removeLast()
        stack.addLast(h)
    }
    return stack.joinToString(" > ") { it.text }
}
