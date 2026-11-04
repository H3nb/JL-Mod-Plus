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
import java.util.Set;
import kotlin.io.FilesKt;

/** External shader discovery and non-overwriting migration of old root-level shaders. */
public final class ShaderCatalog {
    public static final String LEGACY = "shaders_legacy";
    public static final String GLSL = "shaders_glsl";
    private static final String TAG = "ShaderCatalog";

    private ShaderCatalog() {}

    public static ArrayList<ShaderInfo> scan(File root) {
        ArrayList<ShaderInfo> result = new ArrayList<>();
        if (!root.isDirectory() && !root.mkdirs()) return result;
        File legacy = new File(root, LEGACY), glsl = new File(root, GLSL);
        if (!legacy.isDirectory() && !legacy.mkdirs()) Log.w(TAG, "Legacy shader folder unavailable");
        if (!glsl.isDirectory() && !glsl.mkdirs()) Log.w(TAG, "GLSL shader folder unavailable");
        moveLegacy(root, legacy);
        Set<String> seen = new HashSet<>();
        collectLegacy(legacy, result, seen);
        collectLegacy(root, result, seen);
        for (File file : sorted(root)) {
            if (file.isFile() && (ext(file, ".glslp") || ext(file, ".glsl")))
                addGlsl(result, file.getName());
        }
        collectGlsl(glsl, root, result, 0);
        return result;
    }

    /** Old profile fields remain relative to shaders/, even after files are moved. */
    public static File resolveLegacySource(File root, String path) {
        if (root == null || path == null || path.isEmpty()) return null;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File original = new File(canonicalRoot, path).getCanonicalFile();
            if (!within(canonicalRoot, original)) return null;
            if (original.isFile()) return original;
            File moved = new File(new File(canonicalRoot, LEGACY), path).getCanonicalFile();
            return within(canonicalRoot, moved) ? moved : original;
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

    private static void moveLegacy(File root, File legacy) {
        if (!legacy.isDirectory()) return;
        for (File file : sorted(root)) {
            if (!file.isFile() || !(ext(file, ".ini") || ext(file, ".vsh") || ext(file, ".fsh")))
                continue;
            File target = new File(legacy, file.getName());
            // Never overwrite user files; an incomplete move is resolved from both locations.
            if (!target.exists() && !file.renameTo(target)) Log.w(TAG, "Migration skipped: " + file.getName());
        }
    }

    private static void collectLegacy(File folder, ArrayList<ShaderInfo> out, Set<String> seen) {
        for (File file : sorted(folder)) {
            if (!file.isFile() || !ext(file, ".ini")) continue;
            if (!seen.add(file.getName().toLowerCase(Locale.ROOT))) continue;
            try {
                if (file.length() > 512 * 1024) throw new IOException("INI too large");
                ShaderInfo info = null;
                for (String line : FilesKt.readText(file, StandardCharsets.UTF_8).split("[\\n\\r]+")) {
                    if (line.startsWith("[")) {
                        if (info != null && info.fragment != null && info.vertex != null) out.add(info);
                        info = new ShaderInfo(line.replaceAll("[\\[\\]]", ""), "unknown");
                    } else if (info != null) {
                        try { info.set(line); } catch (RuntimeException e) {
                            Log.w(TAG, "Invalid shader setting in " + file.getName(), e);
                        }
                    }
                }
                if (info != null && info.fragment != null && info.vertex != null) out.add(info);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Unable to inspect " + file.getName(), e);
            }
        }
    }

    private static void addGlsl(ArrayList<ShaderInfo> out, String path) {
        ShaderInfo info = new ShaderInfo(path + " (GLSL)", "external");
        info.preset = path;
        out.add(info);
    }

    private static void collectGlsl(File dir, File root, ArrayList<ShaderInfo> out, int depth) {
        if (depth > 12) return;
        for (File file : sorted(dir)) {
            try {
                if (!within(root.getCanonicalFile(), file.getCanonicalFile())) continue;
                if (file.isDirectory()) collectGlsl(file, root, out, depth + 1);
                else if (file.isFile() && (ext(file, ".glslp")
                        || (depth == 0 && ext(file, ".glsl")))) {
                    addGlsl(out, root.toURI().relativize(file.toURI()).getPath());
                }
            } catch (IOException e) {
                Log.w(TAG, "Cannot inspect shader entry", e);
            }
        }
    }
}
