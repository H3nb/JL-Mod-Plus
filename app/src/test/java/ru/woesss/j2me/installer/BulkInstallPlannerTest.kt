/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkInstallPlannerTest {
    @Test
    fun numericComponentsFollowDescriptorOrdering() {
        assertEquals(1, BulkInstallPlanner.compareVersions("1.10", "1.2"))
        assertEquals(-1, BulkInstallPlanner.compareVersions("1.2", "1.10"))
    }

    @Test
    fun missingComponentsCompareAsZero() {
        assertEquals(0, BulkInstallPlanner.compareVersions("1", "1.0.0"))
        assertEquals(1, BulkInstallPlanner.compareVersions("1.0.1", "1"))
    }

    @Test
    fun nonNumericComponentsCompareAsZeroLikeDescriptor() {
        assertEquals(0, BulkInstallPlanner.compareVersions("1.beta", "1.0"))
        assertEquals(0, BulkInstallPlanner.compareVersions("alpha", "0"))
    }
}
