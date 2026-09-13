package com.example.geminichat.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outcome of merging a new turn into the sticky facts memory (see [FactsExtractor.extract]).
 * [facts] replaces whatever facts map was previously stored; [tokensUsed] is the (estimated)
 * cost of the extraction call itself, tracked separately from the actual chat turn (mirrors
 * [CompressionOutcome] for Day 9's summary strategy).
 */
data class FactsOutcome(
    val facts: Map<String, String>,
    val tokensUsed: Int
)

private val FACTS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Implements Day 10's "Sticky Facts / Key-Value Memory" strategy: instead of keeping (or
 * summarizing) raw history, maintain a small `key -> value` map of the important, durable facts
 * from the conversation — goal, constraints, preferences, decisions, agreements — and update it
 * after every user message via a real LLM call that merges the new turn into the existing
 * facts.
 *
 * Kept intentionally close in shape to [HistoryCompressor]: a suspend step ([extract]) that
 * does the actual (LLM-backed) merging, with JSON parsing isolated in [parseFacts] so it can be
 * unit-tested without any network dependency.
 */
class FactsExtractor(private val client: LlmClient) {

    companion object {
        private const val EXTRACTOR_SYSTEM_INSTRUCTION =
            "You maintain a compact key-value memory of the important, durable facts from an " +
                "ongoing conversation: the user's goal, constraints, preferences, decisions, " +
                "and agreements. You will be given the current facts as a JSON object and the " +
                "newest conversation turn(s). Return the UPDATED facts as a single flat JSON " +
                "object (string keys to string values): merge in anything new or changed, drop " +
                "anything the user has explicitly invalidated, and keep everything else " +
                "unchanged. Use short, stable, snake_case keys (e.g. \"goal\", " +
                "\"deadline\", \"preferred_stack\"). Respond with ONLY the JSON object — no " +
                "markdown fences, no commentary, no surrounding text."

        /**
         * Best-effort parse of an LLM reply that is supposed to be a flat JSON object of
         * string-to-string facts. Tolerates a markdown code fence around the JSON (models
         * occasionally add one despite instructions) and non-string values (stringified
         * as-is). Returns `null` for anything that isn't parseable as a JSON object, so
         * callers can fall back to the previous facts instead of losing the sticky memory.
         */
        fun parseFacts(rawText: String): Map<String, String>? {
            val cleaned = rawText.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty()) return null
            return try {
                FACTS_JSON.parseToJsonElement(cleaned).jsonObject.entries.associate { entry ->
                    entry.key to jsonElementToString(entry.value)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun jsonElementToString(element: JsonElement): String = try {
            element.jsonPrimitive.content
        } catch (e: Exception) {
            element.toString()
        }
    }

    /**
     * Calls the LLM to merge [previousFacts] with the newest turn ([recentContext] — typically
     * a short recent tail of history for grounding — plus [newUserMessage], the message that
     * just arrived) into an updated facts map. [model] is the caller's currently selected chat
     * model, reused rather than hard-coded.
     *
     * On a malformed/non-JSON reply, [parseFacts] returns `null` and this method falls back to
     * [previousFacts] unchanged (the extraction cost is still counted, since the call was
     * made) rather than corrupting the sticky memory.
     */
    suspend fun extract(
        previousFacts: Map<String, String>,
        recentContext: List<AgentMessage>,
        newUserMessage: String,
        model: String
    ): Result<FactsOutcome> {
        val previousFactsJson = renderFactsAsJson(previousFacts)
        val transcript = recentContext.joinToString("\n") { message ->
            val speaker = if (message.role == AgentMessage.Role.USER) "User" else "Agent"
            "$speaker: ${message.text}"
        }
        val input = "Current facts (JSON):\n$previousFactsJson\n\n" +
            (if (transcript.isNotBlank()) "Recent context:\n$transcript\n\n" else "") +
            "Newest user message:\nUser: $newUserMessage\n\n" +
            "Write the updated facts JSON object now."

        val spec = LlmRequestSpec(
            model = model,
            input = input,
            systemInstruction = EXTRACTOR_SYSTEM_INSTRUCTION
        )

        val requestTokens = TokenEstimator.estimate(input) +
            TokenEstimator.estimate(EXTRACTOR_SYSTEM_INSTRUCTION)

        return client.complete(spec).map { text ->
            val parsed = parseFacts(text) ?: previousFacts
            FactsOutcome(
                facts = parsed,
                tokensUsed = requestTokens + TokenEstimator.estimate(text)
            )
        }
    }

    /**
     * Renders [facts] as `"- key: value"` lines, ready to be dropped into a prompt ahead of the
     * recent history (see [LlmAgent]). Empty when there are no facts yet.
     */
    fun render(facts: Map<String, String>): String =
        facts.entries.joinToString("\n") { (key, value) -> "- $key: $value" }

    private fun renderFactsAsJson(facts: Map<String, String>): String {
        if (facts.isEmpty()) return "{}"
        val body = facts.entries.joinToString(",") { (key, value) ->
            "${jsonQuote(key)}:${jsonQuote(value)}"
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
