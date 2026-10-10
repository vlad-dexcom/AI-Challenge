package com.example.core.llm

import com.example.core.platform.defaultHttpEngine
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.delay

/**
 * [TextGenerator] over a local Ollama server (`/api/chat`): no key, no cloud.
 *
 *  - Thinking is off by default: chain-of-thought text would break the JSON contracts (memory, citations) and slow every call.
 *  - [GenerationOptions.json] maps to Ollama's `format: "json"`; stray ```json fences are stripped anyway.
 *  - [numCtx] sets the context window per call (Ollama's own default is small and would silently truncate long RAG prompts).
 *  - [tuning] overrides sampling, `num_ctx`, `num_predict` and `keep_alive` globally or per [CallPurpose]; [stats] collects token counts and timings per purpose.
 *  - Some runners (e.g. MLX builds) answer HTTP 501 "structured output is unavailable" to `format: "json"`; the generator then
 *    drops the format for that model and relies on the prompt's JSON instruction (the reply is still fence-stripped).
 *  - At temperature 0 a model can fall into a loop and Ollama aborts with HTTP 500 "token repeat limit reached"; an identical retry would
 *    loop again, so the call is repeated once with a little randomness (temperature 0.3, repeat_penalty 1.1).
 *  - Transient failures (connection reset, 5xx) are retried up to [maxRetries] times; a model that is not pulled or a bad request is not.
 */
class OllamaTextGenerator(
    private val model: String = OllamaChatClient.DEFAULT_MODEL,
    private val baseUrl: String = OllamaChatClient.DEFAULT_URL,
    private val temperature: Double = 0.2,
    private val numCtx: Int = DEFAULT_NUM_CTX,
    private val think: Boolean? = false,
    private val maxRetries: Int = 2,
    private val tuning: OllamaTuning = OllamaTuning.DEFAULT,
    private val stats: LlmCallStats? = null,
    engine: HttpClientEngine = defaultHttpEngine(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : TextGenerator {
    companion object {
        const val DEFAULT_NUM_CTX = 16_384
        private const val UNLOOP_TEMPERATURE = 0.3
        private const val UNLOOP_REPEAT_PENALTY = 1.1
        private val FENCE = Regex("^```(?:json)?\\s*(.*?)\\s*```$", RegexOption.DOT_MATCHES_ALL)
    }

    private val client = OllamaChatClient(baseUrl, model, engine)

    @Volatile private var jsonFormatSupported = true

    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        generate(systemInstruction, prompt, GenerationOptions())

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
        val messages = listOfNotNull(systemInstruction?.let { OllamaMessage("system", it) }, OllamaMessage("user", prompt))
        var attempt = 0
        var unloop = false
        while (true) {
            val tuned = tuning.resolve(options.purpose)
            val result = client.chat(
                messages, model, if (unloop) maxOf(temperatureFor(options), UNLOOP_TEMPERATURE) else temperatureFor(options), think,
                options.json && jsonFormatSupported, tuned.numCtx ?: numCtx, tuned.maxTokens,
                tuned.topP, tuned.topK, if (unloop) maxOf(tuned.repeatPenalty ?: 0.0, UNLOOP_REPEAT_PENALTY) else tuned.repeatPenalty, tuned.keepAlive,
            )
            result.getOrNull()?.let { r ->
                stats?.record(LlmCall(options.purpose, r.promptTokens, r.completionTokens, r.promptEvalMillis, r.evalMillis, r.totalMillis, r.loadMillis))
            }
            val failure = result.exceptionOrNull() ?: return result.map { clean(it.content, options.json) }
            if (options.json && jsonFormatSupported && failure.message.orEmpty().contains("structured output is unavailable")) {
                jsonFormatSupported = false
                continue
            }
            if (!unloop && failure.message.orEmpty().contains("token repeat limit")) {
                unloop = true
                continue
            }
            if (!retryable(failure) || attempt >= maxRetries) return Result.failure(failure)
            sleep(500L shl attempt++)
        }
    }

    /** A per-purpose temperature always wins; otherwise the caller's, then the tuning default, then the constructor default. */
    private fun temperatureFor(options: GenerationOptions): Double =
        tuning.byPurpose[options.purpose]?.temperature ?: options.temperature ?: tuning.base.temperature ?: temperature

    private fun clean(text: String, json: Boolean): String =
        if (json) FENCE.find(text.trim())?.groupValues?.get(1) ?: text.trim() else text.trim()

    private fun retryable(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        if (m.startsWith("Ollama HTTP ")) return m.removePrefix("Ollama HTTP ").takeWhile { it.isDigit() }.toIntOrNull()?.let { it >= 500 } == true
        return !m.contains("empty answer")
    }
}
