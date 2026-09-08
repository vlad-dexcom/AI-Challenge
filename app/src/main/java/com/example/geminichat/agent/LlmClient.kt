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
}
