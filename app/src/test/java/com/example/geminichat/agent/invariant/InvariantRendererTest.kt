package com.example.geminichat.agent.invariant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantRendererTest {

    private fun invariant(
        id: String = "test-invariant",
        category: InvariantCategory = InvariantCategory.SAFETY,
        enabled: Boolean = true,
        locked: Boolean = false,
        alternative: String = "Do X instead."
    ) = Invariant(
        id = id,
        category = category,
        statement = "Never do the dangerous thing.",
        rationale = "Because it's unsafe.",
        alternative = alternative,
        enabled = enabled,
        locked = locked
    )

    @Test
    fun `an empty set renders nothing`() {
        assertEquals("", InvariantRenderer.render(InvariantSet(emptyList())))
    }

    @Test
    fun `a set of only disabled invariants renders nothing`() {
        val set = InvariantSet(listOf(invariant(enabled = false)))
        assertEquals("", InvariantRenderer.render(set))
    }

    @Test
    fun `an enabled invariant is rendered with its id, category, rationale, and alternative`() {
        val set = InvariantSet(listOf(invariant()))
        val rendered = InvariantRenderer.render(set)

        assertTrue(rendered.contains("test-invariant"))
        assertTrue(rendered.contains("SAFETY"))
        assertTrue(rendered.contains("Never do the dangerous thing."))
        assertTrue(rendered.contains("Because it's unsafe."))
        assertTrue(rendered.contains("Do X instead."))
    }

    @Test
    fun `a disabled invariant does not appear even when another is enabled`() {
        val set = InvariantSet(
            listOf(
                invariant(id = "enabled-one", enabled = true),
                invariant(id = "disabled-one", enabled = false)
            )
        )
        val rendered = InvariantRenderer.render(set)

        assertTrue(rendered.contains("enabled-one"))
        assertFalse(rendered.contains("disabled-one"))
    }

    @Test
    fun `the block instructs the model to refuse and explain, and to override other layers`() {
        val set = InvariantSet(listOf(invariant()))
        val rendered = InvariantRenderer.render(set)

        assertTrue(rendered.contains("override"))
        assertTrue(rendered.contains("refuse", ignoreCase = true) || rendered.contains("MUST refuse"))
    }

    @Test
    fun `an invariant with no alternative omits the if-asked-anyway line`() {
        val set = InvariantSet(listOf(invariant(alternative = "")))
        val rendered = InvariantRenderer.render(set)

        assertFalse(rendered.contains("If asked anyway"))
    }
}
