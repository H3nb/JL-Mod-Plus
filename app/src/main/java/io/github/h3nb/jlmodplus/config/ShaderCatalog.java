// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.config;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import kotlin.io.FilesKt;

/** Discovers legacy .ini shader definitions without moving or creating user files. */
public final class ShaderCatalog {
    private static final String TAG = "ShaderCatalog";

    private ShaderCatalog() {}

    public static ArrayList<ShaderInfo> scan(File root) {
        ArrayList<ShaderInfo> result = new ArrayList<>();
        if (!root.isDirectory() && !root.mkdirs()) return result;
        Set<String> seen = new HashSet<>();
        collectLegacy(root, root, result, seen, 0);
        return result;
    }

    /** Legacy profile paths remain relative to shaders/. */
    public static File resolveLegacySource(File root, String path) {
        if (root == null || path == null || path.isEmpty()) return null;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File original = new File(canonicalRoot, path).getCanonicalFile();
            if (!within(canonicalRoot, original)) return null;
            return original;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean within(File root, File file) {
        return file.getPath().startsWith(root.getPath() + File.separator);
    }

    private static File[] sorted(File folder) {
        File[] entries = folder.listFiles();
        if (entries == null) return new File[0];
        Arrays.sort(entries, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        return entries;
    }

    private static boolean ext(File file, String suffix) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    /**
     * Read one user-selected INI. Legacy INI files may contain several named
     * shader pairs; returning all sections lets the configuration page choose.
     */
    public static java.util.List<ShaderInfo> readLegacyFile(File root, String relativePath)
            throws IOException {
        File base = root.getCanonicalFile();
        File file = new File(base, relativePath).getCanonicalFile();
        if (!within(base, file) || !file.isFile() || !ext(file, ".ini")
                || file.length() > 512 * 1024) {
            throw new IOException("Unavailable or invalid legacy shader file");
        }
        java.util.List<ShaderInfo> entries = new ArrayList<>();
        ShaderInfo info = null;
        for (String line : FilesKt.readText(file, StandardCharsets.UTF_8).split("[\\n\\r]+")) {
            if (line.startsWith("[")) {
                addValidLegacyVariant(entries, base, file.getParentFile(), info);
                info = new ShaderInfo(line.replaceAll("[\\[\\]]", ""), "unknown");
            } else if (info != null) {
                try { info.set(line); } catch (RuntimeException e) {
                    Log.w(TAG, "Invalid legacy setting in " + file.getName(), e);
                }
            }
        }
        addValidLegacyVariant(entries, base, file.getParentFile(), info);
        return entries;
    }

    private static void addValidLegacyVariant(List<ShaderInfo> entries, File root,
            File parent, ShaderInfo info) {
        if (info == null || info.fragment == null || info.vertex == null) return;
        try {
            entries.add(resolveLegacyPaths(root, parent, info));
        } catch (IOException ignored) {
            // A malformed section is unusable, but must not discard its valid peers.
        }
    }

    private static ShaderInfo resolveLegacyPaths(File root, File parent, ShaderInfo info)
            throws IOException {
        info.vertex = resolveLegacyPath(root, parent, info.vertex);
        info.fragment = resolveLegacyPath(root, parent, info.fragment);
        return info;
    }

    private static String resolveLegacyPath(File root, File parent, String source)
            throws IOException {
        File sibling = new File(parent, source).getCanonicalFile();
        if (within(root, sibling) && sibling.isFile()) {
            return sibling.getPath().substring(root.getPath().length() + 1)
                    .replace(File.separatorChar, '/');
        }
        File direct = resolveLegacySource(root, source);
        if (direct != null && direct.isFile()) return source;
        throw new IOException("Missing legacy shader source: " + source);
    }

    private static void collectLegacy(File folder, File root, ArrayList<ShaderInfo> out,
            Set<String> seen, int depth) {
        if (depth > 12) return;
        for (File file : sorted(folder)) {
            try {
                File canonicalRoot = root.getCanonicalFile();
                File canonicalFile = file.getCanonicalFile();
                if (!within(canonicalRoot, canonicalFile)) continue;
                if (file.isDirectory()) {
                    collectLegacy(file, root, out, seen, depth + 1);
                    continue;
                }
                if (!file.isFile() || !ext(file, ".ini")
                        || !seen.add(canonicalFile.getPath())) continue;
                String relative = canonicalFile.getPath()
                        .substring(canonicalRoot.getPath().length() + 1)
                        .replace(File.separatorChar, '/');
                out.addAll(readLegacyFile(root, relative));
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Unable to inspect " + file.getName(), e);
            }
        }
    }

}
