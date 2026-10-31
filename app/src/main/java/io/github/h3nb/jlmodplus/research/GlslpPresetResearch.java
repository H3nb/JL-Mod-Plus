// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disposable, CPU-only GLSLP research: strict preset subset and deterministic pass sizes.
 * Does not compile GLSL, resolve #reference, or alter the production shader selection.
 */
public final class GlslpPresetResearch {
    private static final int MAX_PASSES = 16;
    private static final int MAX_PRESET_CHARS = 256 * 1024;
    private static final int MAX_SOURCE_CHARS = 512 * 1024;
    private static final int MAX_TOTAL_SOURCE_CHARS = 2 * 1024 * 1024;

    public interface AssetReader {
        String read(String relativePath) throws IOException;
    }

    public enum SizeBasis { SOURCE, VIEWPORT, ABSOLUTE }

    public static final class Axis {
        public final SizeBasis basis;
        public final double scale;

        private Axis(SizeBasis basis, double scale) {
            this.basis = basis;
            this.scale = scale;
        }

        int resolve(int source, int viewport, int maxTextureSize) {
            double size = basis == SizeBasis.ABSOLUTE
                    ? scale : scale * (basis == SizeBasis.SOURCE ? source : viewport);
            if (!Double.isFinite(size) || size < 1.0 || size > maxTextureSize) {
                throw new IllegalArgumentException("Unsupported pass dimension " + size);
            }
            // The upstream GL2 driver assigns source/viewport float products
            // to an unsigned integer (truncate), while the preset parser
            // rounds an explicitly absolute scale to its integer dimension.
            int resolved = (int) (basis == SizeBasis.ABSOLUTE
                    ? Math.round(size) : Math.floor(size));
            if (resolved <= 0) {
                throw new IllegalArgumentException("Pass dimension rounds to zero");
            }
            return resolved;
        }
    }

    public static final class Pass {
        public final String sourcePath;
        public final String sourceText;
        public final Boolean linearFilter; // null = unspecified; not a forced nearest filter
        public final String wrapMode; // null = unspecified
        public final Axis x;
        public final Axis y;

        private Pass(String sourcePath, String sourceText, Boolean linearFilter,
                String wrapMode, Axis x, Axis y) {
            this.sourcePath = sourcePath;
            this.sourceText = sourceText;
            this.linearFilter = linearFilter;
            this.wrapMode = wrapMode;
            this.x = x;
            this.y = y;
        }
    }

    public static final class PlannedPass {
        public final Pass pass;
        public final int inputWidth, inputHeight, outputWidth, outputHeight;
        public final boolean offscreen;

        private PlannedPass(Pass pass, int inputWidth, int inputHeight,
                int outputWidth, int outputHeight, boolean offscreen) {
            this.pass = pass;
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.offscreen = offscreen;
        }
    }

    public static final class Plan {
        public final List<PlannedPass> passes;
        public final boolean finalBlitRequired;
        public final long conservativeIntermediateBytes;

        private Plan(List<PlannedPass> passes, boolean finalBlitRequired, long bytes) {
            this.passes = Collections.unmodifiableList(passes);
            this.finalBlitRequired = finalBlitRequired;
            this.conservativeIntermediateBytes = bytes;
        }
    }

    public final List<Pass> passes;
    public final Map<String, Double> parameterOverrides;

    private GlslpPresetResearch(List<Pass> passes, Map<String, Double> overrides) {
        this.passes = Collections.unmodifiableList(passes);
        parameterOverrides = Collections.unmodifiableMap(overrides);
    }

    /**
     * The reader's bundle root is authoritative; every path delivered to it is
     * normalized and confined to that logical root. A filesystem reader must also
     * enforce canonical confinement to defend against symlinks.
     */
    public static GlslpPresetResearch load(AssetReader assets, String presetPath)
            throws IOException {
        String preset = normalize("", presetPath);
        String text = assets.read(preset);
        if (text == null || text.length() > MAX_PRESET_CHARS) {
            throw new IOException("Missing or oversized GLSLP preset: " + preset);
        }
        Map<String, String> entries = parse(text);
        String countText = entries.remove("shaders");
        final int count;
        try {
            count = Integer.parseInt(countText);
        } catch (RuntimeException e) {
            throw new IOException("Missing or invalid GLSLP shaders count", e);
        }
        if (count < 1 || count > MAX_PASSES) {
            throw new IOException("Unsupported GLSLP pass count " + count);
        }

        Set<String> parameterNames = new LinkedHashSet<>();
        String parameterList = entries.remove("parameters");
        if (parameterList != null) {
            for (String name : parameterList.split(";", -1)) {
                String id = name.trim();
                if (!id.matches("[A-Za-z_][A-Za-z0-9_]*")
                        || !parameterNames.add(id)) {
                    throw new IOException("Invalid or duplicate parameter " + id);
                }
            }
        }
        Map<String, Double> overrides = new LinkedHashMap<>();
        for (String name : parameterNames) {
            String value = entries.remove(name);
            if (value != null) {
                overrides.put(name, number(value, "parameter " + name));
            }
        }

        List<Pass> passes = new ArrayList<>();
        int totalSourceChars = 0;
        String parent = preset.contains("/")
                ? preset.substring(0, preset.lastIndexOf('/') + 1) : "";
        for (int i = 0; i < count; i++) {
            String fileName = required(entries, "shader" + i);
            String shader = normalize(parent, fileName);
            if (!shader.endsWith(".glsl")) {
                throw new IOException("Unsupported shader source format: " + shader);
            }
            String shaderText = assets.read(shader);
            if (shaderText == null || shaderText.length() > MAX_SOURCE_CHARS) {
                throw new IOException("Missing or oversized shader source: " + shader);
            }
            totalSourceChars += shaderText.length();
            if (totalSourceChars > MAX_TOTAL_SOURCE_CHARS) {
                throw new IOException("GLSLP source budget exceeded");
            }

            String linear = entries.remove("filter_linear" + i);
            Boolean filter = linear == null ? null : bool(linear, "filter_linear" + i);
            String wrap = entries.remove("wrap_mode" + i);
            if (wrap != null && !wrap.equals("clamp_to_edge")
                    && !wrap.equals("repeat") && !wrap.equals("mirrored_repeat")) {
                throw new IOException("Unsupported wrap mode " + wrap + " in pass " + i);
            }

            String commonType = entries.remove("scale_type" + i);
            String xType = entries.remove("scale_type_x" + i);
            String yType = entries.remove("scale_type_y" + i);
            String commonScale = entries.remove("scale" + i);
            String xScale = entries.remove("scale_x" + i);
            String yScale = entries.remove("scale_y" + i);
            boolean sized = commonType != null || xType != null || yType != null;
            if (!sized && (commonScale != null || xScale != null || yScale != null)) {
                throw new IOException("Scale without scale_type in pass " + i);
            }
            SizeBasis defaultBasis = sized ? SizeBasis.SOURCE
                    : (i == count - 1 ? SizeBasis.VIEWPORT : SizeBasis.SOURCE);
            SizeBasis xBasis = basis(commonType != null ? commonType : xType, defaultBasis);
            SizeBasis yBasis = basis(commonType != null ? commonType : yType, defaultBasis);
            Axis x = axis(xBasis, commonScale != null ? commonScale : xScale, i);
            Axis y = axis(yBasis, commonScale != null ? commonScale : yScale, i);
            passes.add(new Pass(shader, shaderText, filter, wrap, x, y));
        }
        if (!entries.isEmpty()) {
            // Reject LUT, temporal, feedback, #reference and newer preset features
            // rather than rendering a visually wrong approximation.
            throw new IOException("Unsupported GLSLP key: " + entries.keySet().iterator().next());
        }
        return new GlslpPresetResearch(passes, overrides);
    }

    public Plan plan(int sourceW, int sourceH, int viewportW, int viewportH,
            int maxTextureSize, long maxIntermediateBytes) {
        if (sourceW <= 0 || sourceH <= 0 || viewportW <= 0 || viewportH <= 0
                || maxTextureSize <= 0 || maxIntermediateBytes < 0) {
            throw new IllegalArgumentException("Invalid source, viewport or GPU limit");
        }
        if (sourceW > maxTextureSize || sourceH > maxTextureSize) {
            throw new IllegalArgumentException("Source texture exceeds GPU limit");
        }
        int inW = sourceW, inH = sourceH;
        long bytes = 0;
        List<PlannedPass> result = new ArrayList<>();
        for (int i = 0; i < passes.size(); i++) {
            Pass pass = passes.get(i);
            int w = pass.x.resolve(inW, viewportW, maxTextureSize);
            int h = pass.y.resolve(inH, viewportH, maxTextureSize);
            // RetroArch's final-pass decision is semantic: any non-identity
            // viewport scale needs a final FBO + stock presentation, even when
            // its resolved pixel dimensions happen to equal the viewport.
            boolean identityViewport = pass.x.basis == SizeBasis.VIEWPORT
                    && pass.y.basis == SizeBasis.VIEWPORT
                    && pass.x.scale == 1.0 && pass.y.scale == 1.0;
            boolean offscreen = i < passes.size() - 1 || !identityViewport;
            if (offscreen) {
                bytes += 4L * w * h;
                if (bytes > maxIntermediateBytes) {
                    throw new IllegalArgumentException("Intermediate GPU budget exceeded");
                }
            }
            result.add(new PlannedPass(pass, inW, inH, w, h, offscreen));
            inW = w;
            inH = h;
        }
        boolean blit = result.get(result.size() - 1).offscreen;
        return new Plan(result, blit, bytes);
    }

    private static Axis axis(SizeBasis basis, String scaleText, int pass) throws IOException {
        if (basis == SizeBasis.ABSOLUTE && scaleText == null) {
            throw new IOException("Absolute size missing in pass " + pass);
        }
        double scale = scaleText == null ? 1.0 : number(scaleText, "scale in pass " + pass);
        if (scale <= 0 || (basis != SizeBasis.ABSOLUTE && scale > 64)) {
            throw new IOException("Invalid scale in pass " + pass);
        }
        return new Axis(basis, scale);
    }

    private static SizeBasis basis(String text, SizeBasis fallback) throws IOException {
        if (text == null) return fallback;
        switch (text) {
            case "source": return SizeBasis.SOURCE;
            case "viewport": return SizeBasis.VIEWPORT;
            case "absolute": return SizeBasis.ABSOLUTE;
            default: throw new IOException("Unsupported scale_type " + text);
        }
    }

    private static Boolean bool(String value, String key) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Invalid boolean " + key + "=" + value);
    }

    private static double number(String value, String key) throws IOException {
        try {
            double number = Double.parseDouble(value);
            if (Double.isFinite(number)) return number;
        } catch (NumberFormatException ignored) {
            // Report the original preset value below.
        }
        throw new IOException("Invalid numeric " + key + "=" + value);
    }

    private static String required(Map<String, String> entries, String key)
            throws IOException {
        String result = entries.remove(key);
        if (result == null || result.isEmpty()) {
            throw new IOException("Missing " + key);
        }
        return result;
    }

    private static Map<String, String> parse(String source) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String original : source.split("\n", -1)) {
            // #reference is a directive, not an ordinary # comment. Refuse it
            // until recursive import and declaring-file-relative paths are modeled.
            if (original.trim().startsWith("#reference")) {
                throw new IOException("Unsupported GLSLP #reference");
            }
            String line = uncomment(original).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 1) throw new IOException("Malformed GLSLP assignment: " + line);
            String key = line.substring(0, equals).trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IOException("Invalid GLSLP key: " + key);
            }
            String val = line.substring(equals + 1).trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            } else if (val.contains("\"")) {
                throw new IOException("Invalid GLSLP quoting for " + key);
            }
            if (values.putIfAbsent(key, val) != null) {
                throw new IOException("Duplicate GLSLP key: " + key);
            }
        }
        return values;
    }

    private static String uncomment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '"') quoted = !quoted;
            if (line.charAt(i) == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    /** Normalizes paths within a logical import root, permitting safe ../ siblings. */
    static String normalize(String base, String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('\0') >= 0) {
            throw new IOException("Unsafe asset path: " + path);
        }
        List<String> parts = new ArrayList<>();
        for (String part : (base + path).split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (parts.isEmpty()) throw new IOException("Asset escapes GLSLP bundle: " + path);
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) throw new IOException("Invalid empty asset path");
        return String.join("/", parts);
    }
}
