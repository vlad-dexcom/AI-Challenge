package com.example.devassistant

import com.example.rag.resolveGeminiApiKey
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Developer assistant (Day 31): answers questions about this project from README/AGENTS.md/docs (RAG) and the live git state (MCP).

  ./gradlew :dev-assistant:run                                  interactive: type "/help <question>" (or just a question), /exit to quit
  ./gradlew :dev-assistant:run --args="--once 'where does agent logic live?'"
  options: [--model gemini-3.5-flash] [--history] [--reindex] [--repo <path>]
    --history  also index the historical docs/dayNN-*.md notes   --reindex  rebuild the docs index

The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never printed or committed).
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) { println(USAGE.trim()); return }
    val flags = setOf("--history", "--reindex")
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a in flags) opts[a.removePrefix("--")] = "true"
        else if (a.startsWith("--") && i + 1 < args.size) opts[a.removePrefix("--")] = args[++i]
        else { System.err.println("Unexpected argument '$a'\n${USAGE.trim()}"); exitProcess(1) }
        i++
    }

    val assistant = try {
        runBlocking {
            DevAssistant.create(
                repoRoot = File(opts["repo"] ?: "."), apiKey = resolveGeminiApiKey(),
                model = opts["model"] ?: com.example.core.llm.GeminiTextGenerator.DEFAULT_MODEL,
                includeHistory = "history" in opts, reindex = "reindex" in opts, log = ::println,
            )
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message); exitProcess(2)
    }

    fun ask(question: String) = runBlocking { assistant.help(question) }
        .fold({ println(it.render()) }, { System.err.println("Error: ${it.message}") })

    opts["once"]?.let { ask(it.removePrefix("/help").trim()); exitProcess(0) }

    println("Developer assistant. Ask with \"/help <question>\"; /exit to quit.")
    while (true) {
        print("\n> ")
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == "/exit" || line == "/quit" -> break
            line == "/help" -> println("Usage: /help <question about the project>, e.g. /help which module owns the RAG pipeline?")
            line.startsWith("/help ") -> ask(line.removePrefix("/help").trim())
            line.startsWith("/") -> println("Unknown command. Use /help <question> or /exit.")
            else -> ask(line)
        }
    }
}
