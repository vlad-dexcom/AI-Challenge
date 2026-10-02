package com.example.rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Content-addressed file cache (key -> text). Lives in a git-ignored folder; [dir] = null disables it. */
class DiskCache(private val dir: File?) {
    fun get(key: String): String? = file(key)?.takeIf { it.isFile }?.readText()

    fun put(key: String, value: String) {
        val f = file(key) ?: return
        f.parentFile.mkdirs()
        f.writeText(value)
    }

    private fun file(key: String): File? = dir?.let { File(it, sha256(key) + ".json") }

    companion object {
        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Caches query embeddings (RETRIEVAL_QUERY only is worth it, but any task type is keyed correctly) so sweeps cost nothing after the first run. */
class CachedEmbeddingClient(private val inner: EmbeddingClient, private val cache: DiskCache) : EmbeddingClient {
    override val modelName: String get() = inner.modelName
    override val dimension: Int get() = inner.dimension

    override suspend fun embed(texts: List<String>, taskType: EmbeddingTaskType): List<FloatArray> {
        val keys = texts.map { "$modelName|$taskType|$it" }
        val cached = keys.map { k -> cache.get(k)?.let { v -> v.split(',').map(String::toFloat).toFloatArray() } }
        val missing = texts.indices.filter { cached[it] == null }
        val fresh = if (missing.isEmpty()) emptyList() else inner.embed(missing.map { texts[it] }, taskType)
        missing.forEachIndexed { i, idx -> cache.put(keys[idx], fresh[i].joinToString(",")) }
        val byIndex = missing.zip(fresh).toMap()
        return texts.indices.map { cached[it] ?: byIndex.getValue(it) }
    }
}

/** LLM calls and wall-clock time spent in them. A cached call adds the latency recorded when it was first made. */
class LlmUsage {
    private val c = AtomicInteger()
    private val ms = AtomicLong()
    val calls: Int get() = c.get()
    val millis: Long get() = ms.get()
    fun add(latencyMs: Long) { c.incrementAndGet(); ms.addAndGet(latencyMs) }
    fun reset() { c.set(0); ms.set(0) }
}

/**
 * Wraps a [TextGenerator]: counts calls/latency into [usage] and optionally caches successful replies on disk.
 * [label] (model + settings) is part of the cache key; the prompt, system prompt and options are too.
 */
class CachedTextGenerator(
    private val inner: TextGenerator,
    private val label: String,
    private val usage: LlmUsage = LlmUsage(),
    private val cache: DiskCache = DiskCache(null),
    private val clock: () -> Long = System::nanoTime,
) : TextGenerator {
    @Serializable private data class Entry(val latencyMs: Long, val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        call(systemInstruction, prompt, GenerationOptions()) { inner.generate(systemInstruction, prompt) }

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> =
        call(systemInstruction, prompt, options) { inner.generate(systemInstruction, prompt, options) }

    private suspend fun call(system: String?, prompt: String, options: GenerationOptions, run: suspend () -> Result<String>): Result<String> {
        val key = "$label|${options.temperature}|${options.json}|${system.orEmpty()}|$prompt"
        cache.get(key)?.let { raw ->
            runCatching { json.decodeFromString(Entry.serializer(), raw) }.getOrNull()?.let {
                usage.add(it.latencyMs)
                return Result.success(it.text)
            }
        }
        val start = clock()
        val result = run()
        val ms = (clock() - start) / 1_000_000
        usage.add(ms)
        result.getOrNull()?.let { cache.put(key, json.encodeToString(Entry.serializer(), Entry(ms, it))) }
        return result
    }
}

enum class Verdict { CORRECT, PARTIAL, WRONG }

@Serializable
data class JudgeResult(val verdict: Verdict, val reason: String)

/**
 * LLM-as-judge for the Day 22 rubric: CORRECT = matches the reference / behaves correctly, PARTIAL = generic or only some facts,
 * WRONG = contradicts or invents specifics instead of the reference. Temperature 0. The verdict is LLM-judged (same model family
 * as the answerer, one prompt) - treat it as a noisy second opinion next to the substring facts and manual review, not as ground truth.
 */
class Judge(private val generator: TextGenerator) {
    suspend fun judge(question: String, answer: String, referenceFacts: List<List<String>>, outOfCorpus: Boolean): Result<JudgeResult> {
        val reference = if (outOfCorpus) {
            "The knowledge base has NO answer to this question. A correct answer clearly says the information is not available / not covered " +
                "(or that it is unknown) and does not state invented specifics as fact. Confident specifics = WRONG."
        } else {
            "Reference facts the answer should contain (each line is a fact; alternatives separated by ' / '):\n" +
                referenceFacts.joinToString("\n") { "- " + it.joinToString(" / ") } +
                "\nCORRECT = all facts present and nothing contradicting; PARTIAL = generic or only some facts; WRONG = different/invented content."
        }
        val prompt = "Question: $question\n\n$reference\n\nAnswer to grade:\n$answer\n\n" +
            "Reply with JSON only: {\"verdict\":\"correct|partial|wrong\",\"reason\":\"<one short sentence>\"}"
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0, json = true)).getOrElse { return Result.failure(it) }
        return parse(reply)
    }

    companion object {
        const val SYSTEM = "You are a strict grader of answers about strength training. Judge only against the given reference."

        fun parse(reply: String): Result<JudgeResult> {
            val verdict = Regex("\"verdict\"\\s*:\\s*\"(correct|partial|wrong)\"", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)
                ?: return Result.failure(IllegalArgumentException("Unparsable judge reply: ${reply.take(100)}"))
            val reason = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(reply)?.groupValues?.get(1).orEmpty()
            return Result.success(JudgeResult(Verdict.valueOf(verdict.uppercase()), reason))
        }
    }
}
