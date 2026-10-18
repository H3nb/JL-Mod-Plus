/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.cldc.file;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MidletPlatformShareCacheTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void stageCopiesFileAndPrunesOnlyExpiredOwnedCopies() throws Exception {
        File directory = temp.newFolder("midlet-platform-share");
        File source = temp.newFile("picture.jpg");
        byte[] payload = {1, 2, 3, 4};
        Files.write(source.toPath(), payload);

        long now = System.currentTimeMillis();
        File expired = tempFile(directory, "midlet-", now - MidletPlatformShareCache.RETENTION_MILLIS - 60_000);
        File recent = tempFile(directory, "midlet-", now - MidletPlatformShareCache.RETENTION_MILLIS + 60_000);
        File unrelated = tempFile(directory, "unrelated-", now - MidletPlatformShareCache.RETENTION_MILLIS - 60_000);
        File subdirectory = new File(directory, "midlet-keep-folder");
        assertTrue(subdirectory.mkdir());
        assertTrue(subdirectory.setLastModified(now - MidletPlatformShareCache.RETENTION_MILLIS - 60_000));

        File staged = MidletPlatformShareCache.stage(source, directory);

        assertFalse(expired.exists());
        assertTrue(recent.exists());
        assertTrue(unrelated.exists());
        assertTrue(subdirectory.isDirectory());
        assertTrue(staged.getName().startsWith("midlet-"));
        assertTrue(staged.getName().endsWith(".jpg"));
        assertArrayEquals(payload, Files.readAllBytes(staged.toPath()));
        assertArrayEquals(payload, Files.readAllBytes(source.toPath()));
    }

    @Test public void failedSourceReadLeavesNoPartialCacheFile() throws Exception {
        File directory = temp.newFolder("midlet-platform-share");
        File missingSource = new File(temp.getRoot(), "missing.mp4");

        assertThrows(IOException.class, () -> MidletPlatformShareCache.stage(missingSource, directory));
        assertEquals(0, directory.list().length);
    }

    @Test public void cleanupDoesNotRemoveRecentOrFutureDatedCopies() throws Exception {
        File directory = temp.newFolder("midlet-platform-share");
        long now = System.currentTimeMillis();
        File recent = tempFile(directory, "midlet-", now - MidletPlatformShareCache.RETENTION_MILLIS + 1000);
        File future = tempFile(directory, "midlet-", now + 60_000);

        MidletPlatformShareCache.pruneExpired(directory, now);

        assertTrue(recent.exists());
        assertTrue(future.exists());
    }

    private static File tempFile(File directory, String prefix, long modified) throws IOException {
        File file = File.createTempFile(prefix, ".bin", directory);
        assertTrue(file.setLastModified(modified));
        return file;
    }
}
