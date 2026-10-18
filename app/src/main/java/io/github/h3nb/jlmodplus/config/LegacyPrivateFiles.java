/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Explicit, non-destructive recovery of Siemens files from one installation's mixed old data. */
public final class LegacyPrivateFiles {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_DEPTH = 16;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final File sourceRoot;
    private final File destinationRoot;

    public LegacyPrivateFiles(File workdir, String storageKey) throws IOException {
        if (storageKey.isEmpty() || storageKey.equals(".") || storageKey.equals("..")
                || storageKey.contains("/") || storageKey.contains("\\") || storageKey.contains(":")) {
            throw new IOException("Invalid installed storage identity");
        }
        sourceRoot = new File(new File(workdir.getCanonicalFile(), "data"), storageKey);
        destinationRoot = new File(sourceRoot, "vendor/siemens");
        checked(sourceRoot, sourceRoot);
        checked(destinationRoot, destinationRoot);
    }

    public List<String> list() throws IOException {
        List<String> result = new ArrayList<>();
        if (sourceRoot.isDirectory()) collect(sourceRoot, "", 0, new int[]{0}, result);
        Collections.sort(result);
        return result;
    }

    private void collect(File directory, String prefix, int depth, int[] count,
            List<String> result) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Legacy directory is too deep");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot inspect legacy private files");
        for (File file : files) {
            if (++count[0] > MAX_ENTRIES) throw new IOException("Too many legacy private entries");
            String relative = prefix + file.getName();
            if (!eligible(relative)) continue;
            // Skip links entirely, including links pointing elsewhere inside the installation.
            if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) continue;
            if (file.isDirectory()) collect(file, relative + "/", depth + 1, count, result);
            else if (file.isFile() && !new File(destinationRoot, relative).exists()) result.add(relative);
        }
    }

    /** Caller must hold the installed write guard and exclusive runtime storage lease. */
    public void copy(String relative) throws IOException {
        if (!eligible(relative)) throw new IOException("Not an importable legacy file");
        File source = checked(sourceRoot, new File(sourceRoot, relative));
        File destination = checked(destinationRoot, new File(destinationRoot, relative));
        if (!source.isFile() || source.length() > MAX_BYTES || destination.exists()) {
            throw new IOException("Legacy file unavailable or destination already exists");
        }
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot prepare import");
        checked(destinationRoot, destination);
        File temporary = File.createTempFile(".legacy-import-", ".tmp", parent);
        try {
            try (FileInputStream input = new FileInputStream(source);
                    FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("Legacy file is too large");
                    output.write(buffer, 0, n);
                }
                output.getFD().sync();
            }
            // The installed guard and lease exclude concurrent installer/runtime publication.
            if (destination.exists() || !temporary.renameTo(destination)) {
                throw new IOException("Cannot publish imported file without replacing data");
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static boolean eligible(String relative) {
        if (relative == null || relative.isEmpty() || relative.startsWith("/")
                || relative.contains("\\") || relative.contains(":") || relative.indexOf('\0') >= 0) return false;
        String[] parts = relative.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        String first = parts[0];
        if (first.equals("private") || first.equals("cache") || first.equals("vendor")) return false;
        String lower = relative.toLowerCase(Locale.ROOT);
        return !lower.endsWith(".rsh") && !lower.endsWith(".rsr");
    }

    private static File checked(File root, File candidate) throws IOException {
        if (!root.getCanonicalFile().equals(root.getAbsoluteFile())
                || !candidate.getCanonicalFile().equals(candidate.getAbsoluteFile())) {
            throw new IOException("Linked private file location");
        }
        if (!candidate.equals(root)
                && !candidate.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Private file escapes installation");
        }
        return candidate;
    }
}
