/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.file.Files

class BulkInstallPlannerTest {
    @Test
    fun sourceFingerprintTracksOrderedContentNotFileLocation() {
        val firstRoot = Files.createTempDirectory("bulk-fingerprint-a").toFile()
        val secondRoot = Files.createTempDirectory("bulk-fingerprint-b").toFile()
        try {
            val firstJad = firstRoot.resolve("game.jad").apply { writeText("name=game") }
            val firstJar = firstRoot.resolve("game.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val copiedJad = secondRoot.resolve("source-0.jad").apply { writeText("name=game") }
            val copiedJar = secondRoot.resolve("source-1.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertEquals(
                BulkInstallPlanner.fingerprint(listOf(firstJad, firstJar)),
                BulkInstallPlanner.fingerprint(listOf(copiedJad, copiedJar)),
            )
            assertNotEquals(
                BulkInstallPlanner.fingerprint(listOf(firstJad, firstJar)),
                BulkInstallPlanner.fingerprint(listOf(firstJar, firstJad)),
            )
        } finally {
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

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
