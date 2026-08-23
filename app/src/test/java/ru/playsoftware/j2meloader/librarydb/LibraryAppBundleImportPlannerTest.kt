/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAppBundleImportPlannerTest {
    @Test
    fun legacyPayloadUsesSingleModeWithoutReordering() {
        val parsed = ParsedBundle(
            formatVersion = 1,
            apps = listOf(
                BundleApp(
                    bundleId = "legacy",
                    title = "",
                    vendor = "",
                    version = "",
                    payloadRoot = "",
                    sourceSha256 = null,
                    configState = BundleNamespaceState.Absent,
                    dataState = BundleNamespaceState.Absent,
                    legacyAssurance = true,
                ),
            ),
            legacyAssurance = true,
        )

        val plan = LibraryAppBundleImportPlanner.plan(parsed)

        assertFalse(plan.isBatch)
        assertTrue(plan.legacyAssurance)
        assertEquals(listOf("legacy"), plan.items.map { it.app.bundleId })
    }

    @Test
    fun universalPayloadUsesStableBundleIdOrderingAndBatchMode() {
        fun app(id: String) = BundleApp(
            bundleId = id,
            title = id,
            vendor = "Vendor",
            version = "1.0",
            payloadRoot = "apps/$id/",
            sourceSha256 = "0".repeat(64),
            configState = BundleNamespaceState.Absent,
            dataState = BundleNamespaceState.Absent,
        )
        val parsed = ParsedBundle(
            formatVersion = LibraryAppBundleFormat.UNIVERSAL_VERSION,
            apps = listOf(app("a0002"), app("a0001")),
            legacyAssurance = false,
        )

        val plan = LibraryAppBundleImportPlanner.plan(parsed)

        assertTrue(plan.isBatch)
        assertEquals(listOf("a0001", "a0002"), plan.items.map { it.app.bundleId })
        assertEquals(listOf(0, 1), plan.items.map { it.ordinal })
    }
}
