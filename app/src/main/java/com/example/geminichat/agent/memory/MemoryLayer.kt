package com.example.geminichat.agent.memory

/**
 * Day 11: the three memory layers an agent keeps *separately*, each answering a different
 * question about what to remember and for how long:
 *
 * - [SHORT_TERM]: the current dialog itself — the raw turn-by-turn transcript. Scoped to one
 *   conversation branch; wiped by "Clear dialog". This layer is *not* represented as
 *   [MemoryItem]s — it's the existing [com.example.geminichat.agent.AgentMessage] transcript,
 *   listed here only so the three-layer model is complete and explicit.
 * - [WORKING]: data about the *current task* the user and agent are working on together (a
 *   training plan being drafted, a bug being debugged, a document being written) — goals,
 *   constraints, decisions, and open questions that matter only until that task is done.
 *   Scoped to one conversation branch; cleared by "End task", independent of [SHORT_TERM].
 * - [LONG_TERM]: durable facts about the *user* — profile, standing decisions, preferences,
 *   knowledge that should carry over to a brand new task, a brand new branch, or even a
 *   different agent persona. Global (not scoped to a branch); only ever cleared by an explicit
 *   user action, never by starting a new task or a new dialog.
 *
 * Each layer is persisted in its own store (see [MemoryStore]) instead of one shared blob, so
 * "what does the agent remember, and from where" can be inspected and reasoned about layer by
 * layer.
 */
enum class MemoryLayer {
    SHORT_TERM,
    WORKING,
    LONG_TERM
}
