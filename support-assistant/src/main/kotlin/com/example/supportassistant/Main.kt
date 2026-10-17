package com.example.supportassistant

import com.example.core.llm.GeminiTextGenerator
import com.example.rag.resolveGeminiApiKey
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Support assistant (Day 33): answers a customer's question about Personal Trainer from the FAQ (RAG) and the data of the ticket (CRM over MCP).

  ./gradlew :support-assistant:run                       interactive
      /tickets            list tickets          /ticket T-101    select a ticket
      /context on|off     use / ignore the ticket data (off = plain FAQ answer)      /exit
      anything else       a question about the selected ticket
  ./gradlew :support-assistant:run --args="--ticket T-101 --once 'Почему не работает авторизация?'"
  ./gradlew :support-assistant:run --args="--eval [--out support/eval/results.md]"     every question of support/eval/questions.json, FAQ only vs with ticket
  options: [--ticket T-101] [--no-ticket] [--once <question>] [--list] [--eval] [--out <file>] [--model gemini-3.5-flash] [--reindex] [--repo <path>]

The Gemini key is read from the GEMINI_API_KEY env var or from local.properties (never printed or committed).
"""

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) { println(USAGE.trim()); return }
    val flags = setOf("--no-ticket", "--reindex", "--list", "--eval")
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a in flags) opts[a.removePrefix("--")] = "true"
        else if (a.startsWith("--") && i + 1 < args.size) opts[a.removePrefix("--")] = args[++i]
        else { System.err.println("Unexpected argument '$a'\n${USAGE.trim()}"); exitProcess(1) }
        i++
    }
    val repo = File(opts["repo"] ?: ".")

    fun listTickets(store: SupportStore) = store.tickets.forEach { t ->
        println("${t.id}  [${t.status}/${t.priority}]  ${store.user(t.userId)?.name ?: t.userId}: ${t.subject}")
    }
    if ("list" in opts) {
        listTickets(try { SupportStore.load(File(repo, SupportStore.DEFAULT_DIR)) } catch (e: IllegalArgumentException) { System.err.println(e.message); exitProcess(2) })
        return
    }

    val wiring = try {
        runBlocking { SupportFactory.create(repo, resolveGeminiApiKey(), opts["model"] ?: GeminiTextGenerator.DEFAULT_MODEL, "reindex" in opts, ::println) }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message); exitProcess(2)
    }
    if ("eval" in opts) {
        val model = opts["model"] ?: GeminiTextGenerator.DEFAULT_MODEL
        val rows = runBlocking { SupportEval.run(wiring.assistant, SupportEval.load(File(repo, "support/eval/questions.json")), ::println) }
        val report = SupportEval.markdown(rows, model)
        opts["out"]?.let { File(it).writeText(report); println("Eval written to $it") } ?: println("\n" + report)
        exitProcess(0)
    }
    var ticket: String? = opts["ticket"]
    var useTicket = "no-ticket" !in opts

    fun ask(question: String) = runBlocking { wiring.assistant.ask(ticket, question, useTicket) }
        .fold({ println(it.render()) }, { System.err.println("Error: ${it.message}") })

    opts["once"]?.let { ask(it); exitProcess(0) }

    println("Support assistant. /tickets, /ticket <id>, /context on|off, /exit.")
    while (true) {
        print("\n${if (useTicket) ticket ?: "no ticket" else "no ticket context"}> ")
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == "/exit" || line == "/quit" -> break
            line == "/tickets" -> listTickets(wiring.store)
            line.startsWith("/ticket ") -> {
                val t = wiring.store.ticket(line.removePrefix("/ticket ").trim())
                if (t == null) println("Unknown ticket. Use /tickets.") else { ticket = t.id; useTicket = true; println("${t.id}: ${t.subject}") }
            }
            line == "/context on" -> useTicket = true
            line == "/context off" -> useTicket = false
            line.startsWith("/") -> println("Unknown command. See /tickets, /ticket <id>, /context on|off, /exit.")
            else -> ask(line)
        }
    }
}
