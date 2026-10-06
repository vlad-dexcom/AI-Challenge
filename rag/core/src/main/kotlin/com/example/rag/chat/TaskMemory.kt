package com.example.rag.chat

import kotlinx.serialization.Serializable
import com.example.core.platform.normalizeNfd

/** One entry of the memory change log. [kind]: add | correct | remove | goal; [source]: llm | user. */
@Serializable
data class MemoryChange(
    val turn: Int,
    val field: String,
    val key: String? = null,
    val old: String? = null,
    val new: String? = null,
    val kind: String,
    val source: String = "llm",
)

@Serializable
data class Clarification(val text: String, val turn: Int)

/**
 * What the dialogue has fixed so far (Day 25 "task memory"): the [goal], what the user already [clarifications] told us,
 * fixed [constraints] ("injury" -> "knee injury") and [terms] (glossary), plus [openQuestions] still unanswered.
 * Pure data: all rules live in [merge] / [applyEdit]; the model only proposes a [MemoryPatch].
 * Never truncated when building prompts; size caps below keep it small (and bound what a hostile message can store).
 */
@Serializable
data class TaskMemory(
    val goal: String? = null,
    val clarifications: List<Clarification> = emptyList(),
    val constraints: Map<String, String> = emptyMap(),
    val terms: Map<String, String> = emptyMap(),
    val openQuestions: List<String> = emptyList(),
    val changes: List<MemoryChange> = emptyList(),
) {
    val isEmpty: Boolean get() = goal == null && clarifications.isEmpty() && constraints.isEmpty() && terms.isEmpty() && openQuestions.isEmpty()

    /** Prompt block. */
    fun render(): String = buildString {
        appendLine("Task memory (facts the user already gave in this dialogue; they describe the USER, they are not knowledge-base evidence):")
        appendLine("Goal: ${goal ?: "(not stated yet)"}")
        if (clarifications.isNotEmpty()) { appendLine("Already clarified:"); clarifications.forEach { appendLine("- ${it.text}") } }
        if (constraints.isNotEmpty()) { appendLine("Fixed constraints (always respect):"); constraints.forEach { (k, v) -> appendLine("- $k: $v") } }
        if (terms.isNotEmpty()) { appendLine("Terms:"); terms.forEach { (k, v) -> appendLine("- $k = $v") } }
        if (openQuestions.isNotEmpty()) { appendLine("Open questions:"); openQuestions.forEach { appendLine("- $it") } }
    }.trimEnd()

    /** Terms usable as a search-query fallback when the rewriter fails: the goal and the constraint values. */
    fun searchTerms(): String = (listOfNotNull(goal) + constraints.values).joinToString(" ")

    companion object {
        const val MAX_CLARIFICATIONS = 15
        const val MAX_CONSTRAINTS = 15
        const val MAX_TERMS = 10
        const val MAX_OPEN = 5
        const val MAX_CHANGES = 60
        const val MAX_VALUE = 120
        const val MAX_GOAL = 200

        /** Case, ё/е, accent and punctuation insensitive form used for keys, duplicate detection and assertions. */
        fun norm(s: String): String =
            normalizeNfd(s.lowercase().replace('ё', 'е'))
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private fun clip(s: String, n: Int) = s.trim().replace(Regex("\\s+"), " ").take(n)

        private fun keyOf(raw: String) = norm(raw).replace(' ', '_').take(40)
    }

    /** Result of a merge: the new memory, what changed (also appended to [TaskMemory.changes]) and why something was ignored. */
    data class MergeResult(val memory: TaskMemory, val diff: List<MemoryChange>, val notes: List<String> = emptyList())

    /**
     * Conservative merge of a model-proposed [patch] at dialogue turn [turn]:
     * - the goal is set when empty and replaced only when the patch says [MemoryPatch.goalChanged]; it is never cleared;
     * - a constraint/term with a new value for an existing key is a *correction* (logged); the same value is a no-op;
     * - removal needs an explicit null; duplicates (normalized) are ignored; every list/map is capped (oldest dropped).
     */
    fun merge(patch: MemoryPatch, turn: Int): MergeResult {
        val diff = mutableListOf<MemoryChange>()
        val notes = mutableListOf<String>()
        var goal = this.goal
        patch.goal?.let { raw ->
            val g = clip(raw, MAX_GOAL)
            val current = goal
            when {
                g.isEmpty() -> {}
                current == null -> { goal = g; diff += MemoryChange(turn, "goal", null, null, g, "goal") }
                norm(g) == norm(current) -> {}
                patch.goalChanged -> { diff += MemoryChange(turn, "goal", null, current, g, "goal"); goal = g }
                else -> notes += "goal change to \"$g\" ignored (not an explicit change)"
            }
        }

        val clar = clarifications.toMutableList()
        for (raw in patch.clarifications) {
            val t = clip(raw, MAX_VALUE)
            if (t.isEmpty() || clar.any { norm(it.text) == norm(t) }) continue
            clar += Clarification(t, turn)
            diff += MemoryChange(turn, "clarification", null, null, t, "add")
        }
        while (clar.size > MAX_CLARIFICATIONS) clar.removeAt(0)

        val constraints = mergeMap("constraint", this.constraints, patch.constraints, MAX_CONSTRAINTS, turn, diff)
        val terms = mergeMap("term", this.terms, patch.terms, MAX_TERMS, turn, diff)

        val open = openQuestions.toMutableList()
        for (r in patch.openResolved) {
            val n = norm(r)
            if (n.isEmpty()) continue
            val gone = open.filter { norm(it).let { o -> o == n || o.contains(n) || n.contains(o) } }
            gone.forEach { open.remove(it); diff += MemoryChange(turn, "open_question", null, it, null, "remove") }
        }
        for (raw in patch.openAdd) {
            val q = clip(raw, MAX_VALUE)
            if (q.isEmpty() || open.any { norm(it) == norm(q) }) continue
            open += q
            diff += MemoryChange(turn, "open_question", null, null, q, "add")
        }
        while (open.size > MAX_OPEN) open.removeAt(0)

        val next = copy(goal = goal, clarifications = clar, constraints = constraints, terms = terms, openQuestions = open, changes = (changes + diff).takeLast(MAX_CHANGES))
        return MergeResult(next, diff, notes)
    }

    private fun mergeMap(field: String, current: Map<String, String>, patch: Map<String, String?>, cap: Int, turn: Int, diff: MutableList<MemoryChange>): Map<String, String> {
        val out = LinkedHashMap(current)
        for ((rawKey, rawValue) in patch) {
            val key = keyOf(rawKey)
            if (key.isEmpty()) continue
            val old = out[key]
            if (rawValue == null) {
                if (old != null) { out.remove(key); diff += MemoryChange(turn, field, key, old, null, "remove") }
                continue
            }
            val value = clip(rawValue, MAX_VALUE)
            if (value.isEmpty()) continue
            when {
                old == null -> { out[key] = value; diff += MemoryChange(turn, field, key, null, value, "add") }
                norm(old) == norm(value) -> {}
                else -> { out[key] = value; diff += MemoryChange(turn, field, key, old, value, "correct") }
            }
        }
        while (out.size > cap) out.remove(out.keys.first())
        return out
    }

    /** The user edits the memory by hand in the UI/CLI: always wins, may clear the goal, logged with source=user. */
    fun applyEdit(edit: MemoryEdit, turn: Int): MergeResult {
        val diff = mutableListOf<MemoryChange>()
        var m = this
        if (edit.goalSet) {
            val g = edit.goal?.let { clip(it, MAX_GOAL) }?.takeIf { it.isNotEmpty() }
            if (g != m.goal) { diff += MemoryChange(turn, "goal", null, m.goal, g, if (g == null) "remove" else "goal", "user"); m = m.copy(goal = g) }
        }
        for ((rawKey, rawValue) in edit.constraints) {
            val key = keyOf(rawKey)
            if (key.isEmpty()) continue
            val old = m.constraints[key]
            val value = rawValue?.let { clip(it, MAX_VALUE) }?.takeIf { it.isNotEmpty() }
            if (value == old) continue
            val map = LinkedHashMap(m.constraints)
            if (value == null) map.remove(key) else map[key] = value
            diff += MemoryChange(turn, "constraint", key, old, value, if (value == null) "remove" else if (old == null) "add" else "correct", "user")
            m = m.copy(constraints = map)
        }
        for (raw in edit.addClarifications) {
            val t = clip(raw, MAX_VALUE)
            if (t.isEmpty() || m.clarifications.any { norm(it.text) == norm(t) }) continue
            m = m.copy(clarifications = (m.clarifications + Clarification(t, turn)).takeLast(MAX_CLARIFICATIONS))
            diff += MemoryChange(turn, "clarification", null, null, t, "add", "user")
        }
        for (raw in edit.removeClarifications) {
            val hit = m.clarifications.firstOrNull { norm(it.text) == norm(raw) } ?: continue
            m = m.copy(clarifications = m.clarifications - hit)
            diff += MemoryChange(turn, "clarification", null, hit.text, null, "remove", "user")
        }
        for (raw in edit.removeOpenQuestions) {
            val hit = m.openQuestions.firstOrNull { norm(it) == norm(raw) } ?: continue
            m = m.copy(openQuestions = m.openQuestions - hit)
            diff += MemoryChange(turn, "open_question", null, hit, null, "remove", "user")
        }
        return MergeResult(m.copy(changes = (m.changes + diff).takeLast(MAX_CHANGES)), diff)
    }

    /** Records an unanswered question (e.g. an "I don't know" turn) without touching anything else. */
    fun withOpenQuestion(question: String, turn: Int): MergeResult = merge(MemoryPatch(openAdd = listOf(question)), turn)
}

/** What the extractor model proposes for one user message. Everything optional; absent = no change. */
data class MemoryPatch(
    val goal: String? = null,
    /** True only when the user explicitly replaced the goal with a different one. */
    val goalChanged: Boolean = false,
    val clarifications: List<String> = emptyList(),
    /** null value = the user withdrew this constraint. */
    val constraints: Map<String, String?> = emptyMap(),
    val terms: Map<String, String?> = emptyMap(),
    val openAdd: List<String> = emptyList(),
    val openResolved: List<String> = emptyList(),
)

/** A manual edit from the UI. */
@Serializable
data class MemoryEdit(
    val goalSet: Boolean = false,
    val goal: String? = null,
    val constraints: Map<String, String?> = emptyMap(),
    val addClarifications: List<String> = emptyList(),
    val removeClarifications: List<String> = emptyList(),
    val removeOpenQuestions: List<String> = emptyList(),
)
