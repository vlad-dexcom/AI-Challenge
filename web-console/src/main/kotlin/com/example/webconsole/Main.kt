package com.example.webconsole

import com.example.rag.resolveGeminiApiKey
import java.io.File

private const val USAGE = """
Web console (chat + chunk visualiser). Local-only server bound to loopback.

  ./gradlew :web-console:run --args="[--port 8080] [--corpus rag/corpus] [--out rag/index]"

The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never committed).
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        println(USAGE.trim())
        return
    }
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val server = UiServer(
        UiApi(File(opts["corpus"] ?: "rag/corpus"), File(opts["out"] ?: "rag/index"), resolveGeminiApiKey()),
        (opts["port"] ?: "8080").toInt(),
    )
    server.start()
    println("Web console (chat + chunk visualiser): http://localhost:${server.port}  (Ctrl+C to stop)")
    Thread.currentThread().join()
}
