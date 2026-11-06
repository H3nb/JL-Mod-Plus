// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.InputStream;
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
    private static final int MAX_IMAGE_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_PIXELS = 4 * 1024 * 1024;

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

    /** Decode a GLSLP lookup image only when the owning GL context is initialized. */
    public static Bitmap loadTextureBitmap(String selection, File shaderDirectory,
            String relativePath) throws IOException {
        String path = GlslpPreset.normalize("", relativePath);
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            throw new IOException("Unsupported LUT format: " + path);
        }
        byte[] encoded;
        if (selection != null && selection.startsWith("builtin:")) {
            try (InputStream in = ContextHolder.getAppContext().getAssets().open(ASSETS + path)) {
                encoded = readLimited(in, MAX_IMAGE_FILE_BYTES);
            }
        } else {
            if (shaderDirectory == null) throw new IOException("Shader directory unavailable");
            File root = shaderDirectory.getCanonicalFile();
            File resource = new File(root, path).getCanonicalFile();
            if (!resource.getPath().startsWith(root.getPath() + File.separator)
                    || !resource.isFile() || resource.length() > MAX_IMAGE_FILE_BYTES) {
                throw new IOException("Unsafe or oversized LUT: " + path);
            }
            try (InputStream in = new FileInputStream(resource)) {
                encoded = readLimited(in, MAX_IMAGE_FILE_BYTES);
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS) {
            throw new IOException("Invalid or oversized LUT dimensions: " + path);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap image = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
        if (image == null || image.getWidth() != bounds.outWidth
                || image.getHeight() != bounds.outHeight) {
            if (image != null) image.recycle();
            throw new IOException("Cannot decode LUT: " + path);
        }
        return image;
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > limit) throw new IOException("LUT file exceeds byte limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
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
