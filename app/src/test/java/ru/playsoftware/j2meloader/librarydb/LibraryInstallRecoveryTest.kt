/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryInstallRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun recoveryArtifactsLiveOutsideConvertedNamespace() {
        val root = temporaryFolder.newFolder("workdir")
        val converted = File(root, "converted").apply { mkdir() }
        val target = File(converted, "game").apply { mkdir() }
        File(target, "converted.dex").writeText("old")

        val backup = LibraryInstallRecovery.createBackup(root, "game", target)
        val staging = LibraryInstallRecovery.stagingDirectory(root)

        assertFalse(target.exists())
        assertEquals(File(root, LibraryInstallRecovery.BACKUP_ROOT_NAME), backup.parentFile)
        assertEquals(root, staging.parentFile)
        assertTrue(backup.isDirectory)
        assertFalse(File(converted, LibraryInstallRecovery.BACKUP_ROOT_NAME).exists())
        assertFalse(File(converted, LibraryInstallRecovery.STAGING_DIR_NAME).exists())
    }

    @Test fun failedPublishCanRestoreBackupToOriginalStorageKey() {
        val root = temporaryFolder.newFolder("restore")
        val converted = File(root, "converted").apply { mkdir() }
        val target = File(converted, "game").apply { mkdir() }
        File(target, "marker").writeText("old")
        val backup = LibraryInstallRecovery.createBackup(root, "game", target)

        assertTrue(LibraryInstallRecovery.restoreBackup(target, backup))
        assertTrue(File(target, "marker").isFile)
        assertFalse(backup.exists())
    }

    @Test fun publishedReplacementRequestsTargetedRefreshUntilBackupDiscarded() {
        val root = temporaryFolder.newFolder("refresh")
        val converted = File(root, "converted").apply { mkdir() }
        val target = File(converted, "game").apply { mkdir() }
        File(target, "marker").writeText("old")
        LibraryInstallRecovery.createBackup(root, "game", target)
        target.mkdir()
        File(target, "marker").writeText("new")

        val recovery = LibraryInstallRecovery.recoverFilesystem(root)

        assertEquals(setOf("game"), recovery.refreshStorageKeys)
        assertTrue(recovery.failures.isEmpty())
        assertTrue(File(root, LibraryInstallRecovery.BACKUP_ROOT_NAME).isDirectory)
        assertTrue(LibraryInstallRecovery.discardBackup(root, "game"))
        assertFalse(File(root, LibraryInstallRecovery.BACKUP_ROOT_NAME).exists())
    }

    @Test fun onlyLegacyInNamespaceStagingNameIsReservedAsStorageKey() {
        assertFalse(LibraryInstallRecovery.isReservedStorageKey(LibraryInstallRecovery.STAGING_DIR_NAME))
        assertTrue(LibraryInstallRecovery.isReservedStorageKey(LibraryInstallRecovery.LEGACY_STAGING_DIR_NAME))
        assertFalse(LibraryInstallRecovery.STAGING_DIR_NAME.contains(':'))
    }

    @Test fun orphanNewInstallStagingIsRemovedWithoutTouchingInstalledApps() {
        val root = temporaryFolder.newFolder("orphan-staging")
        val converted = File(root, "converted").apply { mkdir() }
        val installed = File(converted, "existing").apply { mkdir() }
        File(installed, "marker").writeText("keep")
        val staging = LibraryInstallRecovery.stagingDirectory(root).apply { mkdir() }
        File(staging, "partial").writeText("unfinished new install")

        val recovery = LibraryInstallRecovery.recoverFilesystem(root)

        assertTrue(recovery.refreshStorageKeys.isEmpty())
        assertTrue(recovery.failures.isEmpty())
        assertFalse(staging.exists())
        assertTrue(File(installed, "marker").isFile)
    }

    @Test fun startupRecoveryCleansSiblingAndLegacyStagingAfterRollback() {
        val root = temporaryFolder.newFolder("staging")
        val converted = File(root, "converted").apply { mkdir() }
        val target = File(converted, "game").apply { mkdir() }
        File(target, "converted.dex").writeText("old")
        LibraryInstallRecovery.createBackup(root, "game", target)
        LibraryInstallRecovery.stagingDirectory(root).mkdir()
        File(converted, LibraryInstallRecovery.LEGACY_STAGING_DIR_NAME).mkdir()

        val recovery = LibraryInstallRecovery.recoverFilesystem(root)

        assertTrue(recovery.refreshStorageKeys.isEmpty())
        assertTrue(recovery.failures.isEmpty())
        assertTrue(target.isDirectory)
        assertFalse(LibraryInstallRecovery.stagingDirectory(root).exists())
        assertFalse(File(converted, LibraryInstallRecovery.LEGACY_STAGING_DIR_NAME).exists())
    }
}
