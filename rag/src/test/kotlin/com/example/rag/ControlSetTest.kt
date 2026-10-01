package com.example.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ControlSetTest {
    // Gradle runs :rag tests with the module dir as cwd; the CLI uses repo-root-relative paths.
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "rag/corpus").isDirectory }
    private val questions = ControlSet.load(File(root, "rag/eval/control-questions.json"))

    @Test fun committedControlSetIsValidAgainstCorpus() {
        val problems = ControlSet.validate(questions, CorpusLoader.load(File(root, "rag/corpus")))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun hasTenQuestionsWithAllCategories() {
        assertEquals(10, questions.size)
        assertEquals(ControlSet.CATEGORIES, questions.map { it.category }.toSet())
        assertTrue(questions.count { it.category == "out_of_corpus" } in 1..2)
    }

    @Test fun scorerCountsGroupsCaseInsensitively() {
        val q = ControlQuestion("x", "specific", "?", listOf(listOf("Dead bug"), listOf("5 to 8", "5-8")),
            listOf(ExpectedSource("a.md", "T")))
        val s = ControlScorer.score(q, "Do the DEAD BUG for 5-8 reps")
        assertEquals(2, s.factsHit)
        val partial = ControlScorer.score(q, "dead bug only")
        assertEquals(1, partial.factsHit)
        assertEquals(listOf(listOf("5 to 8", "5-8")), partial.missingFacts)
    }

    @Test fun scorerDetectsAdmissionOfMissingInformation() {
        val q = questions.first { it.category == "out_of_corpus" }
        assertTrue(ControlScorer.score(q, "The knowledge base does not cover this.").admitsNoInfo)
        assertFalse(ControlScorer.score(q, "The record is 501 kg.").admitsNoInfo)
    }

    @Test fun validationCatchesBadSetups() {
        val corpus = listOf(Document("a.md", "A", "# A\n\n## Real\n\nfoo bar"))
        val bad = listOf(
            ControlQuestion("1", "specific", "q", listOf(listOf("zzz")), listOf(ExpectedSource("a.md", "Nope"))),
            ControlQuestion("2", "specific", "q", listOf(listOf("foo")), listOf(ExpectedSource("missing.md", "x"))),
        )
        val problems = ControlSet.validate(bad, corpus)
        assertTrue(problems.any { "expected 10" in it })
        assertTrue(problems.any { "no heading containing 'Nope'" in it })
        assertTrue(problems.any { "not in corpus" in it })
    }

    @Test fun sourceCoverageSeesHeadingsInsideMergedChunks() {
        val c = Chunk("1", "a.md", "t", "Intro", "text\n## Day 2\n- squat", 0, 10, ChunkStrategy.STRUCTURE)
        assertTrue(ControlScorer.coversSection(c, "day 2"))
        assertTrue(ControlScorer.coversSection(c, "intro"))
        assertFalse(ControlScorer.coversSection(c, "Day 3"))
    }
}
