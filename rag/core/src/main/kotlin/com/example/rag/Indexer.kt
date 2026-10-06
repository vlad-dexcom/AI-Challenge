package com.example.rag

import com.example.core.io.extension
import com.example.core.io.isFile
import com.example.core.io.readText
import com.example.core.io.relativeTo
import com.example.core.io.isDirectory
import com.example.core.io.walkFiles
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Instant
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.EmbeddingTaskType

/** Reads markdown (`.md`) files from a directory into [Document]s, in a stable (sorted) order. */
object CorpusLoader {
    fun load(dir: Path): List<Document> {
        require(dir.isDirectory()) { "Corpus directory not found: ${dir.toString()}" }
        return dir.walkFiles().filter { it.extension == "md" }
            .sortedBy { it.relativeTo(dir) }
            .map { f ->
                val text = f.readText().replace("\r\n", "\n")
                val source = f.relativeTo(dir)
                Document(source, titleOf(text) ?: f.name.substringBeforeLast('.'), text)
            }
    }

    private fun titleOf(text: String) = findHeadings(text).firstOrNull { it.level == 1 }?.text
}

/** Pipeline: documents -> chunks -> embeddings -> [VectorIndex]. */
class Indexer(
    private val embedder: EmbeddingClient,
    private val now: () -> Instant = { Clock.System.now() },
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
