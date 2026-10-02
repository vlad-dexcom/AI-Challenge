package com.example.rag

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {
    private fun chunk(id: String, section: String, text: String) =
        SearchHit(Chunk(id, "04-pulling.md", "Pulling", section, text, 0, text.length, ChunkStrategy.STRUCTURE), 0.8f)

    private val pullups = chunk("04-pulling.md#structure-1", "Pulling > Pull-up progressions",
        "## Pull-up progressions\n\n- **Eccentric** pull-ups: lower yourself for 3–5 seconds.\n- Band-assisted pull-ups with a “light” band.")
    private val rows = chunk("04-pulling.md#structure-2", "Pulling > Rows", "Rows build the mid-back. Keep the ribcage down and the neck neutral.")
    private val hits = listOf(pullups, rows)

    private fun json(
        answerable: Boolean = true, answer: String = "Use eccentrics [1].",
        sources: String = """[{"file":"04-pulling.md","section":"Pulling > Pull-up progressions","chunkId":"04-pulling.md#structure-1"}]""",
        quotes: String = """[{"chunkId":"04-pulling.md#structure-1","text":"Eccentric pull-ups: lower yourself for 3-5 seconds."}]""",
    ) = """{"answerable":$answerable,"answer":"$answer","sources":$sources,"quotes":$quotes}"""

    private class Scripted(private val replies: MutableList<Result<String>>) : TextGenerator {
        val calls = mutableListOf<Triple<String?, String, GenerationOptions>>()
        override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
            generate(systemInstruction, prompt, GenerationOptions())
        override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
            calls += Triple(systemInstruction, prompt, options)
            return replies.removeAt(0)
        }
    }

    private fun scripted(vararg r: String) = Scripted(r.map { Result.success(it) }.toMutableList())

    // ---- parser ----
    @Test fun parserAcceptsFencedAndWrappedJson() {
        val raw = CitationParser.parse("Here you go:\n```json\n" + json() + "\n```").getOrThrow()
        assertTrue(raw.answerable)
        assertEquals(1, raw.sources.size)
        assertEquals("04-pulling.md#structure-1", raw.quotes.single().chunkId)
    }

    @Test fun parserAcceptsSnakeCaseKeysAndRejectsGarbage() {
        val raw = CitationParser.parse("""{"answerable":"true","answer":"a","sources":[{"source":"f.md","section":"s","chunk_id":"x"}],"quotes":[{"chunk_id":"x","quote":"some text here"}]}""").getOrThrow()
        assertEquals("x", raw.sources.single().chunkId)
        assertEquals("some text here", raw.quotes.single().text)
        assertTrue(CitationParser.parse("not json").isFailure)
        assertTrue(CitationParser.parse("""{"answer":"no flag"}""").isFailure)
    }

    // ---- normalization / verification ----
    @Test fun normalizationIgnoresWhitespaceCaseDashesQuotesMarkdownAndYo() {
        assertEquals(CitationVerifier.normalize("**Eccentric**  pull-ups:\n lower – “slowly”"), CitationVerifier.normalize("eccentric pull-ups: LOWER - \"slowly\""))
        assertEquals(CitationVerifier.normalize("Всё ещё"), CitationVerifier.normalize("все еще"))
    }

    @Test fun verbatimQuoteWithDifferentWhitespaceCaseAndDashIsVerified() {
        val raw = CitationParser.parse(json()).getOrThrow()
        val report = CitationVerifier.verify(raw, hits, 1)
        assertEquals(QuoteStatus.VERIFIED, report.quotes.single().status)
        assertTrue(report.passed)
    }

    @Test fun fabricatedQuoteIsNotFound() {
        val raw = CitationParser.parse(json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"Do 100 pull-ups every day for a month."}]""")).getOrThrow()
        val report = CitationVerifier.verify(raw, hits, 1)
        assertEquals(QuoteStatus.NOT_FOUND, report.quotes.single().status)
        assertFalse(report.passed)
        assertTrue(report.notes.any { "fabricated" in it })
    }

    @Test fun wrongChunkIdIsReattributedWhenTheTextIsInAnotherRetrievedChunk() {
        val raw = CitationParser.parse(json(quotes = """[{"chunkId":"04-pulling.md#structure-2","text":"Eccentric pull-ups: lower yourself for 3-5 seconds."}]""")).getOrThrow()
        val q = CitationVerifier.verify(raw, hits, 1).quotes.single()
        assertEquals(QuoteStatus.REATTRIBUTED, q.status)
        assertEquals("04-pulling.md#structure-1", q.resolvedChunkId)
    }

    @Test fun quoteFromAChunkThatWasNotRetrievedIsRejected() {
        val raw = CitationParser.parse(json(quotes = """[{"chunkId":"99.md#structure-0","text":"Squat deep and keep the knees out."}]""")).getOrThrow()
        assertEquals(QuoteStatus.NOT_FOUND, CitationVerifier.verify(raw, hits, 1).quotes.single().status)
    }

    @Test fun tooShortQuotesDoNotCount() {
        val raw = CitationParser.parse(json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"pull-ups"}]""")).getOrThrow()
        assertEquals(QuoteStatus.TOO_SHORT, CitationVerifier.verify(raw, hits, 1).quotes.single().status)
    }

    @Test fun ellipsisJoinsOrderedFragmentsOfOneChunk() {
        val ok = CitationParser.parse(json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"Eccentric pull-ups ... Band-assisted pull-ups"}]""")).getOrThrow()
        assertTrue(CitationVerifier.verify(ok, hits, 1).quotes.single().ok)
        val wrongOrder = CitationParser.parse(json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"Band-assisted pull-ups ... Eccentric pull-ups"}]""")).getOrThrow()
        assertFalse(CitationVerifier.verify(wrongOrder, hits, 1).quotes.single().ok)
    }

    @Test fun sourcesMustBeAmongTheRetrievedChunks() {
        val raw = CitationParser.parse(json(sources = """[{"file":"99.md","section":"X","chunkId":"99.md#structure-0"},{"file":"04-pulling.md","section":"pulling > rows","chunkId":"bad-id"}]""")).getOrThrow()
        val s = CitationVerifier.verify(raw, hits, 1).sources
        assertFalse(s[0].ok)
        assertTrue(s[1].ok)
        assertEquals("04-pulling.md#structure-2", s[1].source.chunkId)
    }

    // ---- answerer ----
    @Test fun validAnswerPassesWithRussianAnswerAndEnglishQuotes() = runTest {
        val gen = scripted(json(answer = "Используйте эксцентрические подтягивания [1]."))
        val a = CitedAnswerer(gen).answer("Как научиться подтягиваться?", hits).getOrThrow()
        assertFalse(a.idk)
        assertEquals("ru", a.language)
        assertEquals("04-pulling.md#structure-1", a.sources.single().chunkId)
        assertEquals(0.8f, a.sources.single().score)
        assertTrue(a.quotes.single().ok)
        assertTrue(gen.calls.single().third.json)
        assertEquals(0.0, gen.calls.single().third.temperature)
        assertTrue(gen.calls.single().third.json)
        assertTrue(gen.calls.single().second.contains("chunkId: 04-pulling.md#structure-1"))
    }

    @Test fun malformedJsonIsRepairedOnce() = runTest {
        val gen = scripted("Sure! The answer is eccentrics.", json())
        val a = CitedAnswerer(gen).answer("q", hits).getOrThrow()
        assertFalse(a.idk)
        assertEquals(2, gen.calls.size)
        assertEquals(RagPromptBuilder.REPAIR_SYSTEM, gen.calls[1].first)
    }

    @Test fun unrepairableOutputEndsAsIdk() = runTest {
        val gen = scripted("nope", "still nope", "nope again", "no")
        val a = CitedAnswerer(gen).answer("q", hits).getOrThrow()
        assertTrue(a.idk)
        assertEquals(IdkReason.MALFORMED_OUTPUT, a.idkReason)
        assertEquals(4, gen.calls.size)
    }

    @Test fun fabricatedQuotesTriggerOneStricterRetryThenSucceed() = runTest {
        val bad = json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"Made up sentence about pull-ups."}]""")
        val gen = scripted(bad, json())
        val a = CitedAnswerer(gen).answer("q", hits).getOrThrow()
        assertFalse(a.idk)
        assertEquals(2, gen.calls.size)
        assertTrue(gen.calls[1].first!!.contains("This is a retry"))
        assertTrue(gen.calls[1].second.contains("rejected"))
        assertEquals(2, a.verification.attempts)
    }

    @Test fun persistentVerificationFailureFallsBackToIdk() = runTest {
        val bad = json(quotes = """[{"chunkId":"04-pulling.md#structure-1","text":"Made up sentence about pull-ups."}]""")
        val a = CitedAnswerer(scripted(bad, bad)).answer("What are pull-up progressions?", hits).getOrThrow()
        assertTrue(a.idk)
        assertEquals(IdkReason.VERIFICATION_FAILED, a.idkReason)
        assertTrue(a.sources.isEmpty())
        assertNotNull(a.clarification)
        assertTrue(a.answer.startsWith("I don't know"))
    }

    @Test fun zeroSourcesIsRejected() = runTest {
        val noSources = json(sources = "[]")
        val a = CitedAnswerer(scripted(noSources, noSources)).answer("q", hits).getOrThrow()
        assertTrue(a.idk)
    }

    @Test fun modelSayingUnanswerableIsIdkWithoutRetry() = runTest {
        val gen = scripted(json(answerable = false, answer = "", sources = "[]", quotes = "[]"))
        val a = CitedAnswerer(gen).answer("Which protein powder brand?", hits).getOrThrow()
        assertTrue(a.idk)
        assertEquals(IdkReason.MODEL_UNANSWERABLE, a.idkReason)
        assertEquals(1, gen.calls.size)
        assertTrue(a.relatedTopics.isNotEmpty())
        assertTrue(a.quotes.isEmpty())
    }

    @Test fun generatorFailureIsAFailure() = runTest {
        val gen = Scripted(mutableListOf(Result.failure(IllegalStateException("down"))))
        assertEquals("down", CitedAnswerer(gen).answer("q", hits).exceptionOrNull()!!.message)
    }

    // ---- IDK text ----
    @Test fun idkFollowsTheQuestionLanguageAndAsksOneQuestion() {
        val ru = IdkResponder.build("Какой рекорд в становой тяге?", IdkReason.BELOW_THRESHOLD, hits)
        assertTrue(ru.answer.startsWith("Не знаю"))
        assertEquals("ru", ru.language)
        assertEquals(1, ru.clarification!!.count { it == '?' })
        assertTrue(ru.answer.contains("не ответ"))
        val en = IdkResponder.build("What is the record?", IdkReason.BELOW_THRESHOLD, emptyList())
        assertTrue(en.answer.startsWith("I don't know"))
        assertTrue(en.relatedTopics.isEmpty())
        assertEquals(1, en.clarification!!.count { it == '?' })
    }

    // ---- pipeline ----
    private fun pipeline(gen: TextGenerator, score: Float = 0.8f) = RagPipeline(
        { _ -> hits.map { it.copy(score = score) } }, gen, config = RagConfig.DEFAULT, citations = true,
    )

    @Test fun pipelineBelowThresholdIsIdkWithoutCallingTheModel() = runTest {
        val gen = scripted()
        val a = pipeline(gen, score = 0.3f).ask("Какой мировой рекорд?", RagMode.RAG).getOrThrow()
        assertTrue(gen.calls.isEmpty())
        assertTrue(a.insufficientContext)
        assertEquals(IdkReason.BELOW_THRESHOLD, a.structured!!.idkReason)
        assertTrue(a.answer.startsWith("Не знаю"))
        assertTrue(a.hits.isEmpty())
    }

    @Test fun pipelineAnswersWithStructuredSources() = runTest {
        val a = pipeline(scripted(json())).ask("pull-up progressions?", RagMode.RAG).getOrThrow()
        assertFalse(a.insufficientContext)
        assertEquals("Use eccentrics [1].", a.answer)
        assertEquals(1, a.structured!!.sources.size)
    }

    @Test fun pipelineDefaultsKeepFreeTextAndNoRagHasNoStructure() = runTest {
        val plain = RagPipeline({ _ -> hits }, TextGenerator { _, _ -> Result.success("free text") })
        assertEquals("free text", plain.ask("q", RagMode.RAG).getOrThrow().answer)
        val gen = scripted()
        val no = pipeline(TextGenerator { _, _ -> Result.success("memory") }).ask("q", RagMode.NO_RAG).getOrThrow()
        assertEquals(null, no.structured)
        assertTrue(gen.calls.isEmpty())
    }
}
