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
import java.util.HashSet;
import java.util.Set;
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
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?sampler\\w+\\s+([^;]+);");
    private static final Pattern COMMENTS = Pattern.compile(
            "/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private GlslpAssets() {}

    /**
     * Resolve standalone external sources as a virtual GLSLP preset.
     * The existing GLSLP parser and GPU executor remain the only execution path.
     */
    public static GlslpPreset loadCustom(List<CustomShaderStage> stages, File shaderDirectory)
            throws IOException {
        return loadCustom(stages, shaderDirectory, null, "glsl");
    }

    /** An inherited GLSLP chain retains its original non-source pass metadata. */
    public static GlslpPreset loadCustom(List<CustomShaderStage> stages, File shaderDirectory,
            String basePreset, String mode) throws IOException {
        if (shaderDirectory == null || stages == null || stages.isEmpty() || stages.size() > 16) {
            throw new IOException("Custom shader chain must contain 1–16 stages");
        }
        File root = shaderDirectory.getCanonicalFile();
        if ("glsl".equals(mode) || mode == null) {
            if (basePreset != null) {
                GlslpPreset inherited = load(basePreset, root)
                        .withCustomSources(stages, path -> readLocal(root, path));
                validateCustomSamplers(inherited, true);
                return inherited;
            }
            return loadStandaloneStages(stages, root, false);
        }
        if ("legacy".equals(mode)) return loadStandaloneStages(stages, root, true);
        throw new IOException("Unknown custom shader mode: " + mode);
    }

    private static GlslpPreset loadStandaloneStages(List<CustomShaderStage> stages,
            File root, boolean legacy) throws IOException {
        StringBuilder preset = new StringBuilder("shaders = ").append(stages.size()).append('\n');
        java.util.Map<String, String> generated = new java.util.HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            CustomShaderStage stage = stages.get(i);
            if (stage == null || stage.path == null || stage.path.indexOf('\n') >= 0
                    || stage.path.indexOf('\r') >= 0 || stage.path.indexOf('=') >= 0
                    || stage.sourceScale == 0 || (stage.sourceScale != 1
                    && stage.sourceScale != 2 && stage.sourceScale != 4)) {
                throw new IOException("Invalid custom shader stage");
            }
            String path = GlslpPreset.normalize("", stage.path);
            if (!path.equals(stage.path)) throw new IOException("Unsafe stage path: " + stage.path);
            String key;
            if (legacy) {
                if (stage.legacyVertex == null
                        || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".fsh")) {
                    throw new IOException("Legacy stage requires .vsh and .fsh");
                }
                String vertexPath = GlslpPreset.normalize("", stage.legacyVertex);
                if (!vertexPath.equals(stage.legacyVertex)
                        || !vertexPath.toLowerCase(java.util.Locale.ROOT).endsWith(".vsh")) {
                    throw new IOException("Unsafe legacy vertex path");
                }
                String vertex = readLegacySource(root, vertexPath);
                String fragment = readLegacySource(root, path);
                key = "__jlmod_legacy" + i + ".glsl";
                generated.put(key, "#ifdef VERTEX\n"
                        + "#define a_position VertexCoord\n"
                        + "#define a_texcoord0 TexCoord\n"
                        + vertex + "\n#endif\n#ifdef FRAGMENT\n"
                        + "#define sampler0 Texture\n"
                        + fragment + "\n#endif\n");
            } else {
                if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl")
                        || stage.legacyVertex != null) {
                    throw new IOException("Custom GLSL requires standalone .glsl sources");
                }
                key = path;
            }
            preset.append("shader").append(i).append(" = ").append(key).append('\n');
            if (i < stages.size() - 1) {
                preset.append("scale_type").append(i).append(" = source\n");
                preset.append("scale").append(i).append(" = ").append(stage.sourceScale).append('\n');
            }
            if (stage.filterLinear != null) {
                preset.append("filter_linear").append(i).append(" = ")
                        .append(stage.filterLinear).append('\n');
            }
        }
        GlslpPreset result = GlslpPreset.load(path -> {
            if ("__jlmod_custom__.glslp".equals(path)) return preset.toString();
            if (generated.containsKey(path)) return generated.get(path);
            return readLocal(root, path);
        }, "__jlmod_custom__.glslp");
        if (!legacy) validateCustomSamplers(result, false);
        return legacy ? result.withLegacyValues(stages) : result;
    }

    /**
     * A custom source may only sample inputs the executor actually binds.
     * Preset-derived chains additionally retain their original LUTs and causal pass aliases.
     */
    private static void validateCustomSamplers(GlslpPreset preset, boolean inherited)
            throws IOException {
        for (int i = 0; i < preset.passes.size(); i++) {
            GlslpPreset.Pass pass = preset.passes.get(i);
            Set<String> allowed = new HashSet<>();
            allowed.add("Texture");
            if (inherited) {
                if (i > 0) allowed.add("OrigTexture");
                if (i > 0) allowed.add("PassPrev2Texture");
                for (GlslpPreset.Texture texture : preset.textures) allowed.add(texture.alias);
                for (int j = 0; j < i; j++) {
                    allowed.add("Pass" + (j + 1) + "Texture");
                    String alias = preset.passes.get(j).alias;
                    if (alias != null) {
                        allowed.add(alias);
                        allowed.add(alias + "Texture");
                    }
                }
                for (int distance = 3; distance <= i; distance++) {
                    allowed.add("PassPrev" + distance + "Texture");
                }
            }
            Matcher samplers = SAMPLER.matcher(COMMENTS.matcher(pass.sourceText).replaceAll(""));
            while (samplers.find()) {
                for (String declared : samplers.group(1).split(",")) {
                    String name = declared.trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !allowed.contains(name)) {
                        throw new IOException("Custom stage requires additional sampler: "
                                + name + " (" + pass.sourcePath + ")");
                    }
                }
            }
        }
    }

    /** Old profiles may still refer to sources later moved under shaders_legacy/. */
    private static String readLegacySource(File root, String path) throws IOException {
        File found = io.github.h3nb.jlmodplus.config.ShaderCatalog.resolveLegacySource(root, path);
        if (found == null) throw new IOException("Unsafe legacy shader source: " + path);
        File canonical = found.getCanonicalFile();
        String relative = canonical.getPath();
        String prefix = root.getCanonicalPath() + File.separator;
        if (!relative.startsWith(prefix)) throw new IOException("Legacy shader escapes shader folder");
        return readLocal(root, relative.substring(prefix.length())
                .replace(File.separatorChar, '/'));
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
