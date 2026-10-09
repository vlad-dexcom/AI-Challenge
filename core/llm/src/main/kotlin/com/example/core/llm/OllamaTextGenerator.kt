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
 *  - Some runners (e.g. MLX builds) answer HTTP 501 "structured output is unavailable" to `format: "json"`; the generator then
 *    drops the format for that model and relies on the prompt's JSON instruction (the reply is still fence-stripped).
 *  - Transient failures (connection reset, 5xx) are retried up to [maxRetries] times; a model that is not pulled or a bad request is not.
 */
class OllamaTextGenerator(
    private val model: String = OllamaChatClient.DEFAULT_MODEL,
    private val baseUrl: String = OllamaChatClient.DEFAULT_URL,
    private val temperature: Double = 0.2,
    private val numCtx: Int = DEFAULT_NUM_CTX,
    private val think: Boolean? = false,
    private val maxRetries: Int = 2,
    engine: HttpClientEngine = defaultHttpEngine(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : TextGenerator {
    companion object {
        const val DEFAULT_NUM_CTX = 16_384
        private val FENCE = Regex("^```(?:json)?\\s*(.*?)\\s*```$", RegexOption.DOT_MATCHES_ALL)
    }

    private val client = OllamaChatClient(baseUrl, model, engine)

    @Volatile private var jsonFormatSupported = true

    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> =
        generate(systemInstruction, prompt, GenerationOptions())

    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> {
        val messages = listOfNotNull(systemInstruction?.let { OllamaMessage("system", it) }, OllamaMessage("user", prompt))
        var attempt = 0
        while (true) {
            val result = client.chat(messages, model, options.temperature ?: temperature, think, options.json && jsonFormatSupported, numCtx)
            val failure = result.exceptionOrNull() ?: return result.map { clean(it.content, options.json) }
            if (options.json && jsonFormatSupported && failure.message.orEmpty().contains("structured output is unavailable")) {
                jsonFormatSupported = false
                continue
            }
            if (!retryable(failure) || attempt >= maxRetries) return Result.failure(failure)
            sleep(500L shl attempt++)
        }
    }

    private fun clean(text: String, json: Boolean): String =
        if (json) FENCE.find(text.trim())?.groupValues?.get(1) ?: text.trim() else text.trim()

    private fun retryable(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        if (m.startsWith("Ollama HTTP ")) return m.removePrefix("Ollama HTTP ").takeWhile { it.isDigit() }.toIntOrNull()?.let { it >= 500 } == true
        return !m.contains("empty answer")
    }
}
