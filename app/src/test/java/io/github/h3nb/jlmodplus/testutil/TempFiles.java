package io.github.h3nb.jlmodplus.testutil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Small filesystem fixture helpers for Jupiter's injected per-test temporary directory.
 * Named creations must fail when the target already exists.
 */
public final class TempFiles {
    private TempFiles() {}

    public static File newFile(File root, String name) throws IOException {
        return Files.createFile(new File(root, name).toPath()).toFile();
    }

    public static File newFolder(File root, String name) throws IOException {
        return Files.createDirectory(new File(root, name).toPath()).toFile();
    }

    public static File newFolder(File root) throws IOException {
        return Files.createTempDirectory(root.toPath(), "folder-").toFile();
    }
}
