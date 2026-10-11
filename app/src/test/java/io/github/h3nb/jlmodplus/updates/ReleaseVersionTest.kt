package io.github.h3nb.jlmodplus.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun crossSeriesAndPrereleaseOrder() {
        val pairs = listOf(
            "0.2.0-alpha.1" to "0.1.0-alpha.2",
            "0.2.0-alpha.10" to "0.2.0-alpha.9",
            "0.2.0-beta.1" to "0.2.0-alpha.50",
            "0.2.0-rc.1" to "0.2.0-beta.2",
            "0.2.0" to "0.2.0-rc.9",
        )
        pairs.forEach { (high, low) ->
            assertTrue(requireNotNull(ReleaseVersion.parse(high)) > requireNotNull(ReleaseVersion.parse(low)))
            assertFalse(requireNotNull(ReleaseVersion.parse(low)) > requireNotNull(ReleaseVersion.parse(high)))
        }
    }

    @Test
    fun requiresHigherAndroidCodeAsWell() {
        val candidate = UpdateCandidate("0.2.0-alpha.1", 21, "https://example.invalid")
        assertTrue(eligibleUpdate("0.1.0-alpha.2", 20, candidate))
        assertFalse(eligibleUpdate("0.1.0-alpha.2", 22, candidate))
        assertFalse(eligibleUpdate("0.2.0", 20, candidate))
    }

    @Test
    fun refusesMalformedAndLeadingZeroVersions() {
        listOf("0.2.0-alpha.01", "v0.2.0", "0.2", "0.2.0-alpha..1",
            "0.2.0-alpha.1.", "0.2.0-").forEach {
            assertTrue(ReleaseVersion.parse(it) == null)
        }
    }
}
