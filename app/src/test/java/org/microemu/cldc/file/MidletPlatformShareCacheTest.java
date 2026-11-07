/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.cldc.file;

import org.junit.jupiter.api.io.TempDir;
import io.github.h3nb.jlmodplus.testutil.TempFiles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

public class MidletPlatformShareCacheTest {
    @TempDir public File temp;

    @Test public void stageCopiesFileAndPrunesOnlyExpiredOwnedCopies() throws Exception {
        File directory = TempFiles.newFolder(temp, "midlet-platform-share");
        File source = TempFiles.newFile(temp, "picture.jpg");
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
        File directory = TempFiles.newFolder(temp, "midlet-platform-share");
        File missingSource = new File(temp, "missing.mp4");

        assertThrows(IOException.class, () -> MidletPlatformShareCache.stage(missingSource, directory));
        assertEquals(0, directory.list().length);
    }

    @Test public void cleanupDoesNotRemoveRecentOrFutureDatedCopies() throws Exception {
        File directory = TempFiles.newFolder(temp, "midlet-platform-share");
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
