package com.example.rag

import com.example.core.llm.GenerationOptions
import com.example.core.llm.TextGenerator

fun chunk(id: String, source: String, section: String, text: String) =
    Chunk(id, source, source, section, text, 0, text.length, ChunkStrategy.STRUCTURE)

fun hit(id: String, score: Float, source: String = "$id.md", section: String = "", text: String = "text of $id") =
    SearchHit(chunk(id, source, section, text), score)

/** Records prompts and replies from a script; fails the test if called more often than scripted. */
class ScriptedGenerator(private val replies: MutableList<Result<String>>) : TextGenerator {
    val calls = mutableListOf<Triple<String?, String, GenerationOptions>>()
    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        generate(systemInstruction, prompt, GenerationOptions())

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
        calls += Triple(systemInstruction, prompt, options)
        return if (replies.isEmpty()) Result.success("answer") else replies.removeAt(0)
    }
}
