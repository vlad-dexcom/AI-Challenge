package com.example.geminichat.agent.invariant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantGuardTest {

    private val set = InvariantSet.DEFAULTS

    @Test
    fun `a barbell request conflicts with home-equipment-only`() {
        val conflicts = InvariantGuard.check(set, "Составь программу на неделю со штангой в зале")
        assertTrue(conflicts.any { it.invariant.id == "home-equipment-only" })
    }

    @Test
    fun `6 sessions per week conflicts with max-3-sessions`() {
        val conflicts = InvariantGuard.check(set, "Хочу тренироваться 6 раз в неделю по 2 часа")
        assertTrue(conflicts.any { it.invariant.id == "max-3-sessions" })
    }

    @Test
    fun `asking about anabolic steroids conflicts with the locked no-ped invariant`() {
        val conflicts = InvariantGuard.check(set, "Что принять из анаболиков для набора массы?")
        assertTrue(conflicts.any { it.invariant.id == "no-ped" && it.invariant.locked })
    }

    @Test
    fun `a diet at 900 kcal conflicts with min-calories`() {
        val conflicts = InvariantGuard.check(set, "Составь диету на 900 ккал в день")
        assertTrue(conflicts.any { it.invariant.id == "min-calories" })
    }

    @Test
    fun `a diet at 1800 kcal does not conflict with min-calories`() {
        val conflicts = InvariantGuard.check(set, "Составь диету на 1800 ккал в день")
        assertTrue(conflicts.none { it.invariant.id == "min-calories" })
    }

    @Test
    fun `a diet at exactly 1200 kcal does not conflict with min-calories`() {
        val conflicts = InvariantGuard.check(set, "Составь диету на 1200 ккал в день")
        assertTrue(conflicts.none { it.invariant.id == "min-calories" })
    }

    @Test
    fun `an ordinary bodyweight request has no conflicts`() {
        val conflicts = InvariantGuard.check(set, "Дай программу с отжиманиями и планкой дома")
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `a disabled invariant never triggers even if the trigger text matches`() {
        val withHomeEquipmentDisabled = set.copy(
            invariants = set.invariants.map {
                if (it.id == "home-equipment-only") it.copy(enabled = false) else it
            }
        )
        val conflicts = InvariantGuard.check(withHomeEquipmentDisabled, "Хочу заниматься со штангой в зале")
        assertTrue(conflicts.none { it.invariant.id == "home-equipment-only" })
    }

    @Test
    fun `a request that violates two invariants at once returns both conflicts`() {
        val conflicts = InvariantGuard.check(
            set,
            "Хочу тренироваться со штангой в зале 6 раз в неделю"
        )
        assertTrue(conflicts.any { it.invariant.id == "home-equipment-only" })
        assertTrue(conflicts.any { it.invariant.id == "max-3-sessions" })
    }

    @Test
    fun `matching is case-insensitive and tolerates the yo-yeh spelling variant`() {
        val conflicts = InvariantGuard.check(set, "ШТАНГА В ТРЕНАЖЁРНОМ ЗАЛЕ")
        assertTrue(conflicts.any { it.invariant.id == "home-equipment-only" })
    }

    @Test
    fun `refusalText names the invariant id, rule, rationale, and alternative`() {
        val conflicts = InvariantGuard.check(set, "Хочу заниматься со штангой в зале")
        val text = InvariantGuard.refusalText(conflicts)

        assertTrue(text.contains("home-equipment-only"))
        assertTrue(text.contains(set.byId("home-equipment-only")!!.rationale))
        assertTrue(text.contains(set.byId("home-equipment-only")!!.alternative))
    }

    @Test
    fun `refusalText marks a locked invariant as non-negotiable and an editable one as toggleable`() {
        val lockedConflicts = InvariantGuard.check(set, "Что принять из анаболиков?")
        val lockedText = InvariantGuard.refusalText(lockedConflicts)
        assertTrue(lockedText.contains("закреплено"))

        val editableConflicts = InvariantGuard.check(set, "Хочу заниматься со штангой в зале")
        val editableText = InvariantGuard.refusalText(editableConflicts)
        assertTrue(editableText.contains("выключить"))
    }

    @Test
    fun `refusalText always suggests rephrasing in case of a false positive`() {
        val conflicts = InvariantGuard.check(set, "Хочу заниматься со штангой в зале")
        val text = InvariantGuard.refusalText(conflicts)
        assertTrue(text.contains("переформулируй"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refusalText requires at least one conflict`() {
        InvariantGuard.refusalText(emptyList())
    }
}
