package com.example.rag.chat

import com.example.core.llm.GenerationOptions
import com.example.rag.IdkResponder
import com.example.rag.ManualVerdict
import com.example.core.llm.TextGenerator
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One scripted user message with its expectations. Every `*Has` is a list of groups; a group holds alternatives and is
 * satisfied when ANY alternative occurs (normalized, substring) - this keeps the checks robust to wording and to Russian inflection.
 */
@Serializable
data class ScenarioTurn(
    val user: String,
    /** Why this turn exists in the dialogue (drift, follow-up, correction, ...). */
    val note: String = "",
    /** "answer" (sources + verified quotes) or "idk" (verified "I don't know" with a clarification question). */
    val expect: String = "answer",
    /** Language of the expected reply ("ru"/"en"), null = not checked. */
    val lang: String? = null,
    /** Task memory after this turn: constraint "key: value" text / clarification text / this turn's change kinds. */
    val constraintHas: List<List<String>> = emptyList(),
    val clarificationHas: List<List<String>> = emptyList(),
    val diffKinds: List<String> = emptyList(),
    /** The standalone search query must contain these (the follow-up was resolved with the history / memory). */
    val rewriteHas: List<List<String>> = emptyList(),
    val answerHas: List<List<String>> = emptyList(),
    val answerAvoids: List<String> = emptyList(),
    /** Some shown source must come from a file whose name contains one of these. */
    val sourceFileHas: List<String> = emptyList(),
    /** Constraints the user has fixed up to and including this turn (for the LLM judge only). */
    val fixes: List<String> = emptyList(),
    val judge: Boolean = false,
)

@Serializable
data class Scenario(val id: String, val title: String, val goal: String, val goalHas: List<List<String>>, val turns: List<ScenarioTurn>)

@Serializable
data class CheckResult(val name: String, val ok: Boolean, val detail: String = "")

@Serializable
data class JudgeVerdict(val goalKept: Boolean, val constraintsRespected: Boolean, val reason: String)

@Serializable
data class ScenarioTurnResult(
    val turn: Int,
    val user: String,
    val note: String,
    val answer: String,
    val idk: Boolean,
    val idkReason: String? = null,
    val sources: List<String> = emptyList(),
    val quotesVerified: Int = 0,
    val quotesTotal: Int = 0,
    val searchQuery: String? = null,
    val rewriteFallback: String? = null,
    val memoryGoal: String? = null,
    val memoryConstraints: Map<String, String> = emptyMap(),
    val memoryDiff: List<MemoryChange> = emptyList(),
    val memoryNotes: List<String> = emptyList(),
    val checks: List<CheckResult> = emptyList(),
    /** LLM-judged (same model family as the answerer): a noisy second opinion, not ground truth. */
    val judge: JudgeVerdict? = null,
    val manual: ManualVerdict? = null,
    val llmCalls: Int = 0,
    val llmMs: Long = 0,
    val summaryUpdated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ScenarioRun(val scenarioId: String, val variant: MemoryMode, val turns: List<ScenarioTurnResult>, val finalMemory: TaskMemory?, val summary: String = "")

@Serializable
data class ScenariosReport(val spec: String, val runs: List<ScenarioRun>)

object ScenarioLoader {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(file: File): Scenario = json.decodeFromString(Scenario.serializer(), file.readText())
    fun loadAll(dir: File): List<Scenario> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.map(::load)
}

/** LLM judge for "goal kept / constraints respected" on one reply. Same model family as the answerer: noisy, shares its biases. */
class GoalJudge(private val generator: TextGenerator) {
    suspend fun judge(goal: String, fixes: List<String>, question: String, answer: String, idk: Boolean): Result<JudgeVerdict> {
        val prompt = buildString {
            appendLine("Dialogue goal: $goal")
            appendLine("Constraints the user has fixed so far: ${if (fixes.isEmpty()) "(none)" else fixes.joinToString("; ")}")
            appendLine("User message: $question")
            appendLine("Assistant reply${if (idk) " (an \"I don't know\" reply)" else ""}:\n$answer\n")
            appendLine("goalKept = the reply keeps the dialogue goal in view or at least does not derail it (an on-topic answer to a side question is fine; for an \"I don't know\" reply, goalKept requires that it points back to the goal).")
            appendLine("constraintsRespected = nothing in the reply contradicts a fixed constraint (e.g. recommends something the user cannot do or eat) unless it explicitly flags the conflict; true when there are no constraints.")
            append("Reply with JSON only: {\"goalKept\":true|false,\"constraintsRespected\":true|false,\"reason\":\"<one short sentence>\"}")
        }
        val reply = generator.generate(SYSTEM, prompt, GenerationOptions(temperature = 0.0, json = true)).getOrElse { return Result.failure(it) }
        return parse(reply)
    }

    companion object {
        const val SYSTEM = "You are a strict reviewer of a personal-trainer chat. Judge only what is asked."

        fun parse(reply: String): Result<JudgeVerdict> = runCatching {
            val o = Json.parseToJsonElement(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
            fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: error("missing $k")
            JudgeVerdict(b("goalKept"), b("constraintsRespected"), o["reason"]?.jsonPrimitive?.content.orEmpty())
        }
    }
}

/** Pure checks of one turn (no LLM): used by the runner and unit-tested with hand-made results. */
object ScenarioChecks {
    private fun n(s: String) = TaskMemory.norm(s)
    private fun hasAny(text: String, alts: List<String>) = alts.any { n(text).contains(n(it)) }
    private fun missing(text: String, groups: List<List<String>>) = groups.filter { !hasAny(text, it) }

    fun evaluate(
        scenario: Scenario, spec: ScenarioTurn, mode: MemoryMode, answer: String, idk: Boolean, sources: List<String>,
        info: TurnInfo?, memory: TaskMemory, turnNo: Int,
    ): List<CheckResult> = buildList {
        val st = info?.structured
        val expectIdk = spec.expect == "idk"
        add(CheckResult("expected_mode", idk == expectIdk, "expected ${spec.expect}, got ${if (idk) "idk" else "answer"}"))
        if (idk) {
            add(CheckResult("idk_has_clarification", !st?.clarification.isNullOrBlank() && sources.isEmpty(), "clarification + no sources"))
        } else {
            val quotes = st?.quotes.orEmpty()
            add(CheckResult("sources_present", sources.isNotEmpty() && quotes.any { it.ok }, "${sources.size} sources, ${quotes.count { it.ok }}/${quotes.size} quotes verified"))
            val finalIds = info?.trace?.reranked?.map { it.chunkId }.orEmpty().toSet()
            val shown = info?.sources?.map { it.chunkId }.orEmpty()
            add(CheckResult("evidence_integrity", shown.all { it in finalIds } && quotes.all { it.ok }, "sources ⊆ retrieved: ${shown.all { it in finalIds }}, quotes verbatim: ${quotes.all { it.ok }}"))
        }
        spec.lang?.let { add(CheckResult("language", IdkResponder.detectLanguage(answer) == it, "expected $it")) }
        if (spec.rewriteHas.isNotEmpty()) {
            val q = info?.trace?.searchQuery.orEmpty()
            val miss = missing(q, spec.rewriteHas)
            add(CheckResult("followup_resolved", miss.isEmpty(), if (miss.isEmpty()) q else "query \"$q\" lacks ${miss.map { it.joinToString("/") }}"))
        }
        if (spec.sourceFileHas.isNotEmpty()) add(CheckResult("expected_source", sources.any { s -> hasAny(s, spec.sourceFileHas) }, "one of ${spec.sourceFileHas}"))
        if (!idk) {
            if (spec.answerHas.isNotEmpty()) {
                val miss = missing(answer, spec.answerHas)
                add(CheckResult("answer_mentions", miss.isEmpty(), if (miss.isEmpty()) "" else "lacks ${miss.map { it.joinToString("/") }}"))
            }
            if (spec.answerAvoids.isNotEmpty()) {
                val bad = spec.answerAvoids.filter { n(answer).contains(n(it)) }
                add(CheckResult("answer_avoids", bad.isEmpty(), if (bad.isEmpty()) "" else "mentions $bad"))
            }
        }
        if (mode == MemoryMode.FULL) {
            val goalMiss = missing(memory.goal.orEmpty(), scenario.goalHas)
            add(CheckResult("goal_in_memory", memory.goal != null && goalMiss.isEmpty(), "goal=\"${memory.goal}\"" + if (goalMiss.isEmpty()) "" else " lacks ${goalMiss.map { it.joinToString("/") }}"))
            if (spec.constraintHas.isNotEmpty()) {
                val flat = memory.constraints.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                val miss = missing(flat, spec.constraintHas)
                add(CheckResult("constraint_in_memory", miss.isEmpty(), if (miss.isEmpty()) flat else "constraints \"$flat\" lack ${miss.map { it.joinToString("/") }}"))
            }
            if (spec.clarificationHas.isNotEmpty()) {
                val flat = memory.clarifications.joinToString(" | ") { it.text }
                val miss = missing(flat, spec.clarificationHas)
                add(CheckResult("clarification_in_memory", miss.isEmpty(), if (miss.isEmpty()) "" else "lacks ${miss.map { it.joinToString("/") }}"))
            }
            if (spec.diffKinds.isNotEmpty()) {
                val kinds = info?.memoryDiff?.map { it.kind }.orEmpty()
                add(CheckResult("memory_change_logged", spec.diffKinds.all { it in kinds }, "expected ${spec.diffKinds}, diff kinds $kinds"))
            }
            if (idk) add(CheckResult("idk_keeps_goal", memory.goal?.let { answer.contains(it) } == true, "IDK text restates the goal"))
        }
    }
}

/** Delays real calls so bursts stay under the rate limit; put it BELOW the disk cache so cache hits are free. */
class ThrottledTextGenerator(private val inner: TextGenerator, private val minIntervalMs: Long, private val clock: () -> Long = System::currentTimeMillis) : TextGenerator {
    private var last = 0L
    private suspend fun wait() {
        val gap = minIntervalMs - (clock() - last)
        if (gap > 0) delay(gap)
        last = clock()
    }
    override suspend fun generate(systemInstruction: String?, prompt: String): Result<String> { wait(); return inner.generate(systemInstruction, prompt) }
    override suspend fun generate(systemInstruction: String?, prompt: String, options: GenerationOptions): Result<String> { wait(); return inner.generate(systemInstruction, prompt, options) }
}

/** Replays scripted dialogues through the real [ChatEngine] and checks every turn. */
class ScenarioRunner(private val engine: ChatEngine, private val judge: GoalJudge?, private val options: ChatOptions = ChatOptions()) {
    suspend fun run(scenario: Scenario, variant: MemoryMode, manual: Map<String, ManualVerdict> = emptyMap(), onProgress: (String) -> Unit = {}): ScenarioRun {
        var session = ChatSession(id = "scenario-${scenario.id}-${variant.name.lowercase()}")
        val results = mutableListOf<ScenarioTurnResult>()
        val opts = options.copy(memoryMode = variant)
        scenario.turns.forEachIndexed { i, spec ->
            val turnNo = i + 1
            onProgress("${scenario.id}/${variant.name.lowercase()} #$turnNo: ${spec.user}")
            var sent = engine.send(session, spec.user, opts)
            // a failed turn leaves the session untouched, so one retry after a transient API error is safe
            if (sent.isFailure) sent = engine.send(session, spec.user, opts)
            val next = sent.getOrNull()
            if (next == null) {
                results += ScenarioTurnResult(turnNo, spec.user, spec.note, "", false, error = sent.exceptionOrNull()?.message)
                return@forEachIndexed
            }
            session = next
            val msg = session.messages.last { it.role == "assistant" }
            val info = msg.info
            val st = info?.structured
            val idk = st?.idk ?: false
            val sources = info?.sources?.map { it.label }.orEmpty()
            val checks = ScenarioChecks.evaluate(scenario, spec, variant, msg.text, idk, sources, info, session.memory, turnNo)
            val verdict = if (spec.judge && judge != null) judge.judge(scenario.goal, spec.fixes, spec.user, msg.text, idk).getOrNull() else null
            results += ScenarioTurnResult(
                turnNo, spec.user, spec.note, msg.text, idk, st?.idkReason?.name, sources,
                st?.quotes?.count { it.ok } ?: 0, st?.quotes?.size ?: 0, info?.trace?.searchQuery, info?.trace?.rewriteFallback,
                session.memory.goal, session.memory.constraints, info?.memoryDiff.orEmpty(), info?.memoryNotes.orEmpty(), checks, verdict,
                manual["${scenario.id}#$turnNo"].takeIf { variant == MemoryMode.FULL }, info?.llmCalls ?: 0, info?.llmMs ?: 0, info?.summaryUpdated ?: false,
            )
        }
        return ScenarioRun(scenario.id, variant, results, session.memory.takeIf { variant == MemoryMode.FULL }, session.summary)
    }
}
