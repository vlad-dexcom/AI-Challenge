package com.example.rag.chat

import com.example.rag.CachedTextGenerator
import com.example.rag.DiskCache
import com.example.rag.EmbeddingClient
import com.example.rag.GeminiTextGenerator
import com.example.rag.IndexStore
import com.example.rag.LlmUsage
import com.example.rag.ManualVerdict
import com.example.rag.VectorRetriever
import com.example.rag.cachedEmbedder
import com.example.rag.flag
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import kotlin.system.exitProcess

private val reportJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private class Wiring(val engine: ChatEngine, val judge: GoalJudge, val usage: LlmUsage, val options: ChatOptions)

private fun wire(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, key: String): Wiring {
    if (key.isBlank()) { System.err.println("GEMINI_API_KEY is not set (env var or local.properties)."); exitProcess(2) }
    val file = File(dir, "${opts["strategy"] ?: "structure"}.json")
    require(file.isFile) { "Missing index ${file.path}; run the index command first." }
    val index = IndexStore().load(file)
    val model = opts["model"] ?: GeminiTextGenerator.DEFAULT_MODEL
    val cache = DiskCache(if (flag(opts, "no-cache")) null else File("rag/cache/llm"))
    val raw = ThrottledTextGenerator(GeminiTextGenerator(key, model), opts["delay-ms"]?.toLong() ?: 0L)
    val usage = LlmUsage()
    val options = ChatOptions(
        memoryMode = opts["memory"]?.let { m -> MemoryMode.entries.first { it.name.equals(m.replace('-', '_'), true) } } ?: MemoryMode.FULL,
        keepLastTurns = opts["keep"]?.toInt() ?: 4,
        summaryBatch = opts["batch"]?.toInt() ?: 2,
    )
    val engine = ChatEngine(VectorRetriever(cachedEmbedder(embedder), index, options.topKBefore), CachedTextGenerator(raw, "$model@chat", usage, cache), usage)
    return Wiring(engine, GoalJudge(CachedTextGenerator(raw, "$model@goal-judge", LlmUsage(), cache)), usage, options)
}

/** `scenarios-eval`: replays the scenario files of rag/eval/scenarios in the FULL / HISTORY_ONLY / NONE variants and writes rag/eval/scenarios-report.{md,json}. */
internal suspend fun runScenariosEval(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, key: String) {
    val w = wire(opts, dir, embedder, key)
    val all = ScenarioLoader.loadAll(File(opts["scenarios"] ?: "rag/eval/scenarios"))
    val scenarios = opts["scenario"]?.let { id -> all.filter { it.id == id } } ?: all
    require(scenarios.isNotEmpty()) { "No scenarios found" }
    val variants = opts["variants"]?.split(',')?.map { v -> MemoryMode.entries.first { it.name.equals(v.trim().replace('-', '_'), true) } } ?: MemoryMode.entries.toList()
    val out = File(opts["report"] ?: "rag/eval").also { it.mkdirs() }
    val manualFile = File(out, "scenarios-manual.json")
    val manual = if (manualFile.isFile) Json.decodeFromString(MapSerializer(serializer<String>(), ManualVerdict.serializer()), manualFile.readText()) else emptyMap()
    val runner = ScenarioRunner(w.engine, w.judge, w.options)
    val runs = scenarios.flatMap { sc -> variants.map { v -> runner.run(sc, v, manual) { println(it) } } }
    val spec = "${scenarios.size} scenarios (${scenarios.joinToString { "${it.id}: ${it.turns.size} turns" }}), variants ${variants.joinToString { it.name }}; " +
        "model ${opts["model"] ?: GeminiTextGenerator.DEFAULT_MODEL}, keep last ${w.options.keepLastTurns} turns, summary batch ${w.options.summaryBatch}, threshold ${w.options.threshold}, topK ${w.options.topKBefore}->${w.options.topK}"
    val report = ScenariosReport(spec, runs)
    File(out, "scenarios-report.json").writeText(reportJson.encodeToString(ScenariosReport.serializer(), report))
    val md = ScenarioReport.render(report, scenarios.associateBy { it.id })
    File(out, "scenarios-report.md").writeText(md)
    println(md.lineSequence().takeWhile { !it.startsWith("## Scenario") }.joinToString("\n"))
    println("Written to ${out.path}/scenarios-report.json and scenarios-report.md")
}

/** `chat`: a REPL over the same engine and session files as the web UI. */
internal suspend fun runChatRepl(opts: Map<String, String>, dir: File, embedder: EmbeddingClient, key: String) {
    val w = wire(opts, dir, embedder, key)
    val store = SessionStore(File(opts["sessions"] ?: "rag/sessions"))
    var session = opts["session"]?.let { store.load(it) ?: error("No such session ${it}") } ?: store.create()
    println("Session ${session.id}, memory mode ${w.options.memoryMode}. Commands: /memory  /goal <text>  /reset  /new  /sessions  /quit")
    fun printMemory() = println(session.memory.render() + (if (session.summary.isNotBlank()) "\nSummary: ${session.summary}" else ""))
    while (true) {
        print("you> "); System.out.flush()
        val line = readLine()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == "/quit" -> break
            line == "/memory" -> printMemory()
            line == "/new" -> { session = store.create(); println("New session ${session.id}") }
            line == "/sessions" -> store.list().forEach { println("${it.id}  ${it.turns} turns  ${it.title}") }
            line == "/reset" -> { session = session.copy(messages = emptyList(), memory = TaskMemory(), summary = "", summarizedUpTo = 0, title = "New chat").also(store::save); println("Session reset.") }
            line.startsWith("/goal ") -> { session = session.copy(memory = session.memory.applyEdit(MemoryEdit(goalSet = true, goal = line.removePrefix("/goal ")), session.turnCount).memory).also(store::save); printMemory() }
            else -> {
                w.engine.send(session, line, w.options).fold({
                    session = it; store.save(it)
                    val msg = it.messages.last()
                    println("\nbot> ${msg.text}")
                    val info = msg.info
                    if (info != null) {
                        if (info.sources.isNotEmpty()) println("\nSources:\n" + info.sources.joinToString("\n") { s -> "  - ${s.label} (${"%.2f".format(s.score)})" })
                        info.structured?.quotes?.filter { q -> q.ok }?.forEach { q -> println("  \"${q.text.take(160)}\"") }
                        if (info.consulted.isNotEmpty()) println("\nClosest (not evidence):\n" + info.consulted.joinToString("\n") { s -> "  - ${s.label}" })
                        info.trace?.let { t -> println("\n[search: ${t.searchQuery}]") }
                        if (info.memoryDiff.isNotEmpty()) println("[memory: " + info.memoryDiff.joinToString("; ") { d -> "${d.kind} ${d.field}${d.key?.let { k -> " $k" } ?: ""} = ${d.new ?: "∅"}" } + "]")
                        println("[${info.llmCalls} LLM calls, ${"%.1f".format(info.llmMs / 1000.0)} s]\n")
                    }
                }, { println("Error: ${it.message}") })
            }
        }
    }
}
