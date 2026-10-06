package com.example.rag

import com.example.core.platform.toKxPath

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Instant
import com.example.core.llm.HashingEmbeddingClient

class EvalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val meta = IndexMeta("m", 4, ChunkStrategy.FIXED, "p", "t", "c", 1, 1)
    private fun r(id: String, type: String, src: String?, rank: Int?, sec: Int? = rank, score: Float = 0.5f) =
        QuestionResult(id, type, "q", src, rank, sec, score, "c")

    @Test fun metricsAreComputedOverInCorpusQuestionsOnly() {
        val m = Evaluator.compute(meta, 5, listOf(
            r("1", "direct", "a.md", 1, score = 0.9f),
            r("2", "direct", "a.md", 3, score = 0.7f),
            r("3", "paraphrase", "b.md", null, null, score = 0.5f),
            r("4", "paraphrase", "b.md", 2, sec = null, score = 0.6f),
            r("5", "out_of_corpus", null, null, null, score = 0.3f),
            r("6", "out_of_corpus", null, null, null, score = 0.1f),
        ))
        assertEquals(4, m.inCorpusCount)
        assertEquals(0.25, m.hitAt1, 1e-9)
        assertEquals(0.75, m.hitAt3, 1e-9)
        assertEquals(0.75, m.hitAt5, 1e-9)
        assertEquals((1.0 + 1.0 / 3 + 0.0 + 0.5) / 4, m.mrr, 1e-9)
        assertEquals(0.5, m.sectionHitAt3, 1e-9)
        assertEquals(1.0, m.hitAt3Direct, 1e-9)
        assertEquals(0.5, m.hitAt3Paraphrase, 1e-9)
        assertEquals((0.9 + 0.7 + 0.5 + 0.6) / 4, m.avgTop1InCorpus, 1e-6)
        assertEquals(0.2, m.avgTop1OutOfCorpus!!, 1e-6)
        assertEquals(0.3, m.maxTop1OutOfCorpus!!, 1e-6)
        assertEquals(listOf("3"), m.misses.map { it.id })
    }

    @Test fun noOutOfCorpusQuestionsGivesNullAverage() {
        assertNull(Evaluator.compute(meta, 5, listOf(r("1", "direct", "a.md", 1))).avgTop1OutOfCorpus)
    }

    @Test fun evaluatorRanksAgainstARealIndex() = runTest {
        val docs = listOf(
            Document("sleep.md", "Sleep", "# Sleep\n\n## Hygiene\n\nSleep eight hours in a dark cool bedroom to recover."),
            Document("protein.md", "Protein", "# Protein\n\n## Intake\n\nEat protein every day to build muscle."),
        )
        val e = HashingEmbeddingClient(256)
        val idx = Indexer(e) { Instant.fromEpochMilliseconds(0) }.build(docs, StructureChunker(400, 10), "p", "c")
        val qs = listOf(
            EvalQuestion("a", "direct", "how many hours of sleep", "sleep.md", "Sleep"),
            EvalQuestion("b", "direct", "protein for muscle", "protein.md", "Protein"),
            EvalQuestion("c", "out_of_corpus", "kubernetes ingress"),
        )
        val m = Evaluator.run(idx, e, qs)
        assertEquals(1.0, m.hitAt1, 1e-9)
        assertEquals(1.0, m.sectionHitAt3, 1e-9)
        assertTrue(m.avgTop1OutOfCorpus!! < m.avgTop1InCorpus)
        assertTrue(Evaluator.renderMarkdown("offline", listOf(m)).contains("hit@1"))
    }

    @Test fun validationFlagsMissingSourceBadHeadingAndTypeProblems() {
        val docs = listOf(Document("a.md", "A", "# A\n\n## Real heading\n\ntext"))
        val good = EvalQuestion("1", "direct", "q", "a.md", "real")
        assertEquals(emptyList<String>(), EvalSet.validate(listOf(good, EvalQuestion("2", "out_of_corpus", "x")), docs))
        val problems = EvalSet.validate(listOf(
            EvalQuestion("3", "direct", "q", "missing.md", "x"),
            EvalQuestion("4", "direct", "q", "a.md", "nope"),
            EvalQuestion("5", "out_of_corpus", "q", "a.md"),
            EvalQuestion("6", "weird", "q"),
            good.copy(),
        ), docs)
        assertTrue(problems.any { it.startsWith("3:") && it.contains("not in the corpus") })
        assertTrue(problems.any { it.startsWith("4:") && it.contains("no heading") })
        assertTrue(problems.any { it.startsWith("5:") })
        assertTrue(problems.any { it.startsWith("6:") })
        assertTrue(problems.contains("duplicate question ids").not()) // ids 3,4,5,6,1 are unique
    }

    // The committed question file must stay valid against the committed corpus.
    @Test fun committedEvalSetIsValidAgainstCommittedCorpus() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "rag/eval/questions.json").exists() }
        val questions = EvalSet.load(File(root, "rag/eval/questions.json"))
        assertEquals(emptyList<String>(), EvalSet.validate(questions, CorpusLoader.load(File(root, "rag/corpus").toKxPath())))
        assertTrue(questions.size in 25..35)
        assertTrue(questions.count { it.type == "out_of_corpus" } in 3..4)
        assertTrue(questions.count { it.type == "paraphrase" } >= 8)
    }
}
