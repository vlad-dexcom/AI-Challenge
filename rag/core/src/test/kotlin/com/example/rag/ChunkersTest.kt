package com.example.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkersTest {
    private val doc = Document(
        "a.md", "Doc",
        "# Doc\n\nIntro text.\n\n## Alpha\n\n" + "Alpha sentence one. ".repeat(40) +
            "\n\n## Tiny\n\nShort.\n\n## Beta\n\n### Beta Sub\n\n" + "Beta sentence here. ".repeat(120) + "\n",
    )

    @Test fun fixedChunksOverlapAndCoverDocument() {
        val chunks = FixedSizeChunker(200, 50).chunk(doc)
        assertEquals(0, chunks.first().startOffset)
        assertEquals(doc.text.length, chunks.last().endOffset)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endOffset - 50, b.startOffset) }
        chunks.forEach { assertEquals(doc.text.substring(it.startOffset, it.endOffset), it.text) }
        assertEquals(chunks.map { it.chunkId }.distinct().size, chunks.size)
    }

    @Test fun fixedChunkCarriesSectionMetadata() {
        val c = FixedSizeChunker(200, 50).chunk(doc).first { it.startOffset > doc.text.indexOf("## Alpha") }
        assertEquals("Doc > Alpha", c.section)
        assertEquals("a.md", c.source)
        assertEquals("Doc", c.title)
    }

    @Test fun structureChunksFollowHeadingsAndStayWithinMax() {
        val chunks = StructureChunker(maxChars = 600, minChars = 100).chunk(doc)
        chunks.forEach {
            assertTrue("too big: ${it.text.length}", it.text.length <= 600)
            assertEquals(doc.text.substring(it.startOffset, it.endOffset), it.text)
        }
        // The tiny section is merged away rather than being its own chunk.
        assertTrue(chunks.none { it.text.trim().startsWith("## Tiny") })
        assertTrue(chunks.any { it.section == "Doc > Beta > Beta Sub" })
        // Contiguous, no text lost (whitespace-only gaps aside).
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endOffset, b.startOffset) }
    }

    @Test fun structureChunksStartAtHeadingsWhenSectionsAreBigEnough() {
        val chunks = StructureChunker(maxChars = 5000, minChars = 50).chunk(doc)
        assertTrue(chunks.drop(1).all { it.text.startsWith("##") })
    }

    @Test fun headingsInsideCodeFencesAreIgnored() {
        val d = Document("c.md", "C", "# C\n\n```\n# not a heading\n```\n\n## Real\n\ntext\n")
        assertEquals(listOf("C", "Real"), findHeadings(d.text).map { it.text })
    }

}
