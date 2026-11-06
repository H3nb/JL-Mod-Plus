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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.h3nb.jlmodplus.config.CustomShaderStage;
import javax.microedition.util.ContextHolder;

/** Resolves external shader resources; stock presentation GLSL is the only production asset. */
public final class GlslpAssets {
    private static final String ASSETS = "shaders/glslp/";
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;
    private static final int MAX_IMAGE_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_PIXELS = 4 * 1024 * 1024;

    private static final Pattern SAMPLER = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?sampler\\w+\\s+(\\w+)");
    private GlslpAssets() {}

    /**
     * Resolve standalone external sources as a virtual GLSLP preset.
     * The existing GLSLP parser and GPU executor remain the only execution path.
     */
    public static GlslpPreset loadCustom(List<CustomShaderStage> stages, File shaderDirectory)
            throws IOException {
        if (shaderDirectory == null || stages == null || stages.isEmpty() || stages.size() > 16) {
            throw new IOException("Custom shader chain must contain 1–16 stages");
        }
        StringBuilder preset = new StringBuilder("shaders = ").append(stages.size()).append('\n');
        for (int i = 0; i < stages.size(); i++) {
            CustomShaderStage stage = stages.get(i);
            if (stage == null || stage.path == null || stage.path.indexOf('\n') >= 0
                    || stage.path.indexOf('\r') >= 0 || stage.path.indexOf('=') >= 0
                    || stage.path.indexOf('\\') >= 0 || stage.path.indexOf(':') >= 0) {
                throw new IOException("Invalid custom stage path");
            }
            String path = GlslpPreset.normalize("", stage.path);
            if (!path.equals(stage.path) || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl")
                    || (stage.sourceScale != 1 && stage.sourceScale != 2 && stage.sourceScale != 4)) {
                throw new IOException("Unsupported custom stage: " + stage.path);
            }
            preset.append("shader").append(i).append(" = ").append(path).append('\n');
            if (i < stages.size() - 1) {
                preset.append("scale_type").append(i).append(" = source\n");
                preset.append("scale").append(i).append(" = ").append(stage.sourceScale).append('\n');
            }
            if (stage.filterLinear != null) {
                preset.append("filter_linear").append(i).append(" = ")
                        .append(stage.filterLinear).append('\n');
            }
        }
        File root = shaderDirectory.getCanonicalFile();
        return GlslpPreset.load(path -> {
            if ("__jlmod_custom__.glslp".equals(path)) return preset.toString();
            String source = readLocal(root, path);
            Matcher samplers = SAMPLER.matcher(source);
            while (samplers.find()) {
                if (!"Texture".equals(samplers.group(1))) {
                    throw new IOException("Custom stage requires additional sampler: "
                            + samplers.group(1) + " (" + path + ")");
                }
            }
            return source;
        }, "__jlmod_custom__.glslp");
    }


    public static GlslpPreset load(String selection, File shaderDirectory) throws IOException {
        if (selection == null || (!selection.toLowerCase(java.util.Locale.ROOT).endsWith(".glslp")
                && !selection.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl"))
                || selection.contains("\\") || selection.contains(":")
                || !GlslpPreset.normalize("", selection).equals(selection)) {
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
        if (shaderDirectory == null) throw new IOException("Shader directory unavailable");
        File root = shaderDirectory.getCanonicalFile();
        File resource = new File(root, path).getCanonicalFile();
        if (!resource.getPath().startsWith(root.getPath() + File.separator)
                || !resource.isFile() || resource.length() > MAX_IMAGE_FILE_BYTES) {
            throw new IOException("Unsafe or oversized LUT: " + path);
        }
        byte[] encoded;
        try (InputStream in = new FileInputStream(resource)) {
            encoded = readLimited(in, MAX_IMAGE_FILE_BYTES);
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
