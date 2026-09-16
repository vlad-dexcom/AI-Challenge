package com.example.geminichat.agent.memory

import kotlinx.serialization.Serializable

/**
 * Where a [MemoryItem] came from — used so [MemoryRouter] never silently overwrites something
 * the user explicitly chose to remember (see [MemoryItem.pinned]).
 *
 * - [ROUTER]: written or last updated by [MemoryRouter]'s automatic classification of a chat
 *   turn.
 * - [USER]: written or last confirmed by an explicit manual action in the UI (promote, add,
 *   edit). Items with this source are [pinned][MemoryItem.pinned] by default.
 */
enum class MemorySource { ROUTER, USER }

/**
 * A single fact living in [MemoryLayer.WORKING] or [MemoryLayer.LONG_TERM] — the two layers
 * that are structured key-value memory (as opposed to [MemoryLayer.SHORT_TERM], which is just
 * the raw/summarized transcript).
 *
 * @property key short, stable, snake_case identifier (mirrors Day 10's
 *   [com.example.geminichat.agent.FactsExtractor] key convention), e.g. `"goal_event"`.
 * @property value the fact's current value, e.g. `"полумарафон"`.
 * @property source who last wrote this item — see [MemorySource].
 * @property turn the 1-based user-message turn index this item was last written/confirmed on,
 *   for the memory inspector UI ("added on turn 3").
 * @property pinned when `true`, [MemoryRouter] must leave this item alone (it can still be
 *   read into the prompt, promoted, or deleted manually) — always `true` for [MemorySource.USER]
 *   items, so a manual override always wins over automatic classification.
 */
@Serializable
data class MemoryItem(
    val key: String,
    val value: String,
    val source: MemorySource = MemorySource.ROUTER,
    val turn: Int = 0,
    val pinned: Boolean = false
)

/**
 * The persisted contents of one structured memory layer ([MemoryLayer.WORKING] or
 * [MemoryLayer.LONG_TERM]), keyed by [MemoryItem.key] for cheap upsert/lookup. Kept as its own
 * type (rather than a bare `Map`) so [MemoryStore] implementations have a stable serialization
 * shape to read/write independently of the other layers.
 */
@Serializable
data class MemorySnapshot(
    val items: Map<String, MemoryItem> = emptyMap()
) {
    /** Renders as `"- key: value"` lines, ready to drop into a prompt block (see
     * [MemoryAssembler]). Empty string when there are no items yet. */
    fun render(): String = items.values
        .sortedBy { it.key }
        .joinToString("\n") { item -> "- ${item.key}: ${item.value}" }

    companion object {
        val EMPTY = MemorySnapshot()
    }
}
