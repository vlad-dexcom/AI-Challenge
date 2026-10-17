package com.example.devassistant

import com.example.core.llm.EmbeddingClient
import com.example.core.llm.GeminiEmbeddingClient
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.platform.toKxPath
import com.example.rag.DiskCache
import com.example.rag.Document
import com.example.rag.IndexStore
import com.example.rag.Indexer
import com.example.rag.StructureChunker
import com.example.rag.VectorIndex
import java.io.File

/** Day 31: the developer-facing knowledge base: README, AGENTS.md and the markdown files in `docs/` (day notes only on request). */
object ProjectDocs {
    fun load(root: File, includeHistory: Boolean = false): List<Document> {
        val files = buildList {
            listOf("README.md", "AGENTS.md").map { File(root, it) }.filter { it.isFile }.forEach(::add)
            File(root, "docs").listFiles { f -> f.isFile && f.extension == "md" }.orEmpty()
                .filter { includeHistory || !HISTORY.matches(it.name) }
                .sortedBy { it.name }.forEach(::add)
        }
        return files.map { f ->
            val text = f.readText().replace("\r\n", "\n")
            Document(f.relativeTo(root).path, titleOf(text) ?: f.nameWithoutExtension, text)
        }
    }

    /** Changes whenever any document changes, so a stale index is detected and rebuilt. */
    fun fingerprint(docs: List<Document>): String =
        DiskCache.sha256(docs.joinToString("\u0000") { "${it.source}\u0001${it.text}" }).take(16)

    private fun titleOf(text: String) = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()

    private val HISTORY = Regex("day\\d+.*\\.md")
}

/** Builds and caches the vector index of [ProjectDocs] in `<dir>/structure.json`. */
class DocsIndex(private val dir: File, private val embedder: EmbeddingClient, private val store: IndexStore = IndexStore()) {
    /** Loads the cached index if it matches the current documents and embedder, otherwise (re)builds it. */
    suspend fun ensure(docs: List<Document>, force: Boolean = false, log: (String) -> Unit = {}): VectorIndex {
        val file = File(dir, "structure.json")
        val corpus = "project-docs:${ProjectDocs.fingerprint(docs)}"
        if (!force && file.isFile) {
            val cached = runCatching { store.load(file.toKxPath()) }.getOrNull()
            if (cached != null && cached.meta.sourceCorpus == corpus && cached.meta.embeddingModel == embedder.modelName) return cached
        }
        log("Indexing ${docs.size} project documents with ${embedder.modelName}…")
        val index = Indexer(embedder).build(docs, StructureChunker(1500, 300), "max=1500, min=300", corpus)
        dir.mkdirs()
        store.save(index, file.toKxPath())
        log("Index: ${index.meta.chunkCount} chunks -> ${file.path}")
        return index
    }

    companion object {
        const val DEFAULT_DIR = "rag/index-docs"

        /** Gemini embeddings when a key is available, otherwise the offline hashing embedder (keyword-ish, no network). */
        fun embedderFor(apiKey: String): EmbeddingClient = if (apiKey.isNotBlank()) GeminiEmbeddingClient(apiKey) else HashingEmbeddingClient()
    }
}
