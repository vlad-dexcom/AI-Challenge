package com.example.rag

import java.io.File
import java.time.Instant

/** Reads markdown (`.md`) files from a directory into [Document]s, in a stable (sorted) order. */
object CorpusLoader {
    fun load(dir: File): List<Document> {
        require(dir.isDirectory) { "Corpus directory not found: ${dir.path}" }
        return dir.walkTopDown().filter { it.isFile && it.extension == "md" }
            .sortedBy { it.relativeTo(dir).path }
            .map { f ->
                val text = f.readText().replace("\r\n", "\n")
                val source = f.relativeTo(dir).path
                Document(source, titleOf(text) ?: f.nameWithoutExtension, text)
            }.toList()
    }

    private fun titleOf(text: String) = findHeadings(text).firstOrNull { it.level == 1 }?.text
}

/** Pipeline: documents -> chunks -> embeddings -> [VectorIndex]. */
class Indexer(
    private val embedder: EmbeddingClient,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun build(
        documents: List<Document>,
        chunker: Chunker,
        strategyParams: String,
        sourceCorpus: String,
    ): VectorIndex {
        val chunks = documents.flatMap { chunker.chunk(it) }
        val vectors = embedder.embed(chunks.map { it.text }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        check(vectors.size == chunks.size) { "Embedder returned ${vectors.size} vectors for ${chunks.size} chunks" }
        val meta = IndexMeta(
            embeddingModel = embedder.modelName,
            dimension = embedder.dimension,
            strategy = chunker.strategy,
            strategyParams = strategyParams,
            createdAt = now().toString(),
            sourceCorpus = sourceCorpus,
            documentCount = documents.size,
            chunkCount = chunks.size,
        )
        return VectorIndex(meta, chunks.zip(vectors) { c, v -> IndexedChunk(c, v) })
    }
}
