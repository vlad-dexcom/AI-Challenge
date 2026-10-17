package com.example.devassistant

import com.example.core.llm.CallPurpose
import com.example.core.llm.GenerationOptions
import com.example.core.llm.TextGenerator
import com.example.geminichat.mcp.McpGateway
import com.example.rag.Chunk
import com.example.rag.ChunkStrategy
import com.example.rag.CitationParser
import com.example.rag.CitationVerifier
import com.example.rag.IdkReason
import com.example.rag.IdkResponder
import com.example.rag.RagPromptBuilder
import com.example.rag.Retriever
import com.example.rag.SearchHit
import com.example.rag.StructuredAnswer
import com.example.rag.VerificationReport
import kotlinx.coroutines.CancellationException

/** One MCP tool call made while preparing an answer (shown to the user next to the sources). */
data class McpCall(val tool: String, val text: String, val isError: Boolean)

data class HelpAnswer(
    val question: String,
    val structured: StructuredAnswer,
    /** What the assistant asked the git MCP server for, with the results. */
    val mcpCalls: List<McpCall>,
    /** Excerpts that reached the prompt (project docs first, then MCP results). */
    val context: List<Chunk>,
) {
    val answer: String get() = structured.answer
}

/**
 * Day 31 `/help`: answers questions about the project from its documentation (RAG) and from the live repository
 * state (git MCP tools). MCP results are put into the prompt as extra excerpts (`source = "git (MCP)"`), so the
 * model can cite them and the quote verifier checks them the same way as documentation.
 */
class HelpAssistant(
    private val retriever: Retriever,
    private val generator: TextGenerator,
    private val git: McpGateway,
    private val topK: Int = 5,
) {
    private var connected = false

    suspend fun help(question: String): Result<HelpAnswer> {
        val q = question.trim()
        if (q.isEmpty()) return Result.failure(IllegalArgumentException("Question must not be blank"))

        val calls = gitContext(q)
        val docHits = try {
            retriever.retrieve(q, topK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val mcpHits = calls.filter { !it.isError && it.text.isNotBlank() }.map { SearchHit(mcpChunk(it), 1f) }
        val hits = docHits + mcpHits

        return answer(q, hits, docHits).map { HelpAnswer(q, it, calls, hits.map(SearchHit::chunk)) }
    }

    /** Always asks for the branch and working-tree status; adds the diff summary when the question is about changes. */
    private suspend fun gitContext(question: String): List<McpCall> {
        val tools = buildList {
            add(GitMcpGateway.BRANCH); add(GitMcpGateway.STATUS)
            if (DIFF_HINT.containsMatchIn(question)) add(GitMcpGateway.DIFF_STAT)
        }
        return try {
            if (!connected) { git.connect("local://git"); connected = true }
            tools.map { name ->
                try {
                    val r = git.callTool(name, emptyMap())
                    McpCall(name, r.text, r.isError)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    McpCall(name, e.message ?: "tool call failed", true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(McpCall("connect", e.message ?: "MCP connection failed", true))
        }
    }

    private fun mcpChunk(call: McpCall) = Chunk(
        chunkId = "mcp:${call.tool}", source = MCP_SOURCE, title = "Live repository state", section = call.tool,
        text = call.text, startOffset = 0, endOffset = call.text.length, strategy = ChunkStrategy.STRUCTURE,
    )

    private suspend fun answer(question: String, hits: List<SearchHit>, docHits: List<SearchHit>): Result<StructuredAnswer> {
        val options = GenerationOptions(temperature = 0.0, json = true, purpose = CallPurpose.CITED_ANSWER)
        var feedback: String? = null
        var report = VerificationReport()
        var reason = IdkReason.VERIFICATION_FAILED
        for (attempt in 1..MAX_ATTEMPTS) {
            val prompt = RagPromptBuilder.citationUserPrompt(question, hits, feedback)
            val reply = generator.generate(systemPrompt(attempt > 1), prompt, options).getOrElse { return Result.failure(it) }
            val raw = CitationParser.parse(reply).getOrElse {
                reason = IdkReason.MALFORMED_OUTPUT
                feedback = "Your previous reply was not valid JSON in the required format."
                report = VerificationReport(attempts = attempt, notes = listOf("malformed JSON: ${it.message.orEmpty().take(80)}"))
                continue
            }
            if (!raw.answerable) {
                return Result.success(idk(question, IdkReason.MODEL_UNANSWERABLE, docHits, VerificationReport(attempts = attempt)))
            }
            val checked = CitationVerifier.verify(raw, hits, attempt)
            val problems = buildList {
                if (raw.answer.isBlank()) add("the answer is empty")
                if (checked.verifiedSources == 0) add("no valid source: copy chunkId, file and section from an excerpt header")
                if (checked.verifiedQuotes == 0) add("no verifiable quote: copy fragments character for character from an excerpt")
            }
            if (problems.isEmpty()) {
                val sources = checked.sources.filter { it.ok }.map { it.source }.distinctBy { it.chunkId }
                return Result.success(StructuredAnswer(raw.answer, sources, checked.quotes, checked, language = IdkResponder.detectLanguage(question)))
            }
            reason = IdkReason.VERIFICATION_FAILED
            feedback = "Your previous reply was rejected: ${problems.joinToString("; ")}."
            report = checked.copy(notes = listOf(problems.joinToString("; ")))
        }
        return Result.success(idk(question, reason, docHits, report.copy(attempts = MAX_ATTEMPTS)))
    }

    private fun idk(question: String, reason: IdkReason, closest: List<SearchHit>, report: VerificationReport): StructuredAnswer {
        val ru = IdkResponder.detectLanguage(question) == "ru"
        val topics = IdkResponder.topics(closest)
        val text = buildString {
            append(
                if (ru) "Не знаю: в документации проекта нет подтверждённого ответа на этот вопрос."
                else "I don't know: the project documentation has no verifiable answer to this question.",
            )
            if (topics.isNotEmpty()) append("\n\n" + (if (ru) "Ближайшие разделы (это не ответ): " else "Closest sections (not an answer): ") + topics.joinToString(", ") { "«$it»" })
        }
        return StructuredAnswer(text, verification = report, idk = true, idkReason = reason, relatedTopics = topics, language = if (ru) "ru" else "en")
    }

    companion object {
        const val MCP_SOURCE = "git (MCP)"
        private const val MAX_ATTEMPTS = 2
        private val DIFF_HINT = Regex("diff|change|modif|измен|правк|поменя|отличи", RegexOption.IGNORE_CASE)

        const val PERSONA = "You are a developer assistant for this repository (an Android + JVM app: a Gemini chat agent with MCP tools and RAG). " +
            "Answer questions about the project's structure, modules, conventions and workflow. " +
            "Excerpts whose file is \"$MCP_SOURCE\" are live repository state returned by MCP tools (current branch, working tree status); the others are project documentation."

        fun systemPrompt(strict: Boolean): String =
            "$PERSONA\n\n${RagPromptBuilder.CITATION_RULES}" + if (strict) RagPromptBuilder.STRICT_SUFFIX else ""
    }
}
