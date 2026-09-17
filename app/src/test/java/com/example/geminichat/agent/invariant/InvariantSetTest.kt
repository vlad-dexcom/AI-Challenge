package com.example.geminichat.agent.invariant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantSetTest {

    private val locked = Invariant(
        id = "locked-one",
        category = InvariantCategory.MEDICAL,
        statement = "Never do the locked thing.",
        rationale = "Core safety rule.",
        locked = true
    )

    private val editable = Invariant(
        id = "editable-one",
        category = InvariantCategory.EQUIPMENT,
        statement = "Prefer home equipment.",
        rationale = "User preference."
    )

    private val baseSet = InvariantSet(listOf(locked, editable))

    @Test
    fun `remove rejects a locked invariant with an explanatory reason`() {
        val result = InvariantRules.remove(baseSet, "locked-one")

        assertTrue(result is InvariantChangeResult.Rejected)
        val reason = (result as InvariantChangeResult.Rejected).reason
        assertTrue(reason.contains("locked-one"))
        // The set itself must be provably unchanged, not just the rejection message.
        assertTrue(baseSet.byId("locked-one") != null)
    }

    @Test
    fun `setEnabled false rejects a locked invariant`() {
        val result = InvariantRules.setEnabled(baseSet, "locked-one", false)

        assertTrue(result is InvariantChangeResult.Rejected)
        assertTrue(baseSet.byId("locked-one")!!.enabled)
    }

    @Test
    fun `setEnabled true on a locked invariant is a harmless no-op-equivalent Applied`() {
        // Re-enabling something already locked+enabled should never be rejected — only turning
        // it *off* is disallowed.
        val result = InvariantRules.setEnabled(baseSet, "locked-one", true)
        assertTrue(result is InvariantChangeResult.Applied)
    }

    @Test
    fun `setEnabled false on an editable invariant is applied`() {
        val result = InvariantRules.setEnabled(baseSet, "editable-one", false)

        assertTrue(result is InvariantChangeResult.Applied)
        val updated = (result as InvariantChangeResult.Applied).set
        assertFalse(updated.byId("editable-one")!!.enabled)
        assertTrue(updated.enabledInvariants.none { it.id == "editable-one" })
    }

    @Test
    fun `remove on an editable invariant is applied`() {
        val result = InvariantRules.remove(baseSet, "editable-one")

        assertTrue(result is InvariantChangeResult.Applied)
        val updated = (result as InvariantChangeResult.Applied).set
        assertNull(updated.byId("editable-one"))
    }

    @Test
    fun `add rejects a duplicate id`() {
        val result = InvariantRules.add(
            baseSet,
            Invariant(id = "editable-one", category = InvariantCategory.SCOPE, statement = "x", rationale = "y")
        )
        assertTrue(result is InvariantChangeResult.Rejected)
    }

    @Test
    fun `add rejects a blank statement`() {
        val result = InvariantRules.add(
            baseSet,
            Invariant(id = "new-one", category = InvariantCategory.SCOPE, statement = "  ", rationale = "y")
        )
        assertTrue(result is InvariantChangeResult.Rejected)
    }

    @Test
    fun `add applies a valid new invariant`() {
        val result = InvariantRules.add(
            baseSet,
            Invariant(id = "new-one", category = InvariantCategory.SCOPE, statement = "x", rationale = "y")
        )
        assertTrue(result is InvariantChangeResult.Applied)
        assertTrue((result as InvariantChangeResult.Applied).set.byId("new-one") != null)
    }

    @Test
    fun `applyPreset never removes or disables an existing locked invariant`() {
        val preset = InvariantPreset(label = "Test preset", additions = listOf("editable-one"))
        val result = InvariantRules.applyPreset(baseSet, preset)

        assertTrue(result is InvariantChangeResult.Applied)
        val updated = (result as InvariantChangeResult.Applied).set
        assertTrue(updated.byId("locked-one")!!.locked)
        assertTrue(updated.byId("locked-one")!!.enabled)
    }

    @Test
    fun `applyPreset adds its extra invariants`() {
        val extra = Invariant(id = "extra-one", category = InvariantCategory.TECH, statement = "x", rationale = "y")
        val preset = InvariantPreset(label = "Test preset", extra = listOf(extra))
        val result = InvariantRules.applyPreset(baseSet, preset)

        val updated = (result as InvariantChangeResult.Applied).set
        assertTrue(updated.byId("extra-one") != null)
    }

    @Test
    fun `reset returns DEFAULTS`() {
        val result = InvariantRules.reset()
        assertTrue(result is InvariantChangeResult.Applied)
        assertEquals(InvariantSet.DEFAULTS, (result as InvariantChangeResult.Applied).set)
    }

    @Test
    fun `DEFAULTS has at least the four locked core invariants`() {
        val lockedIds = InvariantSet.DEFAULTS.invariants.filter { it.locked }.map { it.id }
        assertTrue(lockedIds.containsAll(listOf("medical-scope", "no-ped", "no-pain-training", "min-calories")))
    }
}
