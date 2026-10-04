/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File

class BulkInstallPlannerTest {
    private fun item(id: String, version: String = "1.0", fingerprint: String = "payload"): BulkInstallItem {
        val source = File("$id.jar")
        val unit = BulkSourceUnit(id, BulkSourceOrigin.ExplicitSelection, BulkSourceKind.JarOnly,
            source, listOf(source), jarFile = source)
        return BulkInstallItem(id, unit, "Demo", "Vendor", version,
            descriptorAttributes = mapOf("MIDlet-Name" to "Demo", "MIDlet-Version" to version),
            jarFingerprint = fingerprint, status = BulkInstallStatus.New,
            action = BulkInstallAction.Install, selected = true)
    }

    @Test
    fun inspectionReasonsCarryMatchCountAndPreserveDiscoveryConflictPrecedence() {
        val unit = item("source").unit
        assertEquals(BulkInstallReviewReason.AmbiguousInstalledMatch(3),
            BulkInstallPlanner.inspectionReviewReason(unit, BulkInstallStatus.AmbiguousInstalledMatch, 3))
        assertEquals(BulkInstallReviewReason.JadJarMismatch,
            BulkInstallPlanner.inspectionReviewReason(unit, BulkInstallStatus.JadJarMismatch, 0))
        assertEquals(BulkInstallReviewReason.ConflictingJads,
            BulkInstallPlanner.inspectionReviewReason(
                unit.copy(reviewReason = BulkInstallReviewReason.ConflictingJads),
                BulkInstallStatus.AmbiguousInstalledMatch, 3))
        assertNull(BulkInstallPlanner.inspectionReviewReason(unit, BulkInstallStatus.New, 0))
    }

    @Test
    fun jadResolutionRetainsSpecificReasonsAndExactSourceArguments() {
        val root = Files.createTempDirectory("bulk-jad-reasons").toFile()
        try {
            val jad = root.resolve("source.jad")
            assertEquals(BulkInstallPlanner.JadResolution.Error(BulkInstallReviewReason.JadMissingJarUrl),
                BulkInstallPlanner.resolveJadReference(jad, null, null))
            assertEquals(BulkInstallPlanner.JadResolution.Remote,
                BulkInstallPlanner.resolveJadReference(jad, "HtTp://host/file.jar", "HtTp"))
            assertEquals(BulkInstallPlanner.JadResolution.Error(
                BulkInstallReviewReason.UnsupportedJarUriScheme("Vendor+SFTP")),
                BulkInstallPlanner.resolveJadReference(jad, "Vendor+SFTP://host/file.jar", "Vendor+SFTP"))
            val reference = "nested/Original #é.jar"
            assertEquals(BulkInstallPlanner.JadResolution.Error(
                BulkInstallReviewReason.ReferencedJarMissing(reference)),
                BulkInstallPlanner.resolveJadReference(jad, reference, null))
            // Keep the existing filename fallback, even when the exact reference is absent.
            val fallback = root.resolve("source.jar").apply { writeBytes(byteArrayOf(1)) }
            assertEquals(BulkInstallPlanner.JadResolution.Local(fallback.canonicalFile),
                BulkInstallPlanner.resolveJadReference(jad, reference, null))
            val exact = root.resolve("exact.jar").apply { writeBytes(byteArrayOf(2)) }
            assertEquals(BulkInstallPlanner.JadResolution.Local(exact.canonicalFile),
                BulkInstallPlanner.resolveJadReference(jad, "exact.jar", null))
        } finally {
            root.deleteRecursively()
        }
        // A relative filename without a parent is portable; no drive/root assumptions.
        assertEquals(BulkInstallPlanner.JadResolution.Error(BulkInstallReviewReason.JadMissingParentDirectory),
            BulkInstallPlanner.resolveJadReference(File("source.jad"), "source.jar", null))
    }

    @Test
    fun normalizationCarriesRemoteAndSourceErrorReasonsSeparatelyFromDiagnostics() {
        val remote = File("remote.jad")
        val unsupported = File("unsupported.jad")
        val invalid = File("invalid.jad")
        val reason = BulkInstallReviewReason.UnsupportedJarUriScheme("Exact+Scheme")
        val units = BulkInstallPlanner.normalizeResolvedSources(
            listOf(remote, unsupported, invalid), BulkSourceOrigin.ExplicitSelection,
            mapOf(remote to BulkInstallPlanner.JadResolution.Remote,
                unsupported to BulkInstallPlanner.JadResolution.Error(reason),
                invalid to BulkInstallPlanner.JadResolution.Error(diagnosticDetail = "Parser evidence: raw ID")),
        ).associateBy { it.primaryFile }
        assertEquals(BulkInstallStatus.RemoteSourceUnsupported, units.getValue(remote).discoveryStatus)
        assertEquals(BulkInstallReviewReason.RemoteJarUnsupported, units.getValue(remote).reviewReason)
        assertEquals(BulkInstallStatus.SourceError, units.getValue(unsupported).discoveryStatus)
        assertEquals(reason, units.getValue(unsupported).reviewReason)
        assertNull(units.getValue(unsupported).diagnosticDetail)
        assertNull(units.getValue(invalid).reviewReason)
        assertEquals("Parser evidence: raw ID", units.getValue(invalid).diagnosticDetail)
    }

    @Test
    fun conflictingJadsRemainDistinctFromSameVersionConflictAndConsumeSelectedJar() {
        val root = Files.createTempDirectory("bulk-conflicting-jads").toFile()
        try {
            val jar = root.resolve("source.jar").apply { writeBytes(byteArrayOf(1)) }
            fun jad(name: String, description: String) = root.resolve(name).apply {
                writeText("MIDlet-Name: Demo\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n" +
                    "MIDlet-Jar-URL: source.jar\nMIDlet-Jar-Size: 1\nMIDlet-Description: $description\n")
            }
            val first = jad("one.jad", "one")
            val second = jad("two.jad", "two")
            val units = BulkInstallPlanner.normalizeResolvedSources(
                listOf(first, jar, second), BulkSourceOrigin.ExplicitSelection,
                mapOf(first to BulkInstallPlanner.JadResolution.Local(jar),
                    second to BulkInstallPlanner.JadResolution.Local(jar)),
            )
            assertEquals(2, units.size)
            units.forEach {
                assertEquals(BulkInstallStatus.BatchConflict, it.discoveryStatus)
                assertEquals(BulkInstallReviewReason.ConflictingJads, it.reviewReason)
                assertEquals(listOf(it.primaryFile, jar), it.sourceFiles)
                assertEquals(jar, it.jarFile)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateReasonDoesNotChangeFingerprintIdentityOrPreflightClassification() {
        val first = item("first")
        val second = item("second")
        val result = BulkInstallPlanner.markSemanticDuplicates(listOf(first, second))
        assertEquals(first, result[0])
        assertEquals(second.copy(status = BulkInstallStatus.Duplicate, action = BulkInstallAction.Skip,
            selected = false, reviewReason = BulkInstallReviewReason.DuplicateBatchSource), result[1])
        assertFalse(result[1].installable)
        assertEquals(listOf(first), BulkInstallPlanner.markSemanticDuplicates(listOf(first)))
    }

    @Test
    fun equalVersionOrderingAndOlderCandidatesKeepDifferentReviewReasonsAndActions() {
        val first = item("first", "1.2", "one")
        val equal = item("equal", "1.2.0", "two")
        val older = item("older", "1.1", "three")
        val result = BulkInstallPlanner.applyBatchVersionGrouping(listOf(first, equal, older))
        listOf(0, 1).forEach {
            assertEquals(BulkInstallStatus.BatchConflict, result[it].status)
            assertEquals(BulkInstallReviewReason.SameVersionConflict, result[it].reviewReason)
            assertEquals(BulkInstallStatus.New, result[it].preflightStatus)
            assertEquals(BulkInstallAction.Skip, result[it].action)
            assertFalse(result[it].selected)
            assertTrue(result[it].installable)
        }
        assertEquals(older.copy(status = BulkInstallStatus.OlderBatchCandidate,
            action = BulkInstallAction.Skip, selected = false,
            reviewReason = BulkInstallReviewReason.OlderBatchCandidate), result[2])
        val singleMaximum = BulkInstallPlanner.applyBatchVersionGrouping(listOf(first, older))
        assertEquals(first, singleMaximum[0])
        assertEquals(result[2], singleMaximum[1])
    }

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
