package com.example.geminichat.agent.memory

import com.example.geminichat.agent.TokenEstimator

/**
 * Result of [MemoryAssembler.assemble]: the rendered prompt blocks for the two structured
 * layers (empty string when a layer has no items — see [MemoryAssembler]), plus their
 * individually-estimated token costs so the price of *each layer* is visible on its own,
 * mirroring [com.example.geminichat.agent.TokenUsage.summaryTokens] /
 * `factsTokens` for Day 9/10's single-layer equivalents.
 */
data class AssembledMemory(
    val longTermBlock: String,
    val workingBlock: String,
    val longTermTokens: Int,
    val workingTokens: Int
)

/**
 * Day 11: renders [MemoryLayer.LONG_TERM] and [MemoryLayer.WORKING] as distinct, clearly
 * labeled prompt blocks — the short-term layer ([MemoryLayer.SHORT_TERM]) is *not* handled
 * here, since it's the existing raw transcript / Day 9 summary already assembled by
 * [com.example.geminichat.agent.LlmAgent].
 *
 * Order matters and is fixed: long-term (most durable, least likely to change) first, then
 * working (current task), so the model reads from "who is this user" down to "what are we
 * doing right now" before the recent conversation itself.
 */
object MemoryAssembler {

    private const val LONG_TERM_HEADER = "Long-term memory (user profile, decisions, knowledge):"
    private const val WORKING_HEADER = "Working memory (current task):"

    fun assemble(longTerm: MemorySnapshot, working: MemorySnapshot): AssembledMemory {
        val longTermRendered = longTerm.render()
        val workingRendered = working.render()

        val longTermBlock = if (longTermRendered.isEmpty()) "" else
            "$LONG_TERM_HEADER\n$longTermRendered"
        val workingBlock = if (workingRendered.isEmpty()) "" else
            "$WORKING_HEADER\n$workingRendered"

        return AssembledMemory(
            longTermBlock = longTermBlock,
            workingBlock = workingBlock,
            longTermTokens = TokenEstimator.estimate(longTermBlock),
            workingTokens = TokenEstimator.estimate(workingBlock)
        )
    }
}
