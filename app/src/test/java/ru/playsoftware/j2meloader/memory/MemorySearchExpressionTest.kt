package ru.playsoftware.j2meloader.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchExpressionTest {
    @Test
    fun singleValue() {
        assertEquals(MemorySearchExpression.Single("500"), parseMemorySearchExpression(" 500 "))
    }

    @Test
    fun groupUsesDefaultDistance() {
        assertEquals(
            MemorySearchExpression.Group(listOf("500", "1000"), DEFAULT_GROUP_DISTANCE),
            parseMemorySearchExpression("500;1000"),
        )
    }

    @Test
    fun groupReadsExplicitDistance() {
        assertEquals(
            MemorySearchExpression.Group(listOf("500", "1000", "25"), 256),
            parseMemorySearchExpression("500;1000;25:256"),
        )
    }

    @Test
    fun orderedGroupFailsExplicitlyUntilEngineSupportsIt() {
        val parsed = parseMemorySearchExpression("500;1000::128")
        assertTrue(parsed is MemorySearchExpression.Invalid)
        assertEquals(
            MemorySearchExpression.Reason.ORDERED_GROUP_UNSUPPORTED,
            (parsed as MemorySearchExpression.Invalid).reason,
        )
    }
}
