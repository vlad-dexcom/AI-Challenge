package com.example.rag

import com.example.rag.chat.ContextualRewriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptProfileTest {
    private val hits = listOf(hit("a#1", 0.9f, "a.md", "Sec", "Eat 1.6 to 2.2 grams of protein per kilogram."))

    @Test fun defaultProfileKeepsTheOriginalPromptsByteForByte() {
        val d = PromptProfile.DEFAULT
        assertEquals("${RagPromptBuilder.SYSTEM_PROMPT}\n\n${RagPromptBuilder.CITATION_RULES}", RagPromptBuilder.citationSystemPrompt(profile = d))
        assertEquals(RagPromptBuilder.citationSystemPrompt(), RagPromptBuilder.citationSystemPrompt(profile = d))
        assertEquals(
            "${RagPromptBuilder.SYSTEM_PROMPT}\n\n${RagPromptBuilder.CITATION_RULES}\n\n${RagPromptBuilder.DIALOG_RULES}${RagPromptBuilder.STRICT_SUFFIX}",
            RagPromptBuilder.citationSystemPrompt(strict = true, dialog = true, profile = d),
        )
        val ru = "Сколько белка нужно в день?"
        assertEquals(RagPromptBuilder.citationUserPrompt(ru, hits), RagPromptBuilder.citationUserPrompt(ru, hits, profile = d))
        assertFalse(RagPromptBuilder.citationUserPrompt(ru, hits, profile = d).contains("Answer in Russian"))
        assertEquals(LlmQueryRewriter.SYSTEM, LlmQueryRewriter.systemFor(d))
        assertEquals(ContextualRewriter.SYSTEM, ContextualRewriter.systemFor(d))
    }

    @Test fun partialLeverAnswersTheSupportedPartInsteadOfRefusing() {
        val p = PromptProfile(partialAnswers = true)
        val full = RagPromptBuilder.citationSystemPrompt(profile = p)
        assertTrue(full.contains("ONLY when the excerpts contain nothing that answers any part"))
        assertFalse(full.contains("even when they are on a related topic"))
        // the rest of the contract (JSON shape, verbatim quotes) is untouched
        assertTrue(full.contains("copied VERBATIM") && full.contains("\"quotes\""))
    }

    @Test fun compactContractIsShorterAndKeepsTheVerificationRules() {
        val compact = RagPromptBuilder.citationSystemPrompt(profile = PromptProfile(compactContract = true))
        val default = RagPromptBuilder.citationSystemPrompt()
        assertTrue(compact.length < default.length * 0.85)
        for (must in listOf("\"answerable\"", "\"sources\"", "\"quotes\"", "VERBATIM", "chunkId", "original language")) assertTrue(must, compact.contains(must))
        assertTrue(RagPromptBuilder.citationSystemPrompt(profile = PromptProfile.LOCAL).contains("ONLY when the excerpts contain nothing"))
    }

    @Test fun languageHintOnlyForRussianQuestionsAndOnlyWithTheLever() {
        val p = PromptProfile(languageHint = true)
        assertTrue(RagPromptBuilder.citationUserPrompt("Сколько белка нужно?", hits, profile = p).contains("Answer in Russian (keep the quotes in English)."))
        assertFalse(RagPromptBuilder.citationUserPrompt("How much protein?", hits, profile = p).contains("Answer in Russian"))
        assertFalse(RagPromptBuilder.citationUserPrompt("Сколько белка нужно?", hits, profile = PromptProfile.DEFAULT).contains("Answer in Russian"))
        assertTrue(RagPromptBuilder.isRussian("Привет, как дела") && !RagPromptBuilder.isRussian("hello"))
    }

    @Test fun rewriteExamplesLeverSwitchesBothRewriters() {
        val p = PromptProfile(rewriteExamples = true)
        assertEquals(LlmQueryRewriter.LOCAL_SYSTEM, LlmQueryRewriter.systemFor(p))
        assertTrue(LlmQueryRewriter.LOCAL_SYSTEM.contains("Query:") && LlmQueryRewriter.LOCAL_SYSTEM.contains("do not add technique, protocol or product names"))
        assertTrue(ContextualRewriter.systemFor(p).startsWith(LlmQueryRewriter.LOCAL_SYSTEM))
        assertTrue(ContextualRewriter.systemFor(p).contains("task memory"))
    }

    @Test fun profileReachesTheGeneratorThroughTheAnswerer() = runTest {
        val gen = ScriptedGenerator(mutableListOf(Result.success("x"), Result.success("x"), Result.success("x"), Result.success("x")))
        CitedAnswerer(gen, PromptProfile.LOCAL).answer("Сколько белка нужно в день?", hits)
        assertTrue(gen.calls.first().first!!.contains("ONLY when the excerpts contain nothing"))
        assertTrue(gen.calls.first().second.endsWith("Answer in Russian (keep the quotes in English)."))
        val plain = ScriptedGenerator(mutableListOf(Result.success("x"), Result.success("x"), Result.success("x"), Result.success("x")))
        CitedAnswerer(plain).answer("Сколько белка нужно в день?", hits)
        assertEquals(RagPromptBuilder.citationSystemPrompt(), plain.calls.first().first)
    }

    @Test fun parsesPresetsAndLeverLists() {
        assertEquals(PromptProfile.DEFAULT, PromptProfile.parse(null))
        assertEquals(PromptProfile.DEFAULT, PromptProfile.parse("default"))
        assertEquals(PromptProfile.LOCAL, PromptProfile.parse("LOCAL"))
        assertEquals(PromptProfile(rewriteExamples = true, languageHint = true), PromptProfile.parse("rewrite, lang"))
        assertTrue(runCatching { PromptProfile.parse("rewrite,bogus") }.isFailure)
        assertTrue(PromptProfile.DEFAULT.isDefault && !PromptProfile.LOCAL.isDefault)
    }
}
