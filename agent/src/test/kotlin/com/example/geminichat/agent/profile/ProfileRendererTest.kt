package com.example.geminichat.agent.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRendererTest {

    @Test
    fun `an empty profile renders an empty string`() {
        assertEquals("", ProfileRenderer.render(UserProfile.EMPTY))
    }

    @Test
    fun `a filled profile renders every set field`() {
        val profile = UserProfile(
            displayName = "Аня",
            about = "тренируется дома",
            language = "русский",
            expertise = ExpertiseLevel.BEGINNER,
            tone = "дружелюбно",
            format = "короткий список",
            maxAnswerSentences = 5,
            constraints = listOf("нет штанги", "травма колена"),
            notes = "не любит кардио"
        )

        val rendered = ProfileRenderer.render(profile)

        assertTrue(rendered.contains("Аня"))
        assertTrue(rendered.contains("тренируется дома"))
        assertTrue(rendered.contains("русский"))
        assertTrue(rendered.contains("beginner"))
        assertTrue(rendered.contains("дружелюбно"))
        assertTrue(rendered.contains("короткий список"))
        assertTrue(rendered.contains("5"))
        assertTrue(rendered.contains("нет штанги"))
        assertTrue(rendered.contains("травма колена"))
        assertTrue(rendered.contains("не любит кардио"))
    }

    @Test
    fun `a profile with only one field set renders only that field`() {
        val profile = UserProfile(tone = "сухо и по делу")

        val rendered = ProfileRenderer.render(profile)

        assertTrue(rendered.contains("сухо и по делу"))
        assertTrue(!rendered.contains("About the user"))
        assertTrue(!rendered.contains("Preferred answer format"))
        assertTrue(!rendered.contains("constraints", ignoreCase = true))
    }

    @Test
    fun `rendering the same profile twice produces identical output`() {
        val profile = UserProfile(displayName = "Марк", tone = "технично", constraints = listOf("без прыжков"))

        assertEquals(ProfileRenderer.render(profile), ProfileRenderer.render(profile))
    }
}
