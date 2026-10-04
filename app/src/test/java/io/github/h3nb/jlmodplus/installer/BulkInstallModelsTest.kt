/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkInstallModelsTest {
    @Test
    fun sharedConflictStatusKeepsDistinctStructuredResultReasons() {
        val first = BulkInstallResult("item", "Demo", BulkInstallResultKind.Failed,
            reviewReason = BulkInstallReviewReason.ConflictingJads)
        val second = first.copy(reviewReason = BulkInstallReviewReason.SameVersionConflict)
        assertNotEquals(first.reviewReason, second.reviewReason)
        assertEquals(first.kind, second.kind)
        assertNull(first.detail)
        assertNull(second.detail)
    }

    @Test
    fun exactReinstallReviewRetainsItsReasonWithoutLocalizedDetail() {
        val result = BulkInstallResult("item", "Demo", BulkInstallResultKind.Failed,
            reviewReason = BulkInstallReviewReason.RetainedReinstallSourceMissing)
        assertEquals(BulkInstallReviewReason.RetainedReinstallSourceMissing, result.reviewReason)
        assertNull(result.detail)
    }

    @Test
    fun sourceErrorFallbackUsesSemanticStatusRatherThanDiagnosticEvidence() {
        val source = File("source.jar")
        val unit = BulkSourceUnit("item", BulkSourceOrigin.ExplicitSelection, BulkSourceKind.JarOnly,
            source, listOf(source), jarFile = source)
        val item = BulkInstallItem("item", unit, "Demo", "Vendor", "1.0",
            status = BulkInstallStatus.SourceError, action = BulkInstallAction.Skip, selected = false)
        assertEquals(BulkInstallReviewReason.SourceError, item.effectiveReviewReason)
        val diagnostic = item.copy(diagnosticDetail = "raw-parser-evidence")
        val result = BulkInstallResult(item.id, item.name, BulkInstallResultKind.Failed,
            reviewReason = diagnostic.effectiveReviewReason)
        assertEquals(BulkInstallReviewReason.SourceError, result.reviewReason)
        assertNull(result.detail)
        assertEquals("raw-parser-evidence", diagnostic.diagnosticDetail)
        val specific = BulkInstallReviewReason.ReferencedJarMissing("original/reference.jar")
        assertSame(specific, diagnostic.copy(reviewReason = specific).effectiveReviewReason)
        assertNull(diagnostic.copy(status = BulkInstallStatus.Duplicate).effectiveReviewReason)
    }

    @Test
    fun ambiguousMatchRetainsExactCountThroughStructuredResults() {
        listOf(2, 5).forEach { count ->
            val reason = BulkInstallReviewReason.AmbiguousInstalledMatch(count)
            val result = BulkInstallResult("item", "Demo", BulkInstallResultKind.Failed, reviewReason = reason)
            assertSame(reason, result.reviewReason)
            assertEquals(count, (result.reviewReason as BulkInstallReviewReason.AmbiguousInstalledMatch).matchCount)
            assertNull(result.detail)
        }
    }

    @Test
    fun exactReinstallActionMakesAlreadyInstalledItemSelectable() {
        val source = File("/tmp/retained.jar")
        val unit = BulkSourceUnit(
            id = "reinstall-7",
            origin = BulkSourceOrigin.ExplicitSelection,
            kind = BulkSourceKind.JarOnly,
            primaryFile = source,
            sourceFiles = listOf(source),
            jarFile = source,
            reinstallAppId = 7L,
            reinstallStorageKey = "Demo",
        )
        val reinstall = BulkInstallItem(
            id = unit.id,
            unit = unit,
            name = "Demo",
            vendor = "Vendor",
            version = "1.0",
            status = BulkInstallStatus.AlreadyInstalled,
            action = BulkInstallAction.Reinstall,
            selected = true,
        )
        val ordinarySkip = reinstall.copy(action = BulkInstallAction.Skip, selected = false)

        assertTrue(reinstall.installable)
        assertFalse(ordinarySkip.installable)
        assertFalse(reinstall.copy(
            unit = unit.copy(reinstallAppId = null),
        ).installable)
    }
}
