/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.runtime;

import org.junit.jupiter.api.io.TempDir;
import io.github.h3nb.jlmodplus.testutil.TempFiles;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

public class RuntimeStorageLeaseTest {
    @TempDir public File temporaryFolder;

    @Test public void heldLeaseReservesOnlyItsCanonicalStoragePath() throws Exception {
        File filesDir = TempFiles.newFolder(temporaryFolder, "files");
        File rootA = TempFiles.newFolder(temporaryFolder, "work-a");
        File rootB = TempFiles.newFolder(temporaryFolder, "work-b");
        File bounceA = new File(rootA, "converted/Bounce");
        File bounceB = new File(rootB, "converted/Bounce");
        try (RuntimeStorageLease lease = RuntimeStorageLease.acquire(filesDir, bounceA)) {
            assertTrue(RuntimeStorageLease.isActive(filesDir, bounceA));
            assertTrue(RuntimeStorageLease.isActive(filesDir,
                    new File(rootA, "converted/../converted/Bounce")));
            assertFalse(RuntimeStorageLease.isActive(filesDir, bounceB));
            assertFalse(RuntimeStorageLease.isActive(filesDir,
                    new File(rootA, "converted/Bounce_1")));
            assertNull(RuntimeStorageLease.acquire(filesDir, bounceA));
        }
        assertFalse(RuntimeStorageLease.isActive(filesDir, bounceA));
    }

    @Test public void abandonedLockFileDoesNotReservePath() throws Exception {
        File filesDir = TempFiles.newFolder(temporaryFolder, "files-stale");
        File path = new File(TempFiles.newFolder(temporaryFolder, "work-stale"), "converted/Bounce");
        try (RuntimeStorageLease ignored = RuntimeStorageLease.acquire(filesDir, path)) {
            assertTrue(RuntimeStorageLease.isActive(filesDir, path));
        }
        assertFalse(RuntimeStorageLease.isActive(filesDir, path));
    }
}
