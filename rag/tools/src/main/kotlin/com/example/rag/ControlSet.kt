package com.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ExpectedSource(val source: String, /** Case-insensitive keyword of a heading in [source]. */ val section: String)

@Serializable
data class ControlQuestion(
    val id: String,
    /** specific | common | out_of_corpus */
    val category: String,
    val question: String,
    /** Each group is a list of alternatives; a group is hit when any alternative appears in the answer. */
    val mustContain: List<List<String>> = emptyList(),
    val expectedSources: List<ExpectedSource> = emptyList(),
) {
    val inCorpus: Boolean get() = category != "out_of_corpus"
}

@Serializable
private data class ControlFile(val description: String = "", val questions: List<ControlQuestion>)

object ControlSet {
    val CATEGORIES = setOf("specific", "common", "out_of_corpus")

    fun load(file: File): List<ControlQuestion> =
        Json { ignoreUnknownKeys = true }.decodeFromString(ControlFile.serializer(), file.readText()).questions

    fun validate(questions: List<ControlQuestion>, corpus: List<Document>): List<String> {
        val problems = mutableListOf<String>()
        val bySource = corpus.associateBy { it.source }
        if (questions.size != 10) problems += "expected 10 control questions, found ${questions.size}"
        if (questions.map { it.id }.toSet().size != questions.size) problems += "duplicate question ids"
        for (q in questions) {
            if (q.question.isBlank()) problems += "${q.id}: empty question"
            if (q.category !in CATEGORIES) problems += "${q.id}: unknown category '${q.category}'"
            if (q.inCorpus) {
                if (q.mustContain.isEmpty() || q.mustContain.any { g -> g.isEmpty() || g.any { it.isBlank() } }) problems += "${q.id}: needs non-empty mustContain groups"
                if (q.expectedSources.isEmpty()) problems += "${q.id}: needs expectedSources"
            } else if (q.mustContain.isNotEmpty() || q.expectedSources.isNotEmpty()) {
                problems += "${q.id}: out_of_corpus question must not list facts or sources"
            }
            for (es in q.expectedSources) {
                val doc = bySource[es.source]
                if (doc == null) { problems += "${q.id}: source '${es.source}' not in corpus"; continue }
                if (findHeadings(doc.text).none { it.text.contains(es.section, ignoreCase = true) }) {
                    problems += "${q.id}: no heading containing '${es.section}' in ${es.source}"
                }
            }
            // The answer facts must really be in the cited section's source, otherwise the expectation is unfair.
            for (group in q.mustContain) {
                val docs = q.expectedSources.mapNotNull { bySource[it.source] }
                if (docs.isNotEmpty() && docs.none { d -> group.any { d.text.contains(it, ignoreCase = true) } }) {
                    problems += "${q.id}: no alternative of ${group} occurs in the expected source(s)"
                }
            }
        }
        return problems
    }
}

/** Mechanical part of the rubric; the human verdict is recorded separately in the docs. */
@Serializable
data class ModeScore(
    val factsHit: Int,
    val factsTotal: Int,
    val missingFacts: List<List<String>>,
    /** Heuristic: the answer says the information is not available / not covered. */
    val admitsNoInfo: Boolean,
)

@Serializable
data class SourceUsage(
    /** Expected sources found among retrieved chunks (file + section keyword). */
    val expectedFound: Int,
    val expectedTotal: Int,
    val retrieved: List<String>,
    val topScore: Float?,
    /** Whether the answer cites at least one [n] marker. */
    val answerCitesSources: Boolean,
)

@Serializable
data class ControlResult(
    val id: String,
    val category: String,
    val question: String,
    val noRag: String,
    val rag: String,
    val noRagScore: ModeScore,
    val ragScore: ModeScore,
    val sources: SourceUsage,
)

object ControlScorer {
    private val NO_INFO = listOf(
        "does not cover", "doesn't cover", "not cover", "does not contain", "doesn't contain", "not contain",
        "no information", "not mention", "does not mention", "doesn't mention", "not specif", "not provide",
        "not include", "isn't included", "is not included", "not available in", "cannot find", "can't find", "do not have",
    )

    fun score(q: ControlQuestion, answer: String): ModeScore {
        val missing = q.mustContain.filter { g -> g.none { answer.contains(it, ignoreCase = true) } }
        return ModeScore(
            factsHit = q.mustContain.size - missing.size,
            factsTotal = q.mustContain.size,
            missingFacts = missing,
            admitsNoInfo = NO_INFO.any { answer.contains(it, ignoreCase = true) },
        )
    }

    /** A structure chunk is labelled with its first heading but can span several sub-sections, so also look at heading lines inside the text. */
    fun coversSection(chunk: Chunk, keyword: String): Boolean =
        chunk.section.contains(keyword, ignoreCase = true) ||
            chunk.text.lineSequence().any { it.startsWith("#") && it.contains(keyword, ignoreCase = true) }

    fun sourceUsage(q: ControlQuestion, rag: RagAnswer): SourceUsage {
        val found = q.expectedSources.count { es ->
            rag.hits.any { it.chunk.source == es.source && coversSection(it.chunk, es.section) }
        }
        return SourceUsage(
            found, q.expectedSources.size, rag.sources, rag.hits.firstOrNull()?.score,
            Regex("\\[\\d+]").containsMatchIn(rag.answer),
        )
    }

    suspend fun run(pipeline: RagPipeline, questions: List<ControlQuestion>): List<ControlResult> =
        questions.map { q ->
            val cmp = pipeline.compare(q.question).getOrThrow()
            ControlResult(
                q.id, q.category, q.question, cmp.withoutRag.answer, cmp.withRag.answer,
                score(q, cmp.withoutRag.answer), score(q, cmp.withRag.answer), sourceUsage(q, cmp.withRag),
            )
        }

    fun renderMarkdown(questions: List<ControlQuestion>, results: List<ControlResult>): String = buildString {
        appendLine("| # | category | question | facts no-RAG | facts RAG | expected sources retrieved | cites [n] | admits no info (no-RAG / RAG) |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (r in results) {
            val inCorpus = r.category != "out_of_corpus"
            val facts = { s: ModeScore -> if (inCorpus) "${s.factsHit}/${s.factsTotal}" else "n/a" }
            val src = if (inCorpus) "${r.sources.expectedFound}/${r.sources.expectedTotal}" else "n/a"
            appendLine("| ${r.id} | ${r.category} | ${r.question} | ${facts(r.noRagScore)} | ${facts(r.ragScore)} | $src | ${if (r.sources.answerCitesSources) "yes" else "no"} | ${if (r.noRagScore.admitsNoInfo) "yes" else "no"} / ${if (r.ragScore.admitsNoInfo) "yes" else "no"} |")
        }
        val inCorpus = results.filter { it.category != "out_of_corpus" }
        appendLine()
        appendLine("Facts hit (in-corpus, ${inCorpus.size} questions): no-RAG ${inCorpus.sumOf { it.noRagScore.factsHit }}/${inCorpus.sumOf { it.noRagScore.factsTotal }}, RAG ${inCorpus.sumOf { it.ragScore.factsHit }}/${inCorpus.sumOf { it.ragScore.factsTotal }}.")
    }
}
