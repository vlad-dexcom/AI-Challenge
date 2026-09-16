package com.example.geminichat.agent.profile

import com.example.geminichat.agent.LlmClient
import com.example.geminichat.agent.LlmRequestSpec
import com.example.geminichat.agent.TokenEstimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One proposed edit to [UserProfile], produced by [PreferenceAdvisor.suggest]: "change
 * [field] to [value]", with [reason] shown to the user so "Apply"/"Dismiss" is an informed
 * choice, never a silent auto-write (see [com.example.geminichat.ChatViewModel.onApplySuggestion]).
 */
data class PreferenceSuggestion(
    val field: ProfileField,
    val value: String,
    val reason: String
)

private val ADVISOR_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Day 12's hybrid update path for [UserProfile]: the profile is edited by hand in the UI, but
 * after a user turn that sounds like a stated preference ("отвечай короче", "без штанги"),
 * one LLM call proposes a single field change. The suggestion is never applied automatically —
 * [com.example.geminichat.ChatViewModel] surfaces it as a banner the user must explicitly
 * apply or dismiss, so [UserProfile] only ever changes with the user's confirmation (manual
 * edit) or their one-tap approval (advisor suggestion), never silently.
 *
 * Deliberately a *separate* LLM call from [com.example.geminichat.agent.memory.MemoryRouter]:
 * routing decides what the agent learned (facts), this decides whether the user just declared
 * a personalization preference (how to be answered) — different questions, kept independently
 * testable and independently priced (see [PreferenceAdvisorOutcome.tokensUsed]).
 */
class PreferenceAdvisor(private val client: LlmClient) {

    companion object {
        private const val SYSTEM_INSTRUCTION =
            "You watch a chat between a user and an assistant for the user stating a " +
                "PERSONALIZATION PREFERENCE about how they want to be answered from now on " +
                "(e.g. tone, format, answer length, expertise level, language, a hard " +
                "constraint the assistant must always respect, or how to address them). You " +
                "will be given the user's current profile as JSON and their newest message. " +
                "If the newest message states or clearly implies such a preference, respond " +
                "with a single JSON object: {\"field\": one of " +
                "[\"display_name\",\"about\",\"language\",\"expertise\",\"tone\",\"format\"," +
                "\"max_answer_sentences\",\"notes\",\"add_constraint\",\"remove_constraint\"], " +
                "\"value\": the new value as a plain string, \"reason\": a short explanation " +
                "of why you inferred this}. Use \"max_answer_sentences\" only for whole " +
                "numbers as a string. Use \"add_constraint\"/\"remove_constraint\" for hard " +
                "limits (injuries, unavailable equipment, topics to avoid), with \"value\" " +
                "being the constraint text. If the newest message states no such preference, " +
                "respond with exactly the JSON literal null. Respond with ONLY the JSON — no " +
                "markdown fences, no commentary."

        /**
         * Parses an advisor reply into a [PreferenceSuggestion], or `null` when the reply is
         * the JSON literal `null`, unparseable, or names a field/value this app doesn't
         * understand — any of those cases fail safe by proposing nothing rather than crashing
         * or corrupting the profile.
         */
        internal fun parseSuggestion(rawText: String): PreferenceSuggestion? {
            val cleaned = rawText.trim()
                .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (cleaned.isEmpty() || cleaned == "null") return null
            return try {
                val root = ADVISOR_JSON.parseToJsonElement(cleaned).jsonObject
                val fieldRaw = root["field"]?.jsonPrimitive?.content ?: return null
                val value = root["value"]?.jsonPrimitive?.content?.trim().orEmpty()
                val reason = root["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (value.isEmpty()) return null
                val field = fieldToProfileField(fieldRaw) ?: return null
                PreferenceSuggestion(field = field, value = value, reason = reason)
            } catch (e: Exception) {
                null
            }
        }

        /** Maps the advisor's snake_case field name to a [ProfileField]; unrecognized names fail
         * safe by returning `null` (no suggestion) rather than guessing. */
        internal fun fieldToProfileField(raw: String): ProfileField? = when (raw.trim().lowercase()) {
            "display_name" -> ProfileField.DISPLAY_NAME
            "about" -> ProfileField.ABOUT
            "language" -> ProfileField.LANGUAGE
            "expertise" -> ProfileField.EXPERTISE
            "tone" -> ProfileField.TONE
            "format" -> ProfileField.FORMAT
            "max_answer_sentences" -> ProfileField.MAX_ANSWER_SENTENCES
            "notes" -> ProfileField.NOTES
            "add_constraint" -> ProfileField.ADD_CONSTRAINT
            "remove_constraint" -> ProfileField.REMOVE_CONSTRAINT
            else -> null
        }
    }

    /**
     * Calls the LLM to look for a stated preference in [newUserMessage], given the current
     * [profile]. Never throws: any transport failure surfaces as [Result.failure] the caller
     * can ignore (a missed suggestion never breaks the chat turn it rides along with).
     */
    suspend fun suggest(profile: UserProfile, newUserMessage: String, model: String): Result<PreferenceAdvisorOutcome> {
        val input = "Current profile (JSON):\n${renderAsJson(profile)}\n\n" +
            "Newest user message:\nUser: $newUserMessage\n\n" +
            "Respond now."
        val spec = LlmRequestSpec(model = model, input = input, systemInstruction = SYSTEM_INSTRUCTION)
        val requestTokens = TokenEstimator.estimate(input) + TokenEstimator.estimate(SYSTEM_INSTRUCTION)

        return client.complete(spec).map { text ->
            PreferenceAdvisorOutcome(
                suggestion = parseSuggestion(text),
                tokensUsed = requestTokens + TokenEstimator.estimate(text)
            )
        }
    }

    private fun renderAsJson(profile: UserProfile): String {
        val fields = linkedMapOf(
            "display_name" to profile.displayName,
            "about" to profile.about,
            "language" to profile.language,
            "expertise" to (profile.expertise?.name?.lowercase() ?: ""),
            "tone" to profile.tone,
            "format" to profile.format,
            "max_answer_sentences" to (profile.maxAnswerSentences?.toString() ?: ""),
            "notes" to profile.notes
        )
        val body = fields.entries.joinToString(",") { (k, v) -> "${jsonQuote(k)}:${jsonQuote(v)}" }
        val constraints = profile.constraints.joinToString(",") { jsonQuote(it) }
        return "{$body,\"constraints\":[$constraints]}"
    }

    private fun jsonQuote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "\"$escaped\""
    }
}

/** Outcome of one [PreferenceAdvisor.suggest] call: [suggestion] is `null` when the newest
 * message stated no preference; [tokensUsed] is priced separately from the chat turn and
 * [com.example.geminichat.agent.memory.MemoryRouter]'s own routing cost. */
data class PreferenceAdvisorOutcome(
    val suggestion: PreferenceSuggestion?,
    val tokensUsed: Int
)
