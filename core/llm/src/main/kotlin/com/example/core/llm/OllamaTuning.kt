package com.example.core.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sampling and limit overrides for Ollama calls; null = leave to the caller / the model's own defaults. */
data class OllamaParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val repeatPenalty: Double? = null,
    val numCtx: Int? = null,
    val maxTokens: Int? = null,
    val keepAlive: String? = null,
) {
    /** Values set here win, the rest come from [base]. */
    fun over(base: OllamaParams) = OllamaParams(
        temperature ?: base.temperature, topP ?: base.topP, topK ?: base.topK, repeatPenalty ?: base.repeatPenalty,
        numCtx ?: base.numCtx, maxTokens ?: base.maxTokens, keepAlive ?: base.keepAlive,
    )

    fun withField(name: String, value: String): OllamaParams = when (name) {
        "temperature" -> copy(temperature = value.toDouble())
        "topP" -> copy(topP = value.toDouble())
        "topK" -> copy(topK = value.toInt())
        "repeatPenalty" -> copy(repeatPenalty = value.toDouble())
        "numCtx" -> copy(numCtx = value.toInt())
        "maxTokens" -> copy(maxTokens = value.toInt())
        "keepAlive" -> copy(keepAlive = value)
        else -> throw IllegalArgumentException("Unknown tuning parameter '$name' (use temperature, topP, topK, repeatPenalty, numCtx, maxTokens, keepAlive)")
    }
}

/**
 * Tuning of the local model: [base] applies to every call, [byPurpose] overrides it for one kind of call (rewrite, cited answer, memory, ...).
 * `base.temperature` is only a default for calls that do not choose a temperature themselves (the RAG stages pass 0); a per-purpose temperature always wins.
 *
 * Text form (flag `--ollama-tuning` / env `OLLAMA_TUNING`): `numCtx=8192,keepAlive=30m,rewrite.maxTokens=96,cited.temperature=0`
 * where the optional prefix is a [CallPurpose.key]; `none` means no overrides.
 */
data class OllamaTuning(val base: OllamaParams = OllamaParams(), val byPurpose: Map<CallPurpose, OllamaParams> = emptyMap()) {
    /** Effective parameters for a call: per-purpose values, then the base ones. */
    fun resolve(purpose: CallPurpose): OllamaParams = (byPurpose[purpose] ?: OllamaParams()).over(base)

    companion object {
        val DEFAULT = OllamaTuning()

        /**
         * Day 29 result for the RAG assistant on a 36 GB Mac: prompts measured at 0.1-2.1K tokens (single-shot and chat with memory), so an 8K window
         * leaves headroom without cost (context size did not change memory or speed); rewrite is one short line; cited answers peaked at 680 tokens.
         */
        val RECOMMENDED = parse("numCtx=8192,rewrite.maxTokens=64,cited.maxTokens=1024,repair.maxTokens=1024")

        fun parse(spec: String?): OllamaTuning {
            if (spec.isNullOrBlank() || spec.trim().equals("none", ignoreCase = true)) return DEFAULT
            var base = OllamaParams()
            val per = mutableMapOf<CallPurpose, OllamaParams>()
            for (item in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val (k, v) = item.split('=', limit = 2).also { require(it.size == 2) { "Bad tuning item '$item' (expected name=value)" } }
                val parts = k.trim().split('.', limit = 2)
                if (parts.size == 1) base = base.withField(parts[0], v.trim())
                else {
                    val purpose = CallPurpose.fromKey(parts[0]) ?: throw IllegalArgumentException("Unknown call purpose '${parts[0]}' (use ${CallPurpose.entries.joinToString { it.key }})")
                    per[purpose] = (per[purpose] ?: OllamaParams()).withField(parts[1], v.trim())
                }
            }
            return OllamaTuning(base, per)
        }
    }
}

/** One finished Ollama call, with the counters Ollama reports (token counts and timings in milliseconds). */
data class LlmCall(
    val purpose: CallPurpose,
    val promptTokens: Int,
    val completionTokens: Int,
    val promptEvalMillis: Long,
    val evalMillis: Long,
    val totalMillis: Long,
    val loadMillis: Long,
)

/** Aggregate of the calls of one [CallPurpose]; throughput is total tokens over total evaluation time. */
data class PurposeStats(
    val purpose: CallPurpose,
    val calls: Int,
    val promptTokensAvg: Double,
    val promptTokensP90: Int,
    val promptTokensMax: Int,
    val completionTokensAvg: Double,
    val completionTokensP90: Int,
    val completionTokensMax: Int,
    val generationTokPerSec: Double,
    val promptTokPerSec: Double,
    val totalMillisAvg: Double,
    val loadMillisTotal: Long,
)

/** Thread-safe collector of [LlmCall]s, summarised per purpose; used to size context windows and token limits from real data. */
class LlmCallStats {
    private val lock = Mutex()
    private val calls = mutableListOf<LlmCall>()

    suspend fun record(call: LlmCall) = lock.withLock { calls += call }

    suspend fun reset() = lock.withLock { calls.clear() }

    suspend fun summary(): List<PurposeStats> = lock.withLock { calls.toList() }.groupBy { it.purpose }.map { (purpose, cs) ->
        fun p90(v: List<Int>) = v.sorted().let { it[((it.size * 9 + 9) / 10 - 1).coerceIn(0, it.size - 1)] }
        val prompt = cs.map { it.promptTokens }
        val completion = cs.map { it.completionTokens }
        val evalMs = cs.sumOf { it.evalMillis }
        val promptMs = cs.sumOf { it.promptEvalMillis }
        PurposeStats(
            purpose, cs.size, prompt.average(), p90(prompt), prompt.max(), completion.average(), p90(completion), completion.max(),
            if (evalMs > 0) completion.sum() * 1000.0 / evalMs else 0.0,
            if (promptMs > 0) prompt.sum() * 1000.0 / promptMs else 0.0,
            cs.map { it.totalMillis }.average(), cs.sumOf { it.loadMillis },
        )
    }.sortedBy { it.purpose.ordinal }
}
