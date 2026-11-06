// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict GLSLP subset: resource-safe source selection and deterministic pass sizing.
 * Unsupported preset keys are rejected rather than silently misrendered.
 */
public final class GlslpPreset {
    private static final Pattern PRAGMA_PARAMETER = Pattern.compile(
            "^#pragma\\s+parameter\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+\\\"[^\\\"]*\\\""
                    + "\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$");
    private static final int MAX_PASSES = 16;
    private static final int MAX_TEXTURES = 4;
    private static final int MAX_REFERENCE_DEPTH = 8;
    private static final Pattern REFERENCE = Pattern.compile(
            "^#reference\\s+\\\"([^\\\"]+)\\\"\\s*(?:#.*)?$");
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
            if (!finite(size) || size < 1.0 || size > maxTextureSize) {
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
        public final String alias; // optional preset name for this pass's output
        public final boolean mipmapInput;
        public final boolean floatFramebuffer;
        public final int frameCountMod;
        public final Axis x;
        public final Axis y;
        public final List<Float> legacyValues;

        private Pass(String sourcePath, String sourceText, Boolean linearFilter,
                String wrapMode, String alias, boolean mipmapInput,
                boolean floatFramebuffer, int frameCountMod, Axis x, Axis y) {
            this(sourcePath, sourceText, linearFilter, wrapMode, alias, mipmapInput,
                    floatFramebuffer, frameCountMod, x, y, null);
        }

        private Pass(String sourcePath, String sourceText, Boolean linearFilter,
                String wrapMode, String alias, boolean mipmapInput,
                boolean floatFramebuffer, int frameCountMod, Axis x, Axis y,
                List<Float> legacyValues) {
            this.sourcePath = sourcePath;
            this.sourceText = sourceText;
            this.linearFilter = linearFilter;
            this.wrapMode = wrapMode;
            this.alias = alias;
            this.mipmapInput = mipmapInput;
            this.floatFramebuffer = floatFramebuffer;
            this.frameCountMod = frameCountMod;
            this.x = x;
            this.y = y;
            this.legacyValues = legacyValues == null ? null
                    : Collections.unmodifiableList(new ArrayList<>(legacyValues));
        }
    }

    /** Immutable static image sampler declared in the preset. */
    public static final class Texture {
        public final String alias, sourcePath, wrapMode;
        public final boolean linear, mipmap;

        private Texture(String alias, String path, boolean linear, String wrapMode,
                boolean mipmap) {
            this.alias = alias;
            sourcePath = path;
            this.linear = linear;
            this.wrapMode = wrapMode;
            this.mipmap = mipmap;
        }

        private Texture withPath(String path) {
            return new Texture(alias, path, linear, wrapMode, mipmap);
        }
    }

    /** Editor metadata from #pragma, with preset defaults and optional user override. */
    public static final class Parameter {
        public final String id, label;
        public final float defaultValue, minimum, maximum, step, value;

        private Parameter(String id, String label, float def, float min, float max,
                float step, float value) {
            this.id = id;
            this.label = label;
            this.defaultValue = def;
            this.minimum = min;
            this.maximum = max;
            this.step = step;
            this.value = value;
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
    public final List<Texture> textures;
    public final Map<String, Double> parameterOverrides;

    private GlslpPreset(List<Pass> passes, Map<String, Double> overrides,
            List<Texture> textures) {
        this.passes = Collections.unmodifiableList(new ArrayList<>(passes));
        this.textures = Collections.unmodifiableList(new ArrayList<>(textures));
        parameterOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    /**
     * The reader's bundle root is authoritative; every path delivered to it is
     * normalized and confined to that logical root. A filesystem reader must also
     * enforce canonical confinement to defend against symlinks.
     */
    public static GlslpPreset load(AssetReader assets, String presetPath)
            throws IOException {
        return loadReferenced(assets, normalize("", presetPath), new LinkedHashSet<>());
    }

    private static GlslpPreset loadReferenced(AssetReader assets, String preset,
            Set<String> stack) throws IOException {
        if (stack.size() >= MAX_REFERENCE_DEPTH || !stack.add(preset)) {
            throw new IOException("Cyclic or too deep GLSLP #reference: " + preset);
        }
        try {
            return readPreset(assets, preset, stack);
        } finally {
            stack.remove(preset);
        }
    }

    private static GlslpPreset readPreset(AssetReader assets, String preset,
            Set<String> stack) throws IOException {
        if (preset.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl")) {
            String source = assets.read(preset);
            if (source == null || source.length() > MAX_SOURCE_CHARS) {
                throw new IOException("Missing or oversized GLSL source: " + preset);
            }
            Pass single = new Pass(preset, source, null, null, null, false, false, 0,
                    new Axis(SizeBasis.VIEWPORT, 1), new Axis(SizeBasis.VIEWPORT, 1));
            return new GlslpPreset(Collections.singletonList(single), Collections.emptyMap(),
                    Collections.emptyList());
        }
        if (!preset.toLowerCase(java.util.Locale.ROOT).endsWith(".glslp")) throw new IOException("Unsupported GLSLP extension");
        String text = assets.read(preset);
        if (text == null || text.length() > MAX_PRESET_CHARS) {
            throw new IOException("Missing or oversized GLSLP preset: " + preset);
        }
        List<String> references = new ArrayList<>();
        Map<String, String> entries = parse(text, references);
        if (!references.isEmpty()) {
            // Simple presets inherit the shader chain and may override parameter and LUT paths.
            if (entries.containsKey("shaders") || entries.containsKey("parameters")) {
                throw new IOException("GLSLP #reference cannot redefine shader structure");
            }
            String parent = preset.contains("/")
                    ? preset.substring(0, preset.lastIndexOf('/') + 1) : "";
            GlslpPreset inherited = loadReferenced(assets,
                    normalize(parent, references.get(0)), stack);
            Map<String, Double> overrides = new LinkedHashMap<>(inherited.parameterOverrides);
            Set<String> declared = new LinkedHashSet<>();
            for (Parameter parameter : inherited.parameterDefinitions()) {
                declared.add(parameter.id);
            }
            List<Texture> textures = new ArrayList<>(inherited.textures);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (declared.contains(entry.getKey())) {
                    overrides.put(entry.getKey(), number(entry.getValue(), entry.getKey()));
                    continue;
                }
                boolean matched = false;
                for (int i = 0; i < textures.size(); i++) {
                    Texture texture = textures.get(i);
                    if (texture.alias.equals(entry.getKey())) {
                        textures.set(i, texture.withPath(normalize(parent, entry.getValue())));
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new IOException("Unsupported GLSLP #reference override: " + entry.getKey());
                }
            }
            GlslpPreset resolved = new GlslpPreset(inherited.passes, overrides, textures);
            resolved.parameterDefinitions(); // Check bounds before exposing a preset to the UI or GPU.
            return resolved;
        }
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
        Set<String> passAliases = new LinkedHashSet<>();
        int totalSourceChars = 0;
        String parent = preset.contains("/")
                ? preset.substring(0, preset.lastIndexOf('/') + 1) : "";
        for (int i = 0; i < count; i++) {
            String fileName = required(entries, "shader" + i);
            String shader = normalize(parent, fileName);
            if (!shader.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl")) {
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

            String alias = entries.remove("alias" + i);
            if (alias != null && !alias.isEmpty()
                    && (!alias.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || reservedSamplerName(alias) || parameterNames.contains(alias)
                    || passAliases.contains(alias + "Texture")
                    || (alias.endsWith("Texture") && passAliases.contains(
                            alias.substring(0, alias.length() - "Texture".length())))
                    || !passAliases.add(alias))) {
                throw new IOException("Invalid or duplicate GLSLP pass alias: " + alias);
            }
            String linear = entries.remove("filter_linear" + i);
            Boolean filter = linear == null ? null : bool(linear, "filter_linear" + i);
            String wrap = entries.remove("wrap_mode" + i);
            if (wrap != null && !wrap.equals("clamp_to_edge")
                    && !wrap.equals("clamp_to_border")
                    && !wrap.equals("repeat") && !wrap.equals("mirrored_repeat")) {
                throw new IOException("Unsupported wrap mode " + wrap + " in pass " + i);
            }
            String mipmapText = entries.remove("mipmap_input" + i);
            boolean mipmap = mipmapText != null && bool(mipmapText, "mipmap_input" + i);
            String floatValue = entries.remove("float_framebuffer" + i);
            boolean floatFramebuffer = floatValue != null
                    && bool(floatValue, "float_framebuffer" + i);
            String srgbValue = entries.remove("srgb_framebuffer" + i);
            if (srgbValue != null && bool(srgbValue, "srgb_framebuffer" + i)) {
                throw new IOException("Unsupported GLSLP framebuffer format: srgb_framebuffer" + i);
            }
            String frameMod = entries.remove("frame_count_mod" + i);
            int frameCountMod = 0;
            if (frameMod != null) {
                try {
                    frameCountMod = Integer.parseInt(frameMod);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid GLSLP frame_count_mod" + i, e);
                }
                if (frameCountMod < 0) {
                    throw new IOException("Negative GLSLP frame_count_mod" + i);
                }
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
            passes.add(new Pass(shader, shaderText, filter, wrap, alias, mipmap,
                    floatFramebuffer, frameCountMod, x, y));
        }
        Set<String> passSamplerNames = new LinkedHashSet<>(passAliases);
        for (String alias : passAliases) passSamplerNames.add(alias + "Texture");
        List<Texture> textures = new ArrayList<>();
        String textureList = entries.remove("textures");
        if (textureList != null && !textureList.isEmpty()) {
            Set<String> aliases = new LinkedHashSet<>();
            for (String token : textureList.split(";", -1)) {
                String alias = token.trim();
                if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*")
                        || alias.equals("Texture") || alias.equals("OrigTexture")
                        || alias.equals("PassPrev2Texture") || alias.equals("MVPMatrix")
                        || alias.equals("InputSize") || alias.equals("TextureSize")
                        || alias.equals("OutputSize") || parameterNames.contains(alias)
                        || passSamplerNames.contains(alias) || reservedSamplerName(alias)
                        || !aliases.add(alias)) {
                    throw new IOException("Invalid or reserved GLSLP texture alias: " + alias);
                }
                if (textures.size() >= MAX_TEXTURES) {
                    throw new IOException("Too many GLSLP lookup textures");
                }
                String image = normalize(parent, required(entries, alias));
                String extension = image.toLowerCase(java.util.Locale.ROOT);
                if (!(extension.endsWith(".png") || extension.endsWith(".jpg")
                        || extension.endsWith(".jpeg"))) {
                    throw new IOException("Unsupported GLSLP texture format: " + image);
                }
                String linearValue = entries.remove(alias + "_linear");
                // Libretro's lookup-texture default is bilinear when unspecified.
                boolean linear = linearValue == null || bool(linearValue, alias + "_linear");
                String wrap = entries.remove(alias + "_wrap_mode");
                String repeat = entries.remove(alias + "_repeat");
                if (wrap != null && repeat != null) {
                    throw new IOException("Conflicting GLSLP lookup wrap options: " + alias);
                }
                if (repeat != null) wrap = bool(repeat, alias + "_repeat")
                        ? "repeat" : "clamp_to_edge";
                if (wrap == null) wrap = "clamp_to_edge";
                if (!wrap.equals("clamp_to_edge") && !wrap.equals("clamp_to_border")
                        && !wrap.equals("repeat") && !wrap.equals("mirrored_repeat")) {
                    throw new IOException("Unsupported GLSLP lookup wrap: " + wrap);
                }
                String mipmapValue = entries.remove(alias + "_mipmap");
                boolean mipmap = mipmapValue != null && bool(mipmapValue, alias + "_mipmap");
                textures.add(new Texture(alias, image, linear, wrap, mipmap));
            }
        }
        if (!entries.isEmpty()) {
            // Reject unsupported temporal, feedback and newer preset features
            // rather than rendering a visually wrong approximation.
            throw new IOException("Unsupported GLSLP key: " + entries.keySet().iterator().next());
        }
        return new GlslpPreset(passes, overrides, textures);
    }

    /**
     * Preserve original pass sizing, alias, LUT, framebuffer flags and parameter
     * overrides, even if the user changes only one of the source files.
     * sourceScale=0 retains the exact inherited scale basis and value.
     */
    public GlslpPreset withCustomSources(
            List<io.github.h3nb.jlmodplus.config.CustomShaderStage> stages,
            AssetReader assets) throws IOException {
        if (stages == null || stages.isEmpty() || stages.size() > MAX_PASSES) {
            throw new IOException("Custom shader chain must contain 1–16 stages");
        }
        List<Pass> edited = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            io.github.h3nb.jlmodplus.config.CustomShaderStage stage = stages.get(i);
            String path = normalize("", stage.path);
            if (!path.equals(stage.path) || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".glsl")) {
                throw new IOException("Unsupported custom shader path: " + stage.path);
            }
            String source = assets.read(path);
            if (source == null || source.length() > MAX_SOURCE_CHARS) {
                throw new IOException("Missing or oversized custom shader: " + path);
            }
            Pass old = i < passes.size() ? passes.get(i) : null;
            int scale = stage.sourceScale;
            if (scale != 0 && scale != 1 && scale != 2 && scale != 4) {
                throw new IOException("Invalid custom stage scale: " + scale);
            }
            boolean preserveSize = old != null && scale == 0;
            Axis x = preserveSize ? old.x : new Axis(
                    i == stages.size() - 1 ? SizeBasis.VIEWPORT : SizeBasis.SOURCE,
                    i == stages.size() - 1 ? 1 : scale == 0 ? 1 : scale);
            Axis y = preserveSize ? old.y : new Axis(
                    i == stages.size() - 1 ? SizeBasis.VIEWPORT : SizeBasis.SOURCE,
                    i == stages.size() - 1 ? 1 : scale == 0 ? 1 : scale);
            edited.add(new Pass(path, source,
                    stage.filterLinear != null ? stage.filterLinear
                            : old == null ? null : old.linearFilter,
                    old == null ? null : old.wrapMode,
                    old == null ? null : old.alias,
                    old != null && old.mipmapInput,
                    old != null && old.floatFramebuffer,
                    old == null ? 0 : old.frameCountMod,
                    x, y));
        }
        GlslpPreset updated = new GlslpPreset(edited, parameterOverrides, textures);
        updated.parameterDefinitions();
        return updated;
    }

    public GlslpPreset withLegacyValues(
            List<io.github.h3nb.jlmodplus.config.CustomShaderStage> stages) {
        List<Pass> result = new ArrayList<>(passes.size());
        for (int i = 0; i < passes.size(); i++) {
            Pass pass = passes.get(i);
            result.add(new Pass(pass.sourcePath, pass.sourceText, pass.linearFilter,
                    pass.wrapMode, pass.alias, pass.mipmapInput,
                    pass.floatFramebuffer, pass.frameCountMod, pass.x, pass.y,
                    stages.get(i).legacyValues));
        }
        return new GlslpPreset(result, parameterOverrides, textures);
    }

    /**
     * Resolve upstream #pragma defaults and the preset's explicit overrides.
     * All parameters need values when PARAMETER_UNIFORM is enabled: sending only
     * the overridden names silently leaves the remaining uniforms at zero.
     * This is research metadata, not evidence the shaders link on a given GLES.
     */
    public GlslpPreset withParameterOverrides(Map<String, Float> userOverrides) {
        if (userOverrides == null || userOverrides.isEmpty()) return this;
        Map<String, Double> combined = new LinkedHashMap<>(parameterOverrides);
        for (Map.Entry<String, Float> entry : userOverrides.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Null GLSL parameter");
            }
            combined.put(entry.getKey(), entry.getValue().doubleValue());
        }
        return new GlslpPreset(passes, combined, textures);
    }

    /** Source declarations provide the editor's labels/ranges as well as runtime defaults. */
    public List<Parameter> parameterDefinitions() throws IOException {
        Map<String, Parameter> definitions = new LinkedHashMap<>();
        for (Pass pass : passes) {
            for (String line : pass.sourceText.split("\\n")) {
                String declaration = line.trim();
                if (!declaration.startsWith("#pragma parameter")) continue;
                Matcher m = PRAGMA_PARAMETER.matcher(declaration);
                if (!m.matches()) {
                    throw new IOException("Malformed GLSL parameter declaration in " + pass.sourcePath);
                }
                String id = m.group(1);
                String label = declaration.substring(declaration.indexOf('"') + 1, declaration.lastIndexOf('"'));
                float fallback = checkedFloat(number(m.group(2), id), id);
                float minimum = checkedFloat(number(m.group(3), id), id);
                float maximum = checkedFloat(number(m.group(4), id), id);
                float step = checkedFloat(number(m.group(5), id), id);
                if (minimum > maximum || step <= 0 || fallback < minimum || fallback > maximum) {
                    throw new IOException("Invalid GLSL parameter range: " + id);
                }
                Parameter previous = definitions.get(id);
                if (previous != null) {
                    if (previous.defaultValue != fallback || previous.minimum != minimum
                            || previous.maximum != maximum || previous.step != step) {
                        throw new IOException("Conflicting GLSL parameter: " + id);
                    }
                } else {
                    definitions.put(id, new Parameter(id, label, fallback, minimum,
                            maximum, step, fallback));
                }
            }
        }
        for (Map.Entry<String, Double> entry : parameterOverrides.entrySet()) {
            String id = entry.getKey();
            Parameter declared = definitions.get(id);
            if (declared == null) {
                throw new IOException("Preset overrides undeclared GLSL parameter: " + id);
            }
            float value = checkedFloat(entry.getValue(), id);
            if (value < declared.minimum || value > declared.maximum) {
                throw new IOException("Preset parameter outside GLSL range: " + id);
            }
            definitions.put(id, new Parameter(declared.id, declared.label,
                    declared.defaultValue, declared.minimum, declared.maximum,
                    declared.step, value));
        }
        return Collections.unmodifiableList(new ArrayList<>(definitions.values()));
    }

    public Map<String, Float> resolvedParameters() throws IOException {
        Map<String, Float> values = new LinkedHashMap<>();
        for (Parameter parameter : parameterDefinitions()) {
            values.put(parameter.id, parameter.value);
        }
        return Collections.unmodifiableMap(values);
    }

    private static float checkedFloat(double value, String name) throws IOException {
        float converted = (float) value;
        if (Float.isInfinite(converted) || Float.isNaN(converted)) {
            throw new IOException("GLSL parameter cannot be represented as float: " + name);
        }
        return converted;
    }

    public Plan plan(int sourceW, int sourceH, int viewportW, int viewportH,
            int maxTextureSize, long maxIntermediateBytes) {
        return plan(sourceW, sourceH, viewportW, viewportH,
                maxTextureSize, maxIntermediateBytes, false);
    }

    /** Allow GLES2 NPOT repeat/mips only when the GL context supports full NPOT sampling. */
    public Plan plan(int sourceW, int sourceH, int viewportW, int viewportH,
            int maxTextureSize, long maxIntermediateBytes, boolean fullNpot) {
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
            if ((pass.mipmapInput || "repeat".equals(pass.wrapMode)
                    || "mirrored_repeat".equals(pass.wrapMode)
                    || "clamp_to_border".equals(pass.wrapMode))
                    && !fullNpot && (!powerOfTwo(inW) || !powerOfTwo(inH))) {
                throw new IllegalArgumentException(
                        "GLES2 input for mipmaps/repeat must be power-of-two in pass " + i);
            }
            if (pass.mipmapInput) {
                int inputBytesPerPixel = i > 0 && passes.get(i - 1).floatFramebuffer ? 8 : 4;
                bytes += mipChainBytes(inW, inH, inputBytesPerPixel);
                if (bytes > maxIntermediateBytes) {
                    throw new IllegalArgumentException("Intermediate GPU budget exceeded");
                }
            }
            int w = pass.x.resolve(inW, viewportW, maxTextureSize);
            int h = pass.y.resolve(inH, viewportH, maxTextureSize);
            // RetroArch's final-pass decision is semantic: any non-identity
            // viewport scale needs a final FBO + stock presentation, even when
            // its resolved pixel dimensions happen to equal the viewport.
            boolean identityViewport = pass.x.basis == SizeBasis.VIEWPORT
                    && pass.y.basis == SizeBasis.VIEWPORT
                    && pass.x.scale == 1.0 && pass.y.scale == 1.0;
            boolean offscreen = i < passes.size() - 1 || !identityViewport
                    || pass.floatFramebuffer;
            if (offscreen) {
                bytes += (pass.floatFramebuffer ? 8L : 4L) * w * h;
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

    private static boolean powerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    static long mipChainBytes(int width, int height) {
        return mipChainBytes(width, height, 4);
    }

    static long mipChainBytes(int width, int height, int bytesPerPixel) {
        long bytes = 0;
        while (width > 1 || height > 1) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            bytes += (long) bytesPerPixel * width * height;
        }
        return bytes;
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

    private static boolean reservedSamplerName(String name) {
        return name.equals("Texture") || name.equals("OrigTexture")
                || name.equals("PassPrev2Texture") || name.equals("FeedbackTexture")
                || name.equals("PrevTexture") || name.equals("MVPMatrix")
                || name.equals("InputSize") || name.equals("TextureSize")
                || name.equals("OutputSize") || name.equals("FrameCount")
                || name.equals("FrameDirection")
                || name.matches("Pass[0-9]+(?:Texture)?|PassPrev[0-9]+(?:Texture)?"
                        + "|Prev[0-9]+(?:Texture)?");
    }

    private static Boolean bool(String value, String key) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Invalid boolean " + key + "=" + value);
    }

    private static double number(String value, String key) throws IOException {
        try {
            double number = Double.parseDouble(value);
            if (finite(number)) return number;
        } catch (NumberFormatException ignored) {
            // Report the original preset value below.
        }
        throw new IOException("Invalid numeric " + key + "=" + value);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String required(Map<String, String> entries, String key)
            throws IOException {
        String result = entries.remove(key);
        if (result == null || result.isEmpty()) {
            throw new IOException("Missing " + key);
        }
        return result;
    }

    private static Map<String, String> parse(String source, List<String> references)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String original : source.split("\n", -1)) {
            if (original.trim().startsWith("#reference")) {
                Matcher matcher = REFERENCE.matcher(original.trim());
                if (!matcher.matches() || !references.isEmpty()) {
                    throw new IOException("Invalid or multiple GLSLP #reference");
                }
                references.add(matcher.group(1));
                continue;
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
            if (values.containsKey(key)) {
                throw new IOException("Duplicate GLSLP key: " + key);
            }
            values.put(key, val);
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
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.toString();
    }
}
