package io.github.h3nb.jlmodplus.testutil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small filesystem fixture helpers for Jupiter's injected per-test temporary directory.
 * Named creations must fail when the target already exists.
 */
public final class TempFiles {
    private TempFiles() {}

    public static File newFile(File root, String name) throws IOException {
        return Files.createFile(new File(root, name).toPath()).toFile();
    }

    public static File newFile(File root) throws IOException {
        return Files.createTempFile(root.toPath(), "file-", "").toFile();
    }

    public static File newFolder(File root, String... names) throws IOException {
        if (names.length == 0) {
            return Files.createTempDirectory(root.toPath(), "folder-").toFile();
        }
        Path path = root.toPath();
        for (int i = 0; i < names.length - 1; i++) {
            path = path.resolve(names[i]);
            Files.createDirectories(path);
        }
        return Files.createDirectory(path.resolve(names[names.length - 1])).toFile();
    }
}
