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
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.h3nb.jlmodplus.R

class BulkInstallModelsTest {
    @Test
    fun sharedConflictStatusKeepsDistinctPresentationReasons() {
        val first = BulkInstallReviewReason.ConflictingJads
        val second = BulkInstallReviewReason.SameVersionConflict
        assertEquals(R.string.bulk_install_reason_conflicting_jads, bulkInstallReviewReasonResource(first))
        assertEquals(R.string.bulk_install_reason_version_conflict, bulkInstallReviewReasonResource(second))
        assertNotEquals(bulkInstallReviewReasonResource(first), bulkInstallReviewReasonResource(second))
    }

    @Test
    fun exactReinstallReviewKeepsItsExistingMissingSourceExplanation() {
        assertEquals(R.string.bulk_install_reinstall_source_missing,
            bulkInstallReviewReasonResource(BulkInstallReviewReason.RetainedReinstallSourceMissing))
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
