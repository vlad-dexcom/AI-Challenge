package com.example.geminichat.agent.profile

import kotlinx.serialization.Serializable

/**
 * Day 12 personalization: how much the user already knows, used to calibrate vocabulary and
 * the amount of hand-holding in an answer (not a permission level — every [ExpertiseLevel] can
 * ask about anything).
 */
enum class ExpertiseLevel { BEGINNER, INTERMEDIATE, ADVANCED }

/**
 * Every field of [UserProfile] a person can edit or [PreferenceAdvisor] can propose a change
 * for. Kept as its own enum (rather than editing fields by raw string name) so the UI's field
 * list and the advisor's allowed targets can never drift apart — see
 * [com.example.geminichat.agent.profile.PreferenceAdvisor].
 */
enum class ProfileField {
    DISPLAY_NAME,
    ABOUT,
    LANGUAGE,
    EXPERTISE,
    TONE,
    FORMAT,
    MAX_ANSWER_SENTENCES,
    NOTES,
    /** Adds one entry to [UserProfile.constraints] (a set, not a single value to overwrite —
     * unlike every other field above). */
    ADD_CONSTRAINT,
    /** Removes one matching entry from [UserProfile.constraints]. */
    REMOVE_CONSTRAINT
}

/**
 * Day 12: **one** global, editable profile — declarative personalization directives (who the
 * user is, and how they want to be answered), distinct from the Day 11 memory layers, which
 * hold facts the agent *learned* rather than preferences the user *declared*.
 *
 * There is exactly one profile for the whole app (like [com.example.geminichat.agent.memory.LongTermMemoryStore]'s
 * long-term memory) — it can be edited, but there is no catalog of profiles to switch between
 * and no per-branch scoping. See [com.example.geminichat.agent.profile.ProfileRenderer] for how
 * this becomes a prompt block, and [com.example.geminichat.agent.LlmAgent] for how that block is
 * folded into the system instruction on every request.
 *
 * @property displayName how to address the user, e.g. "Аня"; empty if unset.
 * @property about free-form context about the user (life situation, environment, goals).
 * @property language the language answers should be written in, e.g. "русский"; empty means
 *   "no preference stated" — the agent falls back to whatever it would otherwise do.
 * @property expertise calibrates vocabulary/detail level; `null` means "not stated".
 * @property tone desired conversational tone, e.g. "дружелюбно, поддерживающе".
 * @property format desired answer shape, e.g. "короткий список шагов".
 * @property maxAnswerSentences a soft length guidance passed to the model as an instruction —
 *   *not* a hard truncation of the reply (the model may not obey it exactly); `null` means "no
 *   limit stated".
 * @property constraints hard limits the agent must always respect, e.g. "травма колена — без
 *   прыжков"; rendered as a bulleted list distinct from [notes].
 * @property notes free-form additional preferences that don't fit another field.
 */
@Serializable
data class UserProfile(
    val displayName: String = "",
    val about: String = "",
    val language: String = "",
    val expertise: ExpertiseLevel? = null,
    val tone: String = "",
    val format: String = "",
    val maxAnswerSentences: Int? = null,
    val constraints: List<String> = emptyList(),
    val notes: String = ""
) {
    /** Whether every field is at its default — used by [ProfileRenderer] to render nothing
     * and by tests to assert Day 12 is a no-op when the user hasn't set anything up yet. */
    fun isEmpty(): Boolean = this == EMPTY

    companion object {
        /** No personalization directives at all — [ProfileRenderer] renders this as an empty
         * string, so the agent behaves exactly as it did before Day 12. */
        val EMPTY = UserProfile()

        /**
         * A filled-in example shown the first time the app runs (instead of a blank form), so
         * the user edits a realistic starting point rather than facing empty fields. Not a
         * "preset" to pick from — there is only ever one profile; this is just its initial
         * content until the user changes it.
         */
        val STARTER = UserProfile(
            displayName = "Друг",
            about = "Новичок, тренируется дома, без специального инвентаря.",
            language = "русский",
            expertise = ExpertiseLevel.BEGINNER,
            tone = "дружелюбно и поддерживающе",
            format = "короткий пронумерованный список шагов",
            maxAnswerSentences = 6,
            constraints = listOf("нет доступа к тренажёрному залу"),
            notes = ""
        )

        /**
         * Ready-made profiles for exercising personalization end to end: each one pushes a
         * different combination of [language], [expertise], [tone], [format],
         * [maxAnswerSentences] and [constraints], so picking two presets in a row should make
         * the agent's next reply visibly different — see the Settings screen's preset row and
         * `docs/day12-personalization-test-scenario.md`.
         */
        val PRESETS: List<ProfilePreset> = listOf(
            ProfilePreset("Новичок дома", STARTER),
            ProfilePreset(
                "Продвинутый атлет (EN)",
                UserProfile(
                    displayName = "Alex",
                    about = "Experienced lifter, trains at a fully equipped gym 5x/week.",
                    language = "English",
                    expertise = ExpertiseLevel.ADVANCED,
                    tone = "direct, no small talk",
                    format = "bullet points only, with sets/reps/%1RM numbers",
                    maxAnswerSentences = 2,
                    constraints = emptyList(),
                    notes = "always give concrete numbers, never vague advice"
                )
            ),
            ProfilePreset(
                "Осторожный новичок (травма)",
                UserProfile(
                    displayName = "Марина",
                    about = "Восстанавливается после травмы поясницы, тренируется под присмотром врача.",
                    language = "русский",
                    expertise = ExpertiseLevel.BEGINNER,
                    tone = "очень бережно, подробно объяснять каждый шаг",
                    format = "подробное пошаговое объяснение техники, без сокращений",
                    maxAnswerSentences = null,
                    constraints = listOf("боль в пояснице", "не приседать глубоко", "не бегать"),
                    notes = "всегда напоминать размяться перед началом"
                )
            ),
            ProfilePreset(
                "Минимализм",
                UserProfile(
                    displayName = "",
                    about = "",
                    language = "",
                    expertise = ExpertiseLevel.INTERMEDIATE,
                    tone = "нейтрально, без вступлений",
                    format = "только пункты, без пояснений",
                    maxAnswerSentences = 2,
                    constraints = emptyList(),
                    notes = "не повторять то, что уже было сказано раньше"
                )
            )
        )
    }
}

/** One entry of [UserProfile.PRESETS]: a human-readable [label] paired with the full
 * [profile] it loads when picked. */
data class ProfilePreset(val label: String, val profile: UserProfile)
