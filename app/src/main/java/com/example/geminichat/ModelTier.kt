package com.example.geminichat

/**
 * The three model "strength" tiers compared side by side on the comparison screen, plus a
 * rough USD-per-million-token pricing table used to estimate cost per response.
 *
 * Pricing is **hardcoded and approximate** — official published rates for these preview/latest
 * model ids are not consistently available, so treat costs shown in the UI as estimates only,
 * not billing-accurate figures. Update the constants below if Google publishes firmer numbers.
 */
enum class ModelTier(
    val label: String,
    val model: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
) {
    STRONG(
        label = "Strong",
        model = "gemini-3.1-pro-preview",
        inputPricePerMillion = 2.00,
        outputPricePerMillion = 12.00
    ),
    MEDIUM(
        label = "Medium",
        model = "gemini-3.5-flash",
        inputPricePerMillion = 0.30,
        outputPricePerMillion = 2.50
    ),
    WEAK(
        label = "Weak",
        model = "gemini-3.1-flash-lite",
        inputPricePerMillion = 0.10,
        outputPricePerMillion = 0.40
    );

    /**
     * Estimated cost in USD for a single response. Thought tokens are billed as output tokens
     * by Google, so they're added to [Usage.totalOutputTokens] here.
     */
    fun estimateCostUsd(usage: Usage?): Double? {
        if (usage == null) return null
        val inputTokens = usage.totalInputTokens ?: return null
        val outputTokens = (usage.totalOutputTokens ?: 0) + (usage.totalThoughtTokens ?: 0)
        return (inputTokens * inputPricePerMillion / 1_000_000.0) +
            (outputTokens * outputPricePerMillion / 1_000_000.0)
    }
}
