package com.example.geminichat

import com.example.geminichat.agent.TokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ChatMessage(
    val text: String,
    val isFromUser: Boolean,
    /**
     * Token accounting for this turn; only set (in-memory) on agent replies (see
     * [TokenUsage]). Marked [Transient] because [TokenUsage] isn't `@Serializable` and this is
     * ephemeral, recomputed-per-call data — it's simply dropped by [ChatHistoryStore] and
     * comes back `null` for messages restored from a previous app run.
     */
    @Transient
    val tokenUsage: TokenUsage? = null,
    /**
     * Day 14: non-empty when this reply is a deterministic refusal produced by
     * [com.example.geminichat.agent.invariant.InvariantGuard] (see
     * [com.example.geminichat.agent.AgentResponse.refusedByInvariantIds]). Unlike [tokenUsage]
     * this *is* serialized, so the refusal badge survives a restore from
     * [ChatHistoryStore] just like the message text does.
     */
    val refusedByInvariantIds: List<String> = emptyList(),
    /**
     * Day 15: non-null when this reply is a deterministic refusal produced by
     * [com.example.geminichat.agent.task.TaskStageGuard]. Serialized so it survives a restore.
     */
    val blockedByStageName: String? = null
)
