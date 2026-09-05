package ru.playsoftware.j2meloader.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchExpressionTest {
    @Test
    fun singleValue() {
        assertEquals(MemorySearchExpression.Single("500"), parseMemorySearchExpression(" 500 "))
    }

    @Test
    fun groupUsesDefaultDistance() {
        val parsed = parseMemorySearchExpression("500;1000")
        assertEquals(
            MemorySearchExpression.Group(listOf("500", "1000"), DEFAULT_GROUP_DISTANCE),
            parsed,
        )
        assertFalse((parsed as MemorySearchExpression.Group).ordered)
        assertEquals(DEFAULT_GROUP_DISTANCE, parsed.displayDistance)
    }

    @Test
    fun groupReadsExplicitDistance() {
        assertEquals(
            MemorySearchExpression.Group(listOf("500", "1000", "25"), 256),
            parseMemorySearchExpression("500;1000;25:256"),
        )
    }

    @Test
    fun orderedGroupEncodesStrictOrderWithoutChangingBinderShape() {
        val parsed = parseMemorySearchExpression("500;1000::128")
        assertTrue(parsed is MemorySearchExpression.Group)
        parsed as MemorySearchExpression.Group
        assertEquals(listOf("500", "1000"), parsed.values)
        assertTrue(parsed.ordered)
        assertEquals(128, parsed.displayDistance)
        assertEquals(-128, parsed.maxDistance)
    }

    @Test
    fun orderedGroupRejectsMalformedOrOutOfRangeDistance() {
        for (query in listOf("500;1000::", "500;1000::0", "500;1000::4097", "500;1000:::128")) {
            val parsed = parseMemorySearchExpression(query)
            assertTrue(query, parsed is MemorySearchExpression.Invalid)
            assertEquals(
                query,
                MemorySearchExpression.Reason.INVALID_DISTANCE,
                (parsed as MemorySearchExpression.Invalid).reason,
            )
        }
    }
}
