package com.example.geminichat.agent

/**
 * Day 10: the set of interchangeable context-management strategies the same chat can be run
 * with, switchable at any point without losing the conversation itself. Each strategy answers
 * the same question — "what do we actually send to the model as context for this turn?" —
 * differently:
 *
 * - [FULL_HISTORY]: send the entire raw transcript every turn (the pre-Day-9 baseline). Never
 *   drops anything, but prompt size — and cost — grows without bound.
 * - [SLIDING_WINDOW]: send only the last [SLIDING_WINDOW_SIZE] messages verbatim; everything
 *   older is simply discarded (no summarization, no LLM cost beyond the chat turn itself).
 *   Cheapest and simplest, but can visibly "forget" facts established earlier in a long chat.
 * - [FACTS]: maintain a small key-value memory of important facts (goal, constraints,
 *   preferences, decisions — see [FactsExtractor]), updated after every user message, and send
 *   `facts + last N messages` instead of the full/aged-out history.
 * - [SUMMARY]: Day 9's running-summary compression (see [HistoryCompressor]) — folds aged-out
 *   turns into a single narrative summary via an LLM call.
 */
enum class ContextStrategy(val label: String) {
    FULL_HISTORY("Full history"),
    SLIDING_WINDOW("Sliding window"),
    FACTS("Facts (key-value memory)"),
    SUMMARY("Summary (compression)");

    companion object {
        val DEFAULT = SUMMARY

        /** How many most-recent messages [SLIDING_WINDOW] keeps verbatim. */
        const val SLIDING_WINDOW_SIZE = 8

        fun byName(name: String): ContextStrategy =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
