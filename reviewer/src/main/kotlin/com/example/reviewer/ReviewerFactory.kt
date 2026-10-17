package com.example.reviewer

import com.example.core.llm.GeminiTextGenerator
import com.example.devassistant.DocsIndex
import com.example.devassistant.GitMcpGateway
import com.example.devassistant.GitRunner
import com.example.devassistant.ProcessGitRunner
import com.example.devassistant.ProjectDocs
import com.example.rag.Document
import com.example.rag.Retriever
import com.example.rag.VectorRetriever
import java.io.File

/** Day 32: tracked Kotlin sources (no tests, no build output) as a RAG corpus, so the reviewer can see how the code around a change looks. */
object CodeCorpus {
    private const val MAX_FILE_BYTES = 200_000

    fun load(root: File, git: GitRunner): List<Document> =
        git.run(listOf("ls-files", "--", "*.kt")).lines().map(String::trim)
            .filter { it.isNotEmpty() && "/src/test/" !in it && "/src/androidTest/" !in it && "/build/" !in it }
            .sorted()
            .mapNotNull { path ->
                val f = File(root, path)
                if (!f.isFile || f.length() > MAX_FILE_BYTES) null
                else Document(path, f.name, f.readText().replace("\r\n", "\n"))
            }
}

/** Wires a [Reviewer] to a repository: docs index + optional code index + Gemini + the read-only git MCP server. */
object ReviewerFactory {
    const val CODE_INDEX_DIR = "rag/index-code"

    suspend fun create(
        repoRoot: File,
        apiKey: String,
        model: String = GeminiTextGenerator.DEFAULT_MODEL,
        config: ReviewConfig = ReviewConfig(),
        useCode: Boolean = true,
        reindex: Boolean = false,
        log: (String) -> Unit = {},
    ): Reviewer {
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set (env var or local.properties)." }
        val docs = ProjectDocs.load(repoRoot)
        require(docs.isNotEmpty()) { "No documentation found in ${repoRoot.path} (README.md, AGENTS.md, docs/*.md)." }
        val embedder = DocsIndex.embedderFor(apiKey)
        val git = ProcessGitRunner(repoRoot)

        val docsIndex = DocsIndex(File(repoRoot, DocsIndex.DEFAULT_DIR), embedder).ensure(docs, reindex, log)
        // The repository rules (AGENTS.md) are short and apply to every change, so they are always part of the prompt.
        val pinned = docsIndex.chunks.filter { it.source == "AGENTS.md" }
        val codeRetriever: Retriever? = if (!useCode) null else {
            val sources = CodeCorpus.load(repoRoot, git)
            val index = DocsIndex(File(repoRoot, CODE_INDEX_DIR), embedder, label = "project-code", cacheEmbeddings = true).ensure(sources, reindex, log)
            VectorRetriever(embedder, index)
        }
        return Reviewer(
            git = GitMcpGateway(git),
            generator = GeminiTextGenerator(apiKey, model),
            docs = VectorRetriever(embedder, docsIndex),
            code = codeRetriever,
            pinned = pinned,
            config = config,
            model = model,
        )
    }
}
