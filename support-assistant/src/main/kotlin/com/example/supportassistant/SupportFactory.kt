package com.example.supportassistant

import com.example.core.llm.GeminiTextGenerator
import com.example.devassistant.DocsIndex
import com.example.rag.Document
import com.example.rag.VectorRetriever
import java.io.File

/** The support knowledge base: FAQ and documentation articles as markdown files. */
object SupportKb {
    const val DEFAULT_DIR = "support/kb"

    fun load(dir: File): List<Document> {
        require(dir.isDirectory) { "Support knowledge base not found: ${dir.path}" }
        return dir.listFiles { f -> f.isFile && f.extension == "md" }.orEmpty().sortedBy { it.name }.map { f ->
            val text = f.readText().replace("\r\n", "\n")
            val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: f.nameWithoutExtension
            Document(f.name, title, text)
        }
    }
}

/** Wires a [SupportAssistant] to the repository's support data: FAQ index + Gemini + the ticket-scoped CRM MCP server. Shared by the CLI and the web console. */
object SupportFactory {
    const val INDEX_DIR = "rag/index-support"

    class Wiring(val assistant: SupportAssistant, val store: SupportStore)

    suspend fun create(
        repoRoot: File,
        apiKey: String,
        model: String = GeminiTextGenerator.DEFAULT_MODEL,
        reindex: Boolean = false,
        log: (String) -> Unit = {},
    ): Wiring {
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set (env var or local.properties)." }
        val store = SupportStore.load(File(repoRoot, SupportStore.DEFAULT_DIR))
        val docs = SupportKb.load(File(repoRoot, SupportKb.DEFAULT_DIR))
        require(docs.isNotEmpty()) { "The support knowledge base is empty." }
        val embedder = DocsIndex.embedderFor(apiKey)
        val index = DocsIndex(File(repoRoot, INDEX_DIR), embedder, label = "support-kb").ensure(docs, reindex, log)
        val assistant = SupportAssistant(VectorRetriever(embedder, index), GeminiTextGenerator(apiKey, model)) { ticketId -> SupportMcpGateway(store, ticketId) }
        return Wiring(assistant, store)
    }
}

/** Plain-text rendering for the terminal. */
fun SupportAnswer.render(): String = buildString {
    appendLine(if (usedTicket) "Ticket ${ticketId}: " + question else "Question (no ticket context): $question")
    appendLine()
    appendLine(answer)
    if (reply.isNotBlank()) { appendLine(); appendLine("Draft reply to the customer:"); appendLine(reply.prependIndent("  ")) }
    appendLine()
    appendLine(if (needsEscalation) "ESCALATE to a human" + escalationReason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() else "No escalation needed")
    if (crmCalls.isNotEmpty()) {
        appendLine()
        appendLine("CRM (MCP): " + crmCalls.joinToString(", ") { it.tool + if (it.isError) " [error]" else "" })
    }
    if (structured.sources.isNotEmpty()) {
        appendLine()
        appendLine("Sources:")
        structured.sources.distinctBy { it.file to it.section }.forEach { appendLine("  - ${it.file}${if (it.section.isNotBlank()) " > ${it.section}" else ""}") }
    }
}.trimEnd()
