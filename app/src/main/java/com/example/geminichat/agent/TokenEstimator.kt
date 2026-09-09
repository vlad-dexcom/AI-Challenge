package com.example.geminichat.agent

/**
 * Offline, dependency-free approximation of a model tokenizer.
 *
 * There is no real Gemini tokenizer available on-device (and the Interactions API response
 * used by this app does not currently echo back token counts), so [estimate] combines two
 * cheap heuristics and takes the larger:
 * - word/punctuation count (`[A-Za-z0-9]+` runs and individual punctuation characters), since
 *   a real BPE tokenizer rarely merges more than one "word" per token, and
 * - `chars / 4`, the commonly-cited average tokens-per-character ratio for English text,
 *   which also accounts for long words/identifiers a word-count alone would undercount.
 *
 * This is intentionally approximate: it exists to *show* how token counts grow with a
 * conversation and to guard against obviously oversized requests, not to bill real usage.
 */
object TokenEstimator {
    private val WORD_OR_PUNCTUATION = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]")

    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val wordBased = WORD_OR_PUNCTUATION.findAll(text).count()
        val charBased = (text.length + 3) / 4
        return maxOf(wordBased, charBased)
    }

    fun estimate(texts: List<String>): Int = texts.sumOf { estimate(it) }
}
