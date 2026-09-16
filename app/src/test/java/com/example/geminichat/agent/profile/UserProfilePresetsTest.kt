package com.example.geminichat.agent.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [UserProfile.PRESETS]: the Settings screen's preset row lets a tester load one of
 * these with a single tap to check personalization across maximally different profiles, so
 * each preset must actually be distinct and must render into a non-empty prompt block.
 */
class UserProfilePresetsTest {

    @Test
    fun `there is more than one preset`() {
        assertTrue(UserProfile.PRESETS.size > 1)
    }

    @Test
    fun `every preset has a unique, non-blank label`() {
        val labels = UserProfile.PRESETS.map { it.label }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.distinct().size, labels.size)
    }

    @Test
    fun `every preset profile is non-empty and renders a prompt block`() {
        UserProfile.PRESETS.forEach { preset ->
            assertTrue("${preset.label} should not be an empty profile", !preset.profile.isEmpty())
            assertTrue(
                "${preset.label} should render a non-empty prompt block",
                ProfileRenderer.render(preset.profile).isNotEmpty()
            )
        }
    }

    @Test
    fun `presets are pairwise distinct so switching between them is observable`() {
        val profiles = UserProfile.PRESETS.map { it.profile }

        for (i in profiles.indices) {
            for (j in profiles.indices) {
                if (i != j) {
                    assertNotEquals(profiles[i], profiles[j])
                }
            }
        }
    }

    @Test
    fun `presets cover both stated and unstated language, and varied expertise`() {
        val languages = UserProfile.PRESETS.map { it.profile.language }
        val expertiseLevels = UserProfile.PRESETS.map { it.profile.expertise }

        assertTrue("expected at least one preset with no stated language", languages.any { it.isBlank() })
        assertTrue("expected at least one preset with a stated language", languages.any { it.isNotBlank() })
        assertTrue("expected more than one distinct expertise level across presets", expertiseLevels.distinct().size > 1)
    }
}
