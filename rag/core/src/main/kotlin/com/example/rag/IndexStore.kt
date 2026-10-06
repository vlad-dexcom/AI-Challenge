package com.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.core.io.readText
import com.example.core.io.writeText
import kotlinx.io.files.Path
import kotlin.math.round

/**
 * Persists a [VectorIndex] as one JSON file: `{ "meta": {...}, "chunks": [ { chunk fields..., "embedding": [...] } ] }`.
 * Vector components are rounded to 5 decimals to keep files small (cosine ranking is unaffected).
 */
class IndexStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Serializable
    private class StoredChunk(
        val chunkId: String, val source: String, val title: String, val section: String,
        val startOffset: Int, val endOffset: Int, val strategy: ChunkStrategy,
        val text: String, val embedding: List<Float>,
    )

    @Serializable
    private class StoredIndex(val meta: IndexMeta, val chunks: List<StoredChunk>)

    fun save(index: VectorIndex, file: Path) {
        val stored = StoredIndex(index.meta, index.entries.map { e ->
            val c = e.chunk
            StoredChunk(c.chunkId, c.source, c.title, c.section, c.startOffset, c.endOffset, c.strategy, c.text,
                e.embedding.map { (round(it * 100_000f) / 100_000f) })
        })
        file.writeText(json.encodeToString(StoredIndex.serializer(), stored))
    }

    fun load(file: Path): VectorIndex = parse(file.readText())

    /** Parses index JSON from any source (e.g. an Android asset stream). */
    fun parse(text: String): VectorIndex {
        val stored = json.decodeFromString(StoredIndex.serializer(), text)
        return VectorIndex(stored.meta, stored.chunks.map {
            IndexedChunk(
                Chunk(it.chunkId, it.source, it.title, it.section, it.text, it.startOffset, it.endOffset, it.strategy),
                it.embedding.toFloatArray(),
            )
        })
    }
}
