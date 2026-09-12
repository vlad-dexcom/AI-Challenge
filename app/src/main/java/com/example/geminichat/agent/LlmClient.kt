package com.example.geminichat.agent

/**
 * Transport-agnostic spec for a single completion call. [LlmAgent] builds this from an
 * [AgentConfig] + [AgentRequest]; [LlmClient] implementations (e.g.
 * [com.example.geminichat.GeminiApiClient]) turn it into an actual HTTP request.
 */
data class LlmRequestSpec(
    val model: String,
    val input: String,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null
)

/**
 * Abstraction over "call an LLM and get text back". Keeping this separate from [Agent] means
 * agent logic (prompt assembly, error mapping, response validation) can be unit-tested against
 * a fake client, with no network or Android dependency.
 */
interface LlmClient {
    suspend fun complete(spec: LlmRequestSpec): Result<String>

    /**
     * Approximate context window (in tokens) for [model], used by [LlmAgent] to refuse an
     * over-budget request *before* spending an API call (see [ContextWindowExceededException]).
     * Implementations backed by a real provider (e.g. `GeminiApiClient`) should override this
     * with published per-model limits; the default is a conservative placeholder for clients
     * (fakes, tests) that don't care about a specific model's real window.
     */
    fun contextWindowTokens(model: String): Int = DEFAULT_CONTEXT_WINDOW_TOKENS

    companion object {
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 1_000_000
    }
}

/**
 * Thrown (as a [Result.failure]) by [LlmAgent.handle] when the estimated prompt token count
 * plus the reserved output budget would exceed [LlmClient.contextWindowTokens] for the target
 * model. This is a *preemptive* failure: [LlmAgent] never sends the oversized request, so the
 * failure mode for an overflowing conversation is a clear, immediate error instead of a
 * truncated/garbled call to the model.
 */
class ContextWindowExceededException(
    val promptTokens: Int,
    val reservedOutputTokens: Int,
    val contextWindowTokens: Int
) : Exception(
    "This conversation is too long for the model's context window: the prompt needs " +
        "~$promptTokens tokens and $reservedOutputTokens are reserved for the reply " +
        "(total ~${promptTokens + reservedOutputTokens}), but the model only supports " +
        "$contextWindowTokens tokens. Start a new chat or trim the history."
)
