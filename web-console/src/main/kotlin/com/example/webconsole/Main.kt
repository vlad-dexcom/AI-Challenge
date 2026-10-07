package com.example.webconsole

import com.example.rag.resolveGeminiApiKey
import java.io.File

private const val USAGE = """
Web console (chat + chunk visualiser). Local-only server bound to loopback.

  ./gradlew :web-console:run --args="[--port 8080] [--corpus rag/corpus] [--out rag/index] [--ollama-url http://localhost:11434] [--ollama-model gemma4:26b-a4b-it-qat]"

The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
The "Local LLM" tab talks to Ollama (OLLAMA_URL / OLLAMA_MODEL env vars or the flags above); it needs no key and no internet.
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
            ollamaModel = opts["ollama-model"] ?: System.getenv("OLLAMA_MODEL")?.takeIf { it.isNotBlank() } ?: com.example.core.llm.OllamaChatClient.DEFAULT_MODEL,
        ),
        (opts["port"] ?: "8080").toInt(),
    )
    server.start()
    println("Web console (chat + chunk visualiser): http://localhost:${server.port}  (Ctrl+C to stop)")
    Thread.currentThread().join()
}
