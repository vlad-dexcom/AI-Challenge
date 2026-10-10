package com.example.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectionTest {
    @Test fun russianQuestionsWithEnglishTermsStayRussian() {
        assertEquals("ru", IdkResponder.detectLanguage("Кто выиграл CrossFit Games 2024?"))
        assertEquals("ru", IdkResponder.detectLanguage("Какие короткие cues для тяги перечислены в конце гайда?"))
        assertEquals("ru", IdkResponder.detectLanguage("Что такое NEAT и почему он падает на диете?"))
    }

    @Test fun englishAndEmptyTextAreEnglish() {
        assertEquals("en", IdkResponder.detectLanguage("What is the current men's raw deadlift world record?"))
        assertEquals("en", IdkResponder.detectLanguage("How do I say привет in a gym?"))
        assertEquals("en", IdkResponder.detectLanguage(""))
        assertEquals("en", IdkResponder.detectLanguage("2024 ???"))
    }

    @Test fun pureRussianIsRussian() {
        assertEquals("ru", IdkResponder.detectLanguage("Сколько белка нужно в день?"))
    }
}
