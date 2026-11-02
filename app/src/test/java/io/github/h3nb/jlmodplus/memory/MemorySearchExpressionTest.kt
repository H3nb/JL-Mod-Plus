package io.github.h3nb.jlmodplus.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemorySearchExpressionTest {
    @Test
    fun singleValue() {
        assertEquals(MemorySearchExpression.Single("500"), parseMemorySearchExpression(" 500 "))
    }

    @Test
    fun groupUsesPlainSemicolonTerms() {
        assertEquals(
            MemorySearchExpression.Group(listOf("500", "1000")),
            parseMemorySearchExpression("500;1000"),
        )
    }

    @Test
    fun groupRejectsAddressDistanceModifiers() {
        for (query in listOf("500;1000:128", "500;1000::128", "500;1000::")) {
            val parsed = parseMemorySearchExpression(query)
            assertTrue(parsed is MemorySearchExpression.Invalid, query)
            assertEquals(
                MemorySearchExpression.Reason.UNEXPECTED_RANGE,
                (parsed as MemorySearchExpression.Invalid).reason,
                query,
            )
        }
    }

    @Test
    fun autoGroupTypeUsesOneExactTypeForIntegerTerms() {
        assertEquals(
            MemoryEngineContract.TYPE_INT,
            inferMemoryGroupType(listOf("500", "1000", "-25")),
        )
        assertEquals(
            MemoryEngineContract.TYPE_LONG,
            inferMemoryGroupType(listOf("2147483648", "2147483649")),
        )
    }

    @Test
    fun autoGroupTypeUsesDoubleForFiniteDecimalTerms() {
        assertEquals(
            MemoryEngineContract.TYPE_DOUBLE,
            inferMemoryGroupType(listOf("1.5", "2e1")),
        )
        assertEquals(null, inferMemoryGroupType(listOf("1.0", "not-a-number")))
    }
}
