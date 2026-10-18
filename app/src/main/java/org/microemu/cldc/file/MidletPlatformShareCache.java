/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.microemu.cldc.file;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stages external platform requests without deleting files a receiving app may still be reading. */
final class MidletPlatformShareCache {
    // Android can reclaim cache files earlier under storage pressure. Our own cleanup only
    // removes old copies when another platform request is made, leaving receivers time to read.
    static final long RETENTION_MILLIS = 24L * 60 * 60 * 1000;

    private MidletPlatformShareCache() {}

    static File stage(File source, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create temporary shared-file directory");
        }
        pruneExpired(directory, System.currentTimeMillis());

        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 12
                ? name.substring(dot) : ".bin";
        File staged = File.createTempFile("midlet-", extension, directory);
        boolean complete = false;
        try {
            try (InputStream input = new FileInputStream(source);
                    OutputStream output = new BufferedOutputStream(new FileOutputStream(staged))) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    output.write(buffer, 0, count);
                }
            }
            complete = true;
            return staged;
        } finally {
            if (!complete) staged.delete();
        }
    }

    static void pruneExpired(File directory, long now) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File candidate : files) {
            if (!candidate.getName().startsWith("midlet-") || !candidate.isFile()) continue;
            long modified = candidate.lastModified();
            if (modified > 0 && now >= modified && now - modified >= RETENTION_MILLIS) {
                // Best effort: failure must not prevent a new platform request.
                candidate.delete();
            }
        }
    }
}
