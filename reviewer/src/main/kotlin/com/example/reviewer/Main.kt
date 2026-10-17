package com.example.reviewer

import com.example.rag.resolveGeminiApiKey
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
AI code review (Day 32): reviews the changes of a branch against its base with RAG (project docs + code) and a Gemini model.

  ./gradlew :reviewer:run --args="--base main"
  options: [--head HEAD] [--out review.md] [--lang en|ru] [--model gemini-3.8-flash] [--min-confidence 6]
           [--no-code] [--reindex] [--verbose] [--repo <path>]
    --base        ref the change is compared with (the merge base is used), e.g. main or origin/main   (required)
    --out         write the markdown review to a file instead of stdout
    --no-code     do not build/use the code index (docs only; faster, no embedding of the sources)
    --verbose     print the dropped findings and why to stderr

Prints a markdown review (potential bugs, architecture and convention problems, recommendations). Read-only: nothing is changed.
The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never printed or committed).
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) { println(USAGE.trim()); return }
    val flags = setOf("--no-code", "--reindex", "--verbose")
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a in flags) opts[a.removePrefix("--")] = "true"
        else if (a.startsWith("--") && i + 1 < args.size) opts[a.removePrefix("--")] = args[++i]
        else { System.err.println("Unexpected argument '$a'\n${USAGE.trim()}"); exitProcess(1) }
        i++
    }
    val base = opts["base"] ?: run { System.err.println("--base is required\n${USAGE.trim()}"); exitProcess(1) }
    val lang = opts["lang"] ?: "en"
    if (lang !in setOf("en", "ru")) { System.err.println("--lang must be en or ru"); exitProcess(1) }
    val minConfidence = opts["min-confidence"]?.let { it.toIntOrNull() ?: run { System.err.println("--min-confidence must be a number"); exitProcess(1) } }
        ?: ReviewConfig().minConfidence
    val model = opts["model"] ?: ReviewerFactory.DEFAULT_MODEL
    val verbose = "verbose" in opts

    val report = try {
        runBlocking {
            val reviewer = ReviewerFactory.create(
                repoRoot = File(opts["repo"] ?: "."), apiKey = resolveGeminiApiKey(), model = model,
                config = ReviewConfig(lang = lang, minConfidence = minConfidence),
                useCode = "no-code" !in opts, reindex = "reindex" in opts, log = { System.err.println(it) },
            )
            reviewer.review(base, opts["head"] ?: "HEAD")
        }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message); exitProcess(2)
    }.getOrElse {
        System.err.println("Review failed: ${it.message}"); exitProcess(1)
    }

    if (verbose) report.dropped.forEach { System.err.println("dropped: $it") }
    val markdown = report.toMarkdown(lang)
    opts["out"]?.let { File(it).writeText(markdown); System.err.println("Review written to $it (${report.findingCount} findings)") } ?: print(markdown)
}
