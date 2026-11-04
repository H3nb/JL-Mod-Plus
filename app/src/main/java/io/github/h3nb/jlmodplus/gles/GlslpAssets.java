// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.microedition.util.ContextHolder;

/** Resolves packaged demos and local shader resources without path traversal. */
public final class GlslpAssets {
    private static final String ASSETS = "shaders/glslp/";
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;

    private GlslpAssets() {}

    public static GlslpPreset load(String selection, File shaderDirectory) throws IOException {
        if ("builtin:scanlines".equals(selection)) {
            return GlslpPreset.load(GlslpAssets::readBundled, "scanlines.glslp");
        }
        if ("builtin:soften".equals(selection)) {
            return GlslpPreset.load(GlslpAssets::readBundled, "soften.glslp");
        }
        String bundled = null;
        if ("builtin:libretro:fsr".equals(selection)) bundled = "libretro/fsr/fsr.glslp";
        if ("builtin:libretro:xbrz-crisp".equals(selection)) bundled = "libretro/xbrz/xbrz-crisp.glslp";
        if ("builtin:libretro:xbrz-4x".equals(selection)) bundled = "libretro/xbrz/4xbrz-linear.glslp";
        if ("builtin:glslp:stress8".equals(selection)) bundled = "libretro/stress/eight-pass-composition.glslp";
        if (bundled != null) return GlslpPreset.load(GlslpAssets::readBundled, bundled);
        if (selection == null || (!selection.endsWith(".glslp") && !selection.endsWith(".glsl"))
                || (selection.contains("/") && !selection.startsWith("shaders_glsl/")) || selection.contains("\\") || selection.contains(":")) {
            throw new IOException("Unsupported shader selection");
        }
        if (shaderDirectory == null) throw new IOException("Shader directory unavailable");
        File root = shaderDirectory.getCanonicalFile();
        return GlslpPreset.load(path -> readLocal(root, path), selection);
    }

    public static String stockSource() throws IOException {
        return readBundled("stock.glsl");
    }

    private static String readBundled(String path) throws IOException {
        String value = ContextHolder.getAssetAsString(ASSETS + path);
        if (value == null || value.length() > MAX_RESOURCE_BYTES) {
            throw new IOException("Missing or oversized built-in shader: " + path);
        }
        return value;
    }

    private static String readLocal(File root, String relative) throws IOException {
        File resource = new File(root, relative).getCanonicalFile();
        if (!resource.getPath().startsWith(root.getPath() + File.separator)
                || !resource.isFile() || resource.length() > MAX_RESOURCE_BYTES) {
            throw new IOException("Unsafe or oversized shader file: " + relative);
        }
        try (FileInputStream stream = new FileInputStream(resource);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) != -1) {
                if (out.size() + n > MAX_RESOURCE_BYTES) throw new IOException("Shader size limit exceeded");
                out.write(chunk, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
