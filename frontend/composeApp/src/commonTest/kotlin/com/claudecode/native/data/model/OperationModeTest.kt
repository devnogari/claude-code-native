package com.claudecode.native.data.model

import com.claudecode.native.data.model.OperationMode.Companion.fromString
import com.claudecode.native.data.model.OperationMode.Companion.next
import com.claudecode.native.data.model.OperationMode.Companion.toServerValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for OperationMode enum.
 */
class OperationModeTest {

    @Test
    fun `OperationMode should have three modes`() {
        val modes = OperationMode.entries
        assertEquals(3, modes.size)
        assertEquals(listOf(OperationMode.DEFAULT, OperationMode.PLAN, OperationMode.BYPASS), modes)
    }

    @Test
    fun `next should cycle DEFAULT to PLAN`() {
        val mode = OperationMode.DEFAULT
        assertEquals(OperationMode.PLAN, mode.next())
    }

    @Test
    fun `next should cycle PLAN to BYPASS`() {
        val mode = OperationMode.PLAN
        assertEquals(OperationMode.BYPASS, mode.next())
    }

    @Test
    fun `next should cycle BYPASS to DEFAULT`() {
        val mode = OperationMode.BYPASS
        assertEquals(OperationMode.DEFAULT, mode.next())
    }

    @Test
    fun `full cycle should return to original mode`() {
        val startMode = OperationMode.DEFAULT
        val afterThreeCycles = startMode.next().next().next()
        assertEquals(startMode, afterThreeCycles)
    }

    @Test
    fun `fromString should parse default`() {
        assertEquals(OperationMode.DEFAULT, fromString("default"))
        assertEquals(OperationMode.DEFAULT, fromString("DEFAULT"))
    }

    @Test
    fun `fromString should parse plan`() {
        assertEquals(OperationMode.PLAN, fromString("plan"))
        assertEquals(OperationMode.PLAN, fromString("PLAN"))
    }

    @Test
    fun `fromString should parse bypassPermissions`() {
        assertEquals(OperationMode.BYPASS, fromString("bypassPermissions"))
        assertEquals(OperationMode.BYPASS, fromString("BYPASSPERMISSIONS"))
        // Note: "bypass" alias was removed to match backend constants exactly
        assertEquals(OperationMode.DEFAULT, fromString("bypass"))
    }

    @Test
    fun `fromString should return DEFAULT for unknown values`() {
        assertEquals(OperationMode.DEFAULT, fromString("unknown"))
        assertEquals(OperationMode.DEFAULT, fromString(""))
        assertEquals(OperationMode.DEFAULT, fromString("invalid"))
    }

    @Test
    fun `toServerValue should return correct string for DEFAULT`() {
        assertEquals("default", OperationMode.DEFAULT.toServerValue())
    }

    @Test
    fun `toServerValue should return correct string for PLAN`() {
        assertEquals("plan", OperationMode.PLAN.toServerValue())
    }

    @Test
    fun `toServerValue should return correct string for BYPASS`() {
        assertEquals("bypassPermissions", OperationMode.BYPASS.toServerValue())
    }

    @Test
    fun `displayName should be human readable`() {
        assertEquals("Auto", OperationMode.DEFAULT.displayName)
        assertEquals("Plan", OperationMode.PLAN.displayName)
        assertEquals("Bypass", OperationMode.BYPASS.displayName)
    }

    @Test
    fun `description should provide meaningful context`() {
        // Just verify descriptions are not empty and different
        val descriptions = OperationMode.entries.map { it.description }
        assertEquals(3, descriptions.toSet().size) // All unique
        descriptions.forEach {
            assertTrue(it.isNotBlank(), "Description should not be blank")
        }
    }

    @Test
    fun `roundtrip fromString and toServerValue`() {
        OperationMode.entries.forEach { mode ->
            val serverValue = mode.toServerValue()
            val parsed = fromString(serverValue)
            assertEquals(mode, parsed, "Roundtrip failed for $mode")
        }
    }
}
