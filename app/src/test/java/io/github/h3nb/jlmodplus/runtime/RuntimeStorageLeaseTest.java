/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RuntimeStorageLeaseTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void heldLeaseReservesOnlyItsCanonicalStoragePath() throws Exception {
        File filesDir = temporaryFolder.newFolder("files");
        File rootA = temporaryFolder.newFolder("work-a");
        File rootB = temporaryFolder.newFolder("work-b");
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
        File filesDir = temporaryFolder.newFolder("files-stale");
        File path = new File(temporaryFolder.newFolder("work-stale"), "converted/Bounce");
        try (RuntimeStorageLease ignored = RuntimeStorageLease.acquire(filesDir, path)) {
            assertTrue(RuntimeStorageLease.isActive(filesDir, path));
        }
        assertFalse(RuntimeStorageLease.isActive(filesDir, path));
    }
}
