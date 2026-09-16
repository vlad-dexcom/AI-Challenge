package com.example.geminichat.agent.memory

import com.example.geminichat.agent.AgentMessage
import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.geminichat.agent.TokenEstimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single "what went where and why" record produced by one [MemoryRouter.route] call, shown
 * verbatim in the UI's memory inspector so it's possible to check "which data ended up in which
 * layer" turn by turn, not just infer it from the final state.
 */
data class MemoryRoutingDecision(
    val layer: MemoryLayer,
    val key: String,
    val value: String,
    val reason: String
)

/**
 * Outcome of one [MemoryRouter.route] call: the *updated* working and long-term snapshots
 * (already guard-rule-cleaned — see [MemoryRouter.applyGuardRules]) plus [decisions] explaining
 * every item that changed, and [tokensUsed] — the cost of the routing call itself, tracked
 * separately so it isn't confused with the cost of the actual chat turn (mirrors
 * [com.example.geminichat.agent.CompressionOutcome] / [com.example.geminichat.agent.FactsOutcome]).
 */
data class MemoryRoutingOutcome(
    val working: MemorySnapshot,
    val longTerm: MemorySnapshot,
    val decisions: List<MemoryRoutingDecision>,
    val tokensUsed: Int
)

/** Parsed (but not yet guard-cleaned) result of [MemoryRouter.parseRouting]. */
internal data class ParsedRouting(
    val working: Map<String, String>,
    val longTerm: Map<String, String>
)

private val ROUTER_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Day 11's memory router: the "явный выбор, что и куда сохраняется" piece of the memory model.
 * After every user message, one LLM call classifies what just happened into
 * [MemoryLayer.WORKING] (data about the *current task*: goals, constraints, steps, open
 * questions — cleared by "End task") and [MemoryLayer.LONG_TERM] (durable facts about the
 * *user*: profile, standing decisions, knowledge — cleared only by an explicit user action).
 * [MemoryLayer.SHORT_TERM] (the dialog itself) is handled separately by the existing
 * transcript machinery in [com.example.geminichat.ChatViewModel] and never touched here.
 *
 * A suspend step ([route]) does the actual LLM-backed classification, with parsing isolated in
 * [parseRouting] so it's unit-testable without a network dependency, and a deterministic
 * [applyGuardRules] pass so the LLM's output can never corrupt memory outright (malformed
 * JSON, oversized values, or — critically — overwriting something the user pinned manually).
 */
class MemoryRouter(private val client: LlmClient) {

    companion object {
        private const val ROUTER_SYSTEM_INSTRUCTION =
            "You maintain two separate memory layers for an ongoing conversation. WORKING " +
                "memory holds facts about the CURRENT TASK only: its goal, constraints, steps " +
                "already decided, and open questions — things that stop mattering once the " +
                "task is finished. LONG_TERM memory holds durable facts about the USER that " +
                "should be remembered forever, across tasks and conversations: profile " +
                "details (age, condition, preferences), standing decisions, and general " +
                "knowledge about them. You will be given the current contents of both layers " +
                "as JSON objects, plus the newest conversation turn(s). Some entries are " +
                "marked pinned=true — these were set manually by the user and you MUST copy " +
                "them through unchanged (same key and value), never edit or remove them. For " +
                "everything else, decide which layer (if any) the new turn adds or updates " +
                "information in. Return a single JSON object with exactly two keys, " +
                "\"working\" and \"long_term\", each a flat JSON object of string keys to " +
                "string values representing the COMPLETE updated contents of that layer " +
                "(include unchanged existing entries, add new ones, drop ones that no longer " +
                "apply). Use short, stable, snake_case keys. If nothing belongs in a layer, " +
                "use an empty object. Respond with ONLY the JSON object — no markdown fences, " +
                "no commentary, no surrounding text."

        /** Guard rule: at most this many items are kept per layer (oldest, unpinned first). */
        const val MAX_ITEMS_PER_LAYER = 20

        /** Guard rule: values longer than this are truncated (prevents a raw transcript dump
         * from being smuggled into a single "fact"). */
        const val MAX_VALUE_LENGTH = 200

        /**
         * How many most-recent messages are sent verbatim as [route]'s `recentContext` and as
         * [com.example.geminichat.agent.AgentRequest.history] alongside the memory layers —
         * older turns are represented only by whatever the router already classified into
         * [MemoryLayer.WORKING]/[MemoryLayer.LONG_TERM], not resent raw every turn.
         */
        const val RECENT_CONTEXT_SIZE = 8

        /**
         * Best-effort parse of a router reply that should be `{"working": {...}, "long_term":
         * {...}}`. Tolerates a markdown code fence (models occasionally add one despite
         * instructions). Returns `null` for anything unparseable, so callers can fall back to
         * the previous layers unchanged instead of losing memory to a malformed reply.
         */
        internal fun parseRouting(rawText: String): ParsedRouting? {
            val cleaned = rawText.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty()) return null
            return try {
                val root = ROUTER_JSON.parseToJsonElement(cleaned).jsonObject
                val working = root["working"]?.jsonObject?.entries?.associate { (k, v) ->
                    k to jsonElementToString(v)
                } ?: emptyMap()
                val longTerm = root["long_term"]?.jsonObject?.entries?.associate { (k, v) ->
                    k to jsonElementToString(v)
                } ?: emptyMap()
                ParsedRouting(working = working, longTerm = longTerm)
            } catch (e: Exception) {
                null
            }
        }

        private fun jsonElementToString(element: JsonElement): String = try {
            element.jsonPrimitive.content
        } catch (e: Exception) {
            element.toString()
        }

        private fun normalizeKey(key: String): String = key.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    /**
     * Calls the LLM to classify the newest turn into [previousWorking] / [previousLongTerm],
     * then runs [applyGuardRules] on the reply before returning it. On a malformed/non-JSON
     * reply, falls back to the previous layers unchanged (the routing cost is still counted,
     * since the call was made).
     *
     * @param recentContext a short recent tail of the dialog, for grounding.
     * @param turn the 1-based user-message turn index, stamped onto any item this call writes.
     */
    suspend fun route(
        previousWorking: MemorySnapshot,
        previousLongTerm: MemorySnapshot,
        recentContext: List<AgentMessage>,
        newUserMessage: String,
        turn: Int,
        model: String
    ): Result<MemoryRoutingOutcome> {
        val transcript = recentContext.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else "Agent"
            "$speaker: ${message.text}"
        }
        val input = "Current working memory (JSON):\n${renderAsJson(previousWorking)}\n\n" +
            "Current long-term memory (JSON):\n${renderAsJson(previousLongTerm)}\n\n" +
            (if (transcript.isNotBlank()) "Recent context:\n$transcript\n\n" else "") +
            "Newest user message:\nUser: $newUserMessage\n\n" +
            "Write the updated JSON object now."

        val spec = LlmRequestSpec(
            model = model,
            input = input,
            systemInstruction = ROUTER_SYSTEM_INSTRUCTION
        )

        val requestTokens = TokenEstimator.estimate(input) +
            TokenEstimator.estimate(ROUTER_SYSTEM_INSTRUCTION)

        return client.complete(spec).map { text ->
            val parsed = parseRouting(text)
            val tokensUsed = requestTokens + TokenEstimator.estimate(text)
            if (parsed == null) {
                return@map MemoryRoutingOutcome(
                    working = previousWorking,
                    longTerm = previousLongTerm,
                    decisions = emptyList(),
                    tokensUsed = tokensUsed
                )
            }
            applyGuardRules(previousWorking, previousLongTerm, parsed, turn, tokensUsed)
        }
    }

    /**
     * Deterministic safety net over the LLM's raw classification — the guarantee that no
     * reply, however malformed or over-eager, can corrupt memory outright:
     *
     * 1. Any item the user [pinned][MemoryItem.pinned] is copied through unchanged, regardless
     *    of what the router returned for that key — a manual override always wins.
     * 2. Keys are normalized to snake_case; blank keys/values are dropped.
     * 3. Values longer than [MAX_VALUE_LENGTH] are truncated (blocks a raw transcript dump
     *    being smuggled in as a single "fact").
     * 4. Each layer is capped at [MAX_ITEMS_PER_LAYER] items — pinned items are always kept;
     *    if there's still an overflow, the oldest unpinned items are dropped first.
     */
    internal fun applyGuardRules(
        previousWorking: MemorySnapshot,
        previousLongTerm: MemorySnapshot,
        parsed: ParsedRouting,
        turn: Int,
        tokensUsed: Int
    ): MemoryRoutingOutcome {
        val decisions = mutableListOf<MemoryRoutingDecision>()
        val working = mergeLayer(
            previous = previousWorking,
            proposed = parsed.working,
            layer = MemoryLayer.WORKING,
            turn = turn,
            decisions = decisions
        )
        val longTerm = mergeLayer(
            previous = previousLongTerm,
            proposed = parsed.longTerm,
            layer = MemoryLayer.LONG_TERM,
            turn = turn,
            decisions = decisions
        )
        return MemoryRoutingOutcome(working, longTerm, decisions, tokensUsed)
    }

    private fun mergeLayer(
        previous: MemorySnapshot,
        proposed: Map<String, String>,
        layer: MemoryLayer,
        turn: Int,
        decisions: MutableList<MemoryRoutingDecision>
    ): MemorySnapshot {
        val merged = LinkedHashMap<String, MemoryItem>()

        // Rule 1: pinned/user items always survive untouched, no matter what the router said.
        previous.items.values.filter { it.pinned }.forEach { pinnedItem ->
            merged[pinnedItem.key] = pinnedItem
        }

        proposed.forEach { (rawKey, rawValue) ->
            val key = normalizeKey(rawKey)
            if (key.isEmpty() || merged.containsKey(key)) return@forEach // pinned wins
            val value = rawValue.trim().take(MAX_VALUE_LENGTH)
            if (value.isEmpty()) return@forEach
            val previousItem = previous.items[key]
            if (previousItem != null && previousItem.value == value) {
                merged[key] = previousItem
                return@forEach
            }
            val item = MemoryItem(
                key = key,
                value = value,
                source = MemorySource.ROUTER,
                turn = turn,
                pinned = false
            )
            merged[key] = item
            decisions += MemoryRoutingDecision(
                layer = layer,
                key = key,
                value = value,
                reason = if (previousItem == null) "new fact classified into $layer" else "updated in $layer"
            )
        }

        // Rule 4: cap size — pinned items are never dropped; drop the oldest unpinned ones.
        if (merged.size > MAX_ITEMS_PER_LAYER) {
            val overflow = merged.size - MAX_ITEMS_PER_LAYER
            val droppable = merged.values.filter { !it.pinned }.sortedBy { it.turn }
            droppable.take(overflow).forEach { merged.remove(it.key) }
        }

        return MemorySnapshot(items = merged)
    }

    /**
     * Renders [snapshot] as `{"key": {"value": "...", "pinned": true|false}, ...}` so the
     * router can see, per the [ROUTER_SYSTEM_INSTRUCTION], which entries are pinned and must be
     * copied through unchanged.
     */
    private fun renderAsJson(snapshot: MemorySnapshot): String {
        if (snapshot.items.isEmpty()) return "{}"
        val body = snapshot.items.values.joinToString(",") { item ->
            "${jsonQuote(item.key)}:{\"value\":${jsonQuote(item.value)},\"pinned\":${item.pinned}}"
        }
        return "{$body}"
    }

    private fun jsonQuote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "\"$escaped\""
    }
}
