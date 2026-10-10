package com.example.rag

import com.example.core.llm.EmbedPrompts
import com.example.core.llm.EmbeddingClient
import com.example.core.llm.GeminiEmbeddingClient
import com.example.core.llm.GeminiTextGenerator
import com.example.core.llm.HashingEmbeddingClient
import com.example.core.llm.LlmCallStats
import com.example.core.llm.OllamaChatClient
import com.example.core.llm.OllamaEmbeddingClient
import com.example.core.llm.OllamaTextGenerator
import com.example.core.llm.OllamaTuning
import com.example.core.llm.TextGenerator

/**
 * Which LLM stack the tools run on: `gemini` (cloud) or `ollama` (fully local: generation and embeddings, no key, no internet).
 * Selected by `--provider` or the `LLM_PROVIDER` env var; Ollama is configured by `--ollama-url`/`OLLAMA_URL`,
 * `--ollama-model`/`OLLAMA_MODEL` (answers), `--ollama-embed-model`/`OLLAMA_EMBED_MODEL` (retrieval) and
 * `--ollama-tuning`/`OLLAMA_TUNING` (see [OllamaTuning]; default [OllamaTuning.RECOMMENDED], `none` = no overrides) and
 * `--prompt-profile`/`RAG_PROMPT_PROFILE` (see [PromptProfile]; default `local` for Ollama, or `default` / levers `rewrite,partial,lang,compact`).
 */
class LlmProvider(
    val name: String,
    val geminiKey: String,
    val ollamaUrl: String = OllamaChatClient.DEFAULT_URL,
    val ollamaModel: String = OllamaChatClient.DEFAULT_MODEL,
    val ollamaEmbedModel: String = DEFAULT_EMBED_MODEL,
    val tuning: OllamaTuning = OllamaTuning.DEFAULT,
    val stats: LlmCallStats = LlmCallStats(),
    localPrompts: PromptProfile = PromptProfile.DEFAULT,
) {
    /** Prompt wording for this provider: the cloud model always gets the original prompts, the local one the configured profile. */
    val promptProfile: PromptProfile = if (name == OLLAMA) localPrompts else PromptProfile.DEFAULT

    val isLocal: Boolean get() = name == OLLAMA
    val defaultModel: String get() = if (isLocal) ollamaModel else GeminiTextGenerator.DEFAULT_MODEL

    /** Gemini needs a key; Ollama needs nothing here (reachability is checked on the first call). */
    val available: Boolean get() = isLocal || geminiKey.isNotBlank()

    fun missingMessage(): String = "GEMINI_API_KEY is not set (env var or local.properties). Use --provider ollama to run fully locally."

    fun generator(model: String? = null): TextGenerator =
        if (isLocal) OllamaTextGenerator(model ?: ollamaModel, ollamaUrl, tuning = tuning, stats = stats) else GeminiTextGenerator(geminiKey, model ?: GeminiTextGenerator.DEFAULT_MODEL)

    /** The embedder the CLI uses when `--embedder` is not given. */
    val defaultEmbedderName: String get() = if (isLocal) "$OLLAMA_EMBEDDER_PREFIX$ollamaEmbedModel" else if (geminiKey.isNotBlank()) "gemini" else "offline"

    /** Rebuilds the embedder an index was made with from its metadata, or null if it needs a key that is missing. */
    fun embedderFor(embeddingModel: String, dimension: Int): EmbeddingClient? = when {
        embeddingModel.startsWith("offline-hashing-bow-") -> HashingEmbeddingClient(dimension)
        embeddingModel.startsWith(OLLAMA_EMBEDDER_PREFIX) -> {
            val model = embeddingModel.removePrefix(OLLAMA_EMBEDDER_PREFIX).removeSuffix("+prompts")
            val prompts = if (embeddingModel.endsWith("+prompts")) EmbedPrompts.defaultsFor(model) else EmbedPrompts.NONE
            OllamaEmbeddingClient(model, dimension, prompts, ollamaUrl)
        }
        !isLocal && geminiKey.isNotBlank() -> GeminiEmbeddingClient(geminiKey, embeddingModel, dimension)
        else -> null
    }

    companion object {
        const val OLLAMA = "ollama"
        const val GEMINI = "gemini"
        const val OLLAMA_EMBEDDER_PREFIX = "ollama:"
        const val DEFAULT_EMBED_MODEL = "embeddinggemma-2:270m-bf16-text"
        const val LOCAL_INDEX_DIR = "rag/index-local"

        private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

        fun from(opts: Map<String, String>, geminiKey: String): LlmProvider {
            val name = (opts["provider"] ?: env("LLM_PROVIDER") ?: GEMINI).lowercase()
            require(name == GEMINI || name == OLLAMA) { "Unknown provider '$name' (use gemini or ollama)" }
            return LlmProvider(
                name, geminiKey,
                opts["ollama-url"] ?: env("OLLAMA_URL") ?: OllamaChatClient.DEFAULT_URL,
                opts["ollama-model"] ?: env("OLLAMA_MODEL") ?: OllamaChatClient.DEFAULT_MODEL,
                opts["ollama-embed-model"] ?: env("OLLAMA_EMBED_MODEL") ?: DEFAULT_EMBED_MODEL,
                (opts["ollama-tuning"] ?: env("OLLAMA_TUNING"))?.let { OllamaTuning.parse(it) } ?: OllamaTuning.RECOMMENDED,
                localPrompts = PromptProfile.parse(opts["prompt-profile"] ?: env("RAG_PROMPT_PROFILE") ?: "local"),
            )
        }
    }
}
