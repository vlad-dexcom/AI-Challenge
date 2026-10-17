package com.example.devassistant

import com.example.core.llm.GeminiTextGenerator
import com.example.rag.Retriever
import com.example.rag.VectorRetriever
import java.io.File

/** Wires [HelpAssistant] to the project repository: docs index + Gemini generation + git MCP. Shared by the CLI and the web console. */
object DevAssistant {
    suspend fun create(
        repoRoot: File,
        apiKey: String,
        model: String = GeminiTextGenerator.DEFAULT_MODEL,
        indexDir: File = File(repoRoot, DocsIndex.DEFAULT_DIR),
        includeHistory: Boolean = false,
        reindex: Boolean = false,
        log: (String) -> Unit = {},
    ): HelpAssistant {
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set (env var or local.properties)." }
        val docs = ProjectDocs.load(repoRoot, includeHistory)
        require(docs.isNotEmpty()) { "No documentation found in ${repoRoot.path} (README.md, AGENTS.md, docs/*.md)." }
        val embedder = DocsIndex.embedderFor(apiKey)
        val index = DocsIndex(indexDir, embedder).ensure(docs, reindex, log)
        val retriever: Retriever = VectorRetriever(embedder, index)
        return HelpAssistant(retriever, GeminiTextGenerator(apiKey, model), GitMcpGateway(ProcessGitRunner(repoRoot)))
    }
}

/** Plain-text rendering of an answer for the terminal: answer, MCP calls, then the cited documentation. */
fun HelpAnswer.render(): String = buildString {
    appendLine(answer)
    val live = mcpCalls
    if (live.isNotEmpty()) {
        appendLine()
        appendLine("MCP (git):")
        live.forEach { c -> appendLine("  ${c.tool}${if (c.isError) " [error]" else ""}: ${c.text.lineSequence().firstOrNull().orEmpty()}") }
    }
    if (structured.sources.isNotEmpty()) {
        appendLine()
        appendLine("Sources:")
        structured.sources.distinctBy { it.file to it.section }.forEach { s -> appendLine("  - ${s.file}${if (s.section.isNotBlank()) " > ${s.section}" else ""}") }
    }
}.trimEnd()
