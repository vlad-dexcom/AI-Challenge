package com.example.webconsole

import com.example.rag.resolveGeminiApiKey
import java.io.File

private const val USAGE = """
Web console (chat + chunk visualiser). Local-only server bound to loopback.

  ./gradlew :web-console:run --args="[--port 8080] [--corpus rag/corpus] [--out rag/index] [--ollama-tuning numCtx=8192,rewrite.maxTokens=96] [--prompt-profile default|local] [--ollama-url http://localhost:11434] [--ollama-model gemma4:26b-a4b-it-qat]"

The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
The Local provider of the Chat tab ("Answer with" → Local) talks to Ollama (OLLAMA_URL / OLLAMA_MODEL / OLLAMA_EMBED_MODEL env vars or the flags above); it needs no key and no internet.
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(USAGE.trim())
        return
    }
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val server = UiServer(
        UiApi(
            File(opts["corpus"] ?: "rag/corpus"), File(opts["out"] ?: "rag/index"), resolveGeminiApiKey(),
            ollamaUrl = opts["ollama-url"] ?: System.getenv("OLLAMA_URL")?.takeIf { it.isNotBlank() } ?: com.example.core.llm.OllamaChatClient.DEFAULT_URL,
            ollamaTuning = try {
                (opts["ollama-tuning"] ?: System.getenv("OLLAMA_TUNING")?.takeIf { it.isNotBlank() })?.let { com.example.core.llm.OllamaTuning.parse(it) } ?: com.example.core.llm.OllamaTuning.RECOMMENDED
            } catch (e: IllegalArgumentException) { System.err.println(e.message); kotlin.system.exitProcess(1) },
            ollamaPrompts = try {
                com.example.rag.PromptProfile.parse(opts["prompt-profile"] ?: System.getenv("RAG_PROMPT_PROFILE")?.takeIf { it.isNotBlank() } ?: "local")
            } catch (e: IllegalArgumentException) { System.err.println(e.message); kotlin.system.exitProcess(1) },
            ollamaEmbedModel = opts["ollama-embed-model"] ?: System.getenv("OLLAMA_EMBED_MODEL")?.takeIf { it.isNotBlank() } ?: com.example.rag.LlmProvider.DEFAULT_EMBED_MODEL,
            ollamaModel = opts["ollama-model"] ?: System.getenv("OLLAMA_MODEL")?.takeIf { it.isNotBlank() } ?: com.example.core.llm.OllamaChatClient.DEFAULT_MODEL,
        ),
        (opts["port"] ?: "8080").toInt(),
    )
    server.start()
    println("Web console (chat + chunk visualiser): http://localhost:${server.port}  (Ctrl+C to stop)")
    Thread.currentThread().join()
}
