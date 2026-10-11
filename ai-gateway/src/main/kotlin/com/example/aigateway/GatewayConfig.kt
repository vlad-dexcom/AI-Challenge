package com.example.aigateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class KeyEntry(val name: String, val sha256: String)

@Serializable
data class Limits(
    val maxBodyBytes: Int = 64 * 1024,
    val maxMessages: Int = 40,
    /** Server-side ceiling for `max_tokens`; larger requests are clamped, not rejected. */
    val maxTokensCap: Int = 1024,
    val defaultMaxTokens: Int = 512,
    val rateLimitPerMinute: Int = 30,
    val rateBurst: Int = 10,
    /** Simultaneous generations; keep equal to OLLAMA_NUM_PARALLEL. */
    val maxConcurrent: Int = 1,
    val queueSize: Int = 4,
    val queueTimeoutMs: Long = 30_000,
    val retryAfterSeconds: Int = 5,
    val maxRequestsPerKey: Int = 2,
    val requestTimeoutMs: Long = 300_000,
    val streamIdleTimeoutMs: Long = 60_000,
    val keepaliveMs: Long = 10_000,
)

@Serializable
data class GatewayConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8088,
    val upstreamUrl: String = "http://127.0.0.1:11434",
    /** Allow-list; the first entry is the default when a request names no model. */
    val models: List<String> = listOf("gemma4:26b-a4b-it-qat"),
    val numCtx: Int = 8192,
    /** Reasoning models (Gemma 4) "think" by default: the thinking tokens eat `max_tokens` and a chat client sees an empty answer. Off unless configured. */
    val think: Boolean? = false,
    val keys: List<KeyEntry> = emptyList(),
    val limits: Limits = Limits(),
    /** Retrieval-augmented chat (`POST /v1/rag/chat`); off unless enabled. */
    val rag: RagSettings = RagSettings(),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun load(path: String?, env: Map<String, String> = System.getenv()): GatewayConfig {
            val file = (path ?: env["GATEWAY_CONFIG"])?.let(::File)
            var c = if (file != null) json.decodeFromString(serializer(), file.readText()) else GatewayConfig()
            env["GATEWAY_UPSTREAM_URL"]?.let { c = c.copy(upstreamUrl = it) }
            env["GATEWAY_MODELS"]?.let { v -> c = c.copy(models = v.split(',').map(String::trim).filter(String::isNotEmpty)) }
            env["GATEWAY_NUM_CTX"]?.toIntOrNull()?.let { c = c.copy(numCtx = it) }
            env["GATEWAY_HOST"]?.let { c = c.copy(host = it) }
            env["GATEWAY_PORT"]?.toIntOrNull()?.let { c = c.copy(port = it) }
            env["GATEWAY_MAX_CONCURRENT"]?.toIntOrNull()?.let { c = c.copy(limits = c.limits.copy(maxConcurrent = it)) }
            return c.also { it.validate() }
        }
    }

    fun validate() {
        require(models.isNotEmpty()) { "models must not be empty" }
        require(numCtx > 0 && port in 0..65535) { "numCtx/port out of range" }
        require(limits.maxConcurrent >= 1 && limits.queueSize >= 0 && limits.maxRequestsPerKey >= 1) { "invalid concurrency limits" }
        require(limits.rateLimitPerMinute >= 1 && limits.rateBurst >= 1) { "invalid rate limit" }
        require(limits.defaultMaxTokens <= limits.maxTokensCap) { "defaultMaxTokens must not exceed maxTokensCap" }
        require(limits.maxTokensCap < numCtx) { "maxTokensCap must be smaller than numCtx" }
    }
}
