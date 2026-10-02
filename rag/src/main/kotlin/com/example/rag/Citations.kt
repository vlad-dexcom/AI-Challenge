package com.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer

/*
 * Day 24 answer contract. The model replies with ONE JSON object:
 *   {"answerable": bool, "answer": str, "sources": [{"file","section","chunkId"}], "quotes": [{"chunkId","text"}]}
 * Quotes are verbatim fragments of the retrieved chunks, so they stay in the corpus language (English) even when the
 * answer is Russian. Everything the model claims is re-checked in code by [CitationVerifier].
 */

@Serializable
data class SourceRef(val file: String, val section: String, val chunkId: String, val score: Float? = null)

@Serializable
data class CitedQuote(val chunkId: String, val text: String)

/** Parsed (not yet verified) model reply. */
data class RawCitedAnswer(val answerable: Boolean, val answer: String, val sources: List<SourceRef>, val quotes: List<CitedQuote>)

@Serializable
enum class QuoteStatus { VERIFIED, REATTRIBUTED, NOT_FOUND, TOO_SHORT }

/** [resolvedChunkId] is the retrieved chunk the text was actually found in (differs from the cited id for REATTRIBUTED). */
@Serializable
data class QuoteCheck(val chunkId: String, val text: String, val status: QuoteStatus, val resolvedChunkId: String? = null) {
    val ok: Boolean get() = status == QuoteStatus.VERIFIED || status == QuoteStatus.REATTRIBUTED
}

@Serializable
data class SourceCheck(val source: SourceRef, val ok: Boolean, val note: String? = null)

@Serializable
data class VerificationReport(
    val quotes: List<QuoteCheck> = emptyList(),
    val sources: List<SourceCheck> = emptyList(),
    /** Model calls (answer attempts) that were made; 0 when the filter rejected everything. */
    val attempts: Int = 0,
    val notes: List<String> = emptyList(),
) {
    val verifiedQuotes: Int get() = quotes.count { it.ok }
    val verifiedSources: Int get() = sources.count { it.ok }
    val passed: Boolean get() = verifiedQuotes > 0 && verifiedSources > 0

    fun summary(): String =
        "quotes ${verifiedQuotes}/${quotes.size} verified, sources ${verifiedSources}/${sources.size} valid" +
            (notes.takeIf { it.isNotEmpty() }?.joinToString(prefix = "; ", separator = "; ") ?: "")
}

@Serializable
enum class IdkReason { BELOW_THRESHOLD, MODEL_UNANSWERABLE, VERIFICATION_FAILED, MALFORMED_OUTPUT }

/**
 * Final result of a RAG answer. Either an answer with verified [sources] and [quotes] ([idk] = false),
 * or an "I don't know" ([idk] = true, [idkReason] set) with a [clarification] question and [relatedTopics] (NOT an answer).
 */
@Serializable
data class StructuredAnswer(
    val answer: String,
    val sources: List<SourceRef> = emptyList(),
    val quotes: List<QuoteCheck> = emptyList(),
    val verification: VerificationReport = VerificationReport(),
    val idk: Boolean = false,
    val idkReason: IdkReason? = null,
    val clarification: String? = null,
    val relatedTopics: List<String> = emptyList(),
    /** "ru" or "en": the language of [answer] / the IDK text (follows the question). */
    val language: String = "en",
)

object CitationParser {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Accepts fenced or prose-wrapped JSON (takes the outermost `{...}`); requires `answerable`. */
    fun parse(reply: String): Result<RawCitedAnswer> = runCatching {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in reply" }
        val obj = lenient.parseToJsonElement(reply.substring(start, end + 1)) as? JsonObject ?: error("reply is not a JSON object")
        val answerable = (obj["answerable"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
            ?: error("missing boolean 'answerable'")
        val answer = (obj["answer"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        fun str(o: JsonObject, vararg names: String) = names.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }.orEmpty().trim()
        val sources = (obj["sources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { SourceRef(str(it, "file", "source"), str(it, "section"), str(it, "chunkId", "chunk_id")) }
        val quotes = (obj["quotes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { CitedQuote(str(it, "chunkId", "chunk_id"), str(it, "text", "quote")) }
        RawCitedAnswer(answerable, answer, sources, quotes)
    }
}

object CitationVerifier {
    const val MIN_QUOTE_CHARS = 12
    private val LIST_MARKER = Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val ELLIPSIS = Regex("\\[\\.\\.\\.]|\\.{3}|…")

    /** Whitespace/case/markdown/quote-style/dash/ё-insensitive form used for the verbatim check. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in Normalizer.normalize(LIST_MARKER.replace(s, ""), Normalizer.Form.NFKC)) {
            when (c) {
                '‘', '’', '‚', '‛', '′', '`' -> sb.append('\'')
                '“', '”', '„', '‟', '″', '«', '»' -> sb.append('"')
                '‐', '‑', '‒', '–', '—', '―', '−' -> sb.append('-')
                '*', '_', '#', '>', '|' -> {}
                else -> sb.append(if (c == 'ё' || c == 'Ё') 'е' else c.lowercaseChar())
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun fragments(text: String): List<String> =
        ELLIPSIS.split(text).map(::normalize).filter { it.isNotEmpty() }

    private fun foundIn(text: String, chunk: String): Boolean {
        val parts = fragments(text)
        if (parts.isEmpty() || parts.sumOf { it.length } < MIN_QUOTE_CHARS) return false
        var from = 0
        for (p in parts) {
            val i = chunk.indexOf(p, from)
            if (i < 0) return false
            from = i + p.length
        }
        return true
    }

    fun verify(raw: RawCitedAnswer, hits: List<SearchHit>, attempts: Int, extraNotes: List<String> = emptyList()): VerificationReport {
        val byId = hits.associateBy { it.chunk.chunkId }
        val normalized = hits.associate { it.chunk.chunkId to normalize(it.chunk.text) }
        val notes = extraNotes.toMutableList()

        val quotes = raw.quotes.map { q ->
            when {
                fragments(q.text).sumOf { it.length } < MIN_QUOTE_CHARS -> QuoteCheck(q.chunkId, q.text, QuoteStatus.TOO_SHORT)
                q.chunkId in byId && foundIn(q.text, normalized.getValue(q.chunkId)) -> QuoteCheck(q.chunkId, q.text, QuoteStatus.VERIFIED, q.chunkId)
                else -> {
                    val other = hits.firstOrNull { it.chunk.chunkId != q.chunkId && foundIn(q.text, normalized.getValue(it.chunk.chunkId)) }
                    if (other != null) QuoteCheck(q.chunkId, q.text, QuoteStatus.REATTRIBUTED, other.chunk.chunkId)
                    else QuoteCheck(q.chunkId, q.text, QuoteStatus.NOT_FOUND)
                }
            }
        }
        val sources = raw.sources.map { s ->
            val byIdHit = byId[s.chunkId]
            val byLabel = hits.filter { it.chunk.source == s.file && it.chunk.section.equals(s.section, ignoreCase = true) }.takeIf { it.size == 1 }?.single()
            when {
                byIdHit != null -> SourceCheck(s.withScore(byIdHit), true)
                byLabel != null -> SourceCheck(s.copy(chunkId = byLabel.chunk.chunkId).withScore(byLabel), true, "chunkId '${s.chunkId}' corrected from file+section")
                else -> SourceCheck(s, false, "not among the retrieved chunks")
            }
        }
        if (quotes.any { it.status == QuoteStatus.NOT_FOUND }) notes += "fabricated or altered quotes: ${quotes.count { it.status == QuoteStatus.NOT_FOUND }}"
        return VerificationReport(quotes, sources, attempts, notes)
    }

    private fun SourceRef.withScore(hit: SearchHit) = copy(file = hit.chunk.source, section = hit.chunk.section, score = hit.score)
}

object IdkResponder {
    fun detectLanguage(text: String): String {
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val lat = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (cyr > lat) "ru" else "en"
    }

    /** Closest chunks below this cosine are unrelated noise (Day 23 sweep: off-topic top-1 scores are 0.50-0.60), so no topics are offered. */
    const val RELATED_MIN_SCORE = 0.55f

    /** Up to [limit] distinct section titles (without the document title) of the closest chunks; a hint for the user, never presented as an answer. */
    fun topics(hits: List<SearchHit>, limit: Int = 3): List<String> =
        hits.filter { it.score >= RELATED_MIN_SCORE }
            .map { h -> h.chunk.section.split(" > ").map(String::trim).filter(String::isNotEmpty).let { if (it.size > 1) it.drop(1).joinToString(" > ") else it.firstOrNull() ?: h.chunk.title } }
            .filter { it.isNotBlank() }.distinct().take(limit)

    /**
     * Deterministic template (no extra LLM call): the text uses only the user's question language, fixed wording and
     * section titles from the index, so it cannot make free-form claims about training.
     */
    fun build(question: String, reason: IdkReason, closest: List<SearchHit>, report: VerificationReport = VerificationReport()): StructuredAnswer {
        val lang = detectLanguage(question)
        val topics = topics(closest)
        val ru = lang == "ru"
        val head = when {
            reason == IdkReason.VERIFICATION_FAILED || reason == IdkReason.MALFORMED_OUTPUT ->
                if (ru) "Не знаю: не удалось подтвердить ответ цитатами из базы знаний, поэтому я не буду его придумывать."
                else "I don't know: I could not back an answer with verifiable quotes from the knowledge base, so I won't make one up."
            else ->
                if (ru) "Не знаю: в базе знаний нет информации, которая надёжно отвечает на этот вопрос."
                else "I don't know: the knowledge base has no information that reliably answers this question."
        }
        val related = if (topics.isEmpty()) null else {
            val list = topics.joinToString(", ") { "«$it»" }
            if (ru) "Ближайшие темы в базе (это не ответ на ваш вопрос): $list." else "Closest topics in the knowledge base (not an answer to your question): $list."
        }
        val clarification = when {
            ru && topics.isNotEmpty() -> "Вы имели в виду одну из этих тем? Если нет — уточните, о каком упражнении, программе или аспекте питания идёт речь."
            ru -> "О каком именно упражнении, программе тренировок или аспекте питания вы хотите узнать?"
            topics.isNotEmpty() -> "Did you mean one of these topics? If not, which exercise, program or nutrition aspect do you have in mind?"
            else -> "Which exercise, training program or nutrition aspect do you mean?"
        }
        val text = listOfNotNull(head, related, clarification).joinToString("\n\n")
        return StructuredAnswer(text, verification = report, idk = true, idkReason = reason, clarification = clarification, relatedTopics = topics, language = lang)
    }
}

/** Generates the JSON answer, verifies it in code, retries once with a stricter prompt, and falls back to "I don't know". */
class CitedAnswerer(private val generator: TextGenerator) {
    private sealed interface Attempt {
        data class Parsed(val raw: RawCitedAnswer) : Attempt
        data class Malformed(val why: String) : Attempt
    }

    suspend fun answer(question: String, hits: List<SearchHit>, closest: List<SearchHit> = hits): Result<StructuredAnswer> {
        val options = GenerationOptions(temperature = 0.0, json = true)
        var lastReport = VerificationReport()
        var lastReason = IdkReason.VERIFICATION_FAILED
        var feedback: String? = null
        val notes = mutableListOf<String>()
        for (attempt in 1..MAX_ATTEMPTS) {
            val prompt = RagPromptBuilder.citationUserPrompt(question, hits, feedback)
            val reply = generator.generate(RagPromptBuilder.citationSystemPrompt(strict = attempt > 1), prompt, options).getOrElse { return Result.failure(it) }
            val parsed = parseOrRepair(reply, options).getOrElse { return Result.failure(it) }
            when (parsed) {
                is Attempt.Malformed -> {
                    lastReason = IdkReason.MALFORMED_OUTPUT
                    notes += "attempt $attempt: malformed JSON (${parsed.why})"
                    feedback = "Your previous reply was not valid JSON in the required format."
                    lastReport = VerificationReport(attempts = attempt, notes = notes.toList())
                }
                is Attempt.Parsed -> {
                    val raw = parsed.raw
                    if (!raw.answerable) {
                        val report = VerificationReport(attempts = attempt, notes = notes + "model reported answerable=false")
                        return Result.success(IdkResponder.build(question, IdkReason.MODEL_UNANSWERABLE, closest, report))
                    }
                    val report = CitationVerifier.verify(raw, hits, attempt, notes.toList())
                    val problems = buildList {
                        if (raw.answer.isBlank()) add("the answer is empty")
                        if (report.verifiedSources == 0) add("no valid source: cite the chunkId, file and section of the excerpts you used, exactly as in their headers")
                        if (report.verifiedQuotes == 0) add("no verifiable quote: copy fragments character for character from an excerpt, do not translate or paraphrase")
                    }
                    if (problems.isEmpty()) {
                        val good = report.sources.filter { it.ok }.map { it.source }.distinctBy { it.chunkId }
                        return Result.success(StructuredAnswer(raw.answer, good, report.quotes, report, language = IdkResponder.detectLanguage(question)))
                    }
                    lastReason = IdkReason.VERIFICATION_FAILED
                    notes += "attempt $attempt rejected: ${problems.joinToString("; ")}; ${report.summary()}"
                    feedback = "Your previous reply was rejected: ${problems.joinToString("; ")}."
                    lastReport = report.copy(notes = notes.toList())
                }
            }
        }
        return Result.success(IdkResponder.build(question, lastReason, closest, lastReport.copy(attempts = MAX_ATTEMPTS)))
    }

    /** One repair call when the reply is not parseable; a failed repair request is a malformed attempt, not a hard error. */
    private suspend fun parseOrRepair(reply: String, options: GenerationOptions): Result<Attempt> {
        CitationParser.parse(reply).onSuccess { return Result.success(Attempt.Parsed(it)) }
        val repaired = generator.generate(RagPromptBuilder.REPAIR_SYSTEM, RagPromptBuilder.repairPrompt(reply), options).getOrNull()
        val second = repaired?.let { CitationParser.parse(it) }
        return second?.fold({ Result.success(Attempt.Parsed(it)) }, { Result.success(Attempt.Malformed(it.message.orEmpty().take(80))) })
            ?: Result.success(Attempt.Malformed("repair request failed"))
    }

    companion object {
        const val MAX_ATTEMPTS = 2
    }
}
