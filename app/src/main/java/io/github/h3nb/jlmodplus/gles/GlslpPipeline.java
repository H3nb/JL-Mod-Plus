// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static android.opengl.GLES20.*;

import android.graphics.Bitmap;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * GLES2 presentation-only execution of bounded GLSLP multipass presets.
 * Uses the existing guest frame sequence; unsupported dependencies fail closed.
 */
public final class GlslpPipeline {
    // GL_OES/EXT_texture_border_clamp and GLES 3.2 share these enum values.
    private static final int GL_RGBA16F_VALUE = 0x881A;
    private static final int GL_HALF_FLOAT_OES_VALUE = 0x8D61;
    private static final int GL_HALF_FLOAT_VALUE = 0x140B;
    private static final int GL_CLAMP_TO_BORDER_VALUE = 0x812D;
    private static final int GL_TEXTURE_BORDER_COLOR_VALUE = 0x1004;
    private static final float[] TRANSPARENT_BORDER = {0f, 0f, 0f, 0f};
    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1
    };
    private static final float[] FULL_QUAD = {
            -1,  1, 0, 1, 0, 1, 0, 1,
            -1, -1, 0, 1, 0, 0, 0, 1,
             1,  1, 0, 1, 1, 1, 0, 1,
             1, -1, 0, 1, 1, 0, 0, 1
    };
    private final GlslpPreset preset;
    private final Map<String, Float> parameters;
    private final boolean fullNpot, borderClamp, halfFloatLinear, halfFloatEs3;
    private final int[] programs, framebuffer, intermediate, targetWidth, targetHeight;
    private final boolean[] usesOriginal, usesPrev2;
    private final int[] vertexAttributes, textureAttributes;
    private final int[] lookupIds, lookupWidths, lookupHeights;
    private final int[][] lookupUniforms;
    private final List<List<PassReference>> passReferences = new ArrayList<>();
    private int extraPassTextureUnits;

    private static final class PassReference {
        final String uniformName;
        final int location, sourcePass, unit;

        PassReference(String name, int location, int sourcePass, int unit) {
            this.uniformName = name;
            this.location = location;
            this.sourcePass = sourcePass;
            this.unit = unit;
        }
    }
    private static final long MAX_LOOKUP_BYTES = 16L * 1024L * 1024L;

    public interface TextureLoader {
        Bitmap load(String sourcePath) throws IOException;
    }
    private final List<Map<String, Integer>> uniformLocations = new ArrayList<>();
    private final FloatBuffer quad = ByteBuffer.allocateDirect(4 * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] legacyUniformValues = new float[4];
    private long processedSequence = Long.MIN_VALUE;
    private GlslpPreset.Plan cachedPlan;
    private int planSourceWidth, planSourceHeight, planViewportWidth, planViewportHeight;

    public GlslpPipeline(GlslpPreset resolved, String stockSource) throws IOException {
        this(resolved, stockSource, path -> {
            throw new IOException("GLSLP lookup image loader is unavailable: " + path);
        });
    }

    public GlslpPipeline(GlslpPreset resolved, String stockSource,
            TextureLoader images) throws IOException {
        if (resolved == null || resolved.passes.isEmpty() || resolved.passes.size() > 16) {
            throw new IOException("GLSLP supports 1–16 passes in this prototype");
        }
        preset = resolved;
        String extensions = glGetString(GL_EXTENSIONS);
        String version = glGetString(GL_VERSION);
        fullNpot = (version != null && version.startsWith("OpenGL ES 3."))
                || (extensions != null
                    && (" " + extensions + " ").contains(" GL_OES_texture_npot "));
        borderClamp = supportsBorderClamp(version, extensions);
        halfFloatEs3 = version != null && version.matches("^OpenGL ES 3\\..*");
        halfFloatLinear = halfFloatEs3 || hasExtension(extensions, "GL_OES_texture_half_float_linear");
        for (GlslpPreset.Pass pass : preset.passes) {
            if (pass.floatFramebuffer && !supportsHalfFloatFramebuffer(version, extensions)) {
                throw new IOException("GLSLP float_framebuffer requires renderable RGBA16F");
            }
        }
        for (GlslpPreset.Pass pass : preset.passes) {
            requireBorderClamp(pass.wrapMode);
        }
        for (GlslpPreset.Texture texture : preset.textures) {
            requireBorderClamp(texture.wrapMode);
        }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(resolved.resolvedParameters()));
        int n = preset.passes.size();
        programs = new int[n + 1];
        framebuffer = new int[n];
        intermediate = new int[n];
        targetWidth = new int[n];
        targetHeight = new int[n];
        usesOriginal = new boolean[n];
        usesPrev2 = new boolean[n];
        vertexAttributes = new int[n + 1];
        textureAttributes = new int[n + 1];
        lookupIds = new int[preset.textures.size()];
        lookupWidths = new int[preset.textures.size()];
        lookupHeights = new int[preset.textures.size()];
        lookupUniforms = new int[n][preset.textures.size()];
        for (int i = 0; i <= n; i++) uniformLocations.add(new HashMap<>());
        try {
            for (int i = 0; i < n; i++) {
                GlslpPreset.Pass pass = preset.passes.get(i);
                programs[i] = link(pass.sourceText, !parameters.isEmpty());
                vertexAttributes[i] = glGetAttribLocation(programs[i], "VertexCoord");
                textureAttributes[i] = glGetAttribLocation(programs[i], "TexCoord");
                usesOriginal[i] = glGetUniformLocation(programs[i], "OrigTexture") >= 0;
                usesPrev2[i] = glGetUniformLocation(programs[i], "PassPrev2Texture") >= 0;
                if (i == 0 && (usesOriginal[i] || usesPrev2[i])) {
                    throw new IOException("First pass cannot read preceding images");
                }
                for (String unsupported : new String[] {
                        "PrevTexture", "Prev1Texture", "Prev2Texture", "Prev3Texture",
                        "FeedbackTexture"
                }) {
                    if (glGetUniformLocation(programs[i], unsupported) >= 0) {
                        throw new IOException("Unsupported GLSLP dependency " + unsupported);
                    }
                }
                validateLinkedSamplers(i);
            }
            programs[n] = link(stockSource, false);
            vertexAttributes[n] = glGetAttribLocation(programs[n], "VertexCoord");
            textureAttributes[n] = glGetAttribLocation(programs[n], "TexCoord");
            resolvePassReferences();
            uploadLookups(images);
        } catch (IOException | RuntimeException e) {
            release();
            throw e;
        }
    }

    private static boolean hasExtension(String extensions, String extension) {
        return extensions != null && (" " + extensions + " ").contains(" " + extension + " ");
    }

    static boolean supportsHalfFloatFramebuffer(String version, String extensions) {
        boolean es3 = version != null && version.matches("^OpenGL ES 3\\..*");
        boolean renderable = hasExtension(extensions, "GL_EXT_color_buffer_half_float")
                || hasExtension(extensions, "GL_EXT_color_buffer_float");
        return renderable && (es3 || hasExtension(extensions, "GL_OES_texture_half_float"));
    }

    static boolean supportsBorderClamp(String version, String extensions) {
        boolean core = version != null
                && version.matches("^OpenGL ES 3\\.(?:[2-9]|[1-9][0-9]).*");
        if (core) return true;
        if (extensions == null) return false;
        String tokens = " " + extensions + " ";
        return tokens.contains(" GL_OES_texture_border_clamp ")
                || tokens.contains(" GL_EXT_texture_border_clamp ")
                || tokens.contains(" GL_NV_texture_border_clamp ");
    }

    private void requireBorderClamp(String wrapMode) throws IOException {
        if ("clamp_to_border".equals(wrapMode) && !borderClamp) {
            throw new IOException("GLSLP clamp_to_border requires GLES 3.2 or border-clamp extension");
        }
    }

    private static int wrapValue(String wrap) {
        if ("clamp_to_border".equals(wrap)) return GL_CLAMP_TO_BORDER_VALUE;
        if ("repeat".equals(wrap)) return GL_REPEAT;
        if ("mirrored_repeat".equals(wrap)) return GL_MIRRORED_REPEAT;
        return GL_CLAMP_TO_EDGE;
    }

    private static void applyWrap(String wrap) {
        if ("clamp_to_border".equals(wrap)) {
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR_VALUE,
                    TRANSPARENT_BORDER, 0);
        }
        int mode = wrapValue(wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mode);
    }

    /**
     * Linked-program uniforms are authoritative after GLSL macro expansion.
     * Reject active samplers the executor cannot bind; otherwise the GL default
     * sampler unit (zero) may silently render a plausible but incorrect frame.
     * Causal checks for named pass samplers are performed by resolvePassReferences.
     */
    private void validateLinkedSamplers(int stage) throws IOException {
        Set<String> boundNames = new HashSet<>();
        boundNames.add("Texture");
        boundNames.add("OrigTexture");
        boundNames.add("PassPrev2Texture");
        for (GlslpPreset.Texture lookup : preset.textures) {
            boundNames.add(lookup.alias);
        }
        for (int i = 0; i < preset.passes.size(); i++) {
            boundNames.add("Pass" + (i + 1) + "Texture");
            String alias = preset.passes.get(i).alias;
            if (alias != null) {
                boundNames.add(alias);
                boundNames.add(alias + "Texture");
            }
        }
        for (int distance = 3; distance <= 16; distance++) {
            boundNames.add("PassPrev" + distance + "Texture");
        }
        int[] count = new int[1], size = new int[1], type = new int[1];
        glGetProgramiv(programs[stage], GL_ACTIVE_UNIFORMS, count, 0);
        for (int index = 0; index < count[0]; index++) {
            String name = glGetActiveUniform(programs[stage], index, size, 0, type, 0);
            if (type[0] == GL_SAMPLER_2D) {
                if (size[0] != 1 || !boundNames.contains(name)) {
                    throw new IOException("Unbound GLSLP sampler " + name + " in pass " + stage);
                }
            } else if (type[0] == GL_SAMPLER_CUBE || type[0] == 0x8D66) {
                // GL_SAMPLER_EXTERNAL_OES (0x8D66) cannot use GL_TEXTURE_2D bindings.
                throw new IOException("Unsupported GLSLP sampler type for " + name);
            }
        }
    }

    /**
     * Bind only causal earlier-pass outputs. Sampler location and texture-unit
     * allocation are resolved once per GL program, not on every frame.
     * A previously rendered pass is already retained as an offscreen texture.
     */
    private void resolvePassReferences() throws IOException {
        int[] limits = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, limits, 0);
        int firstExtraUnit = 3 + lookupIds.length;
        for (int stage = 0; stage < preset.passes.size(); stage++) {
            int[] assignedUnits = new int[preset.passes.size()];
            Arrays.fill(assignedUnits, -1);
            int[] nextUnit = {firstExtraUnit};
            List<PassReference> refs = new ArrayList<>();
            for (int source = 0; source < preset.passes.size(); source++) {
                collectPassReference(stage, source, "Pass" + (source + 1) + "Texture",
                        assignedUnits, nextUnit, limits[0], refs);
                String alias = preset.passes.get(source).alias;
                if (alias != null && !alias.isEmpty()) {
                    collectPassReference(stage, source, alias,
                            assignedUnits, nextUnit, limits[0], refs);
                    collectPassReference(stage, source, alias + "Texture",
                            assignedUnits, nextUnit, limits[0], refs);
                }
            }
            for (int distance = 3; distance <= 16; distance++) {
                collectPassReference(stage, stage - distance,
                        "PassPrev" + distance + "Texture",
                        assignedUnits, nextUnit, limits[0], refs);
            }
            extraPassTextureUnits = Math.max(extraPassTextureUnits,
                    nextUnit[0] - firstExtraUnit);
            passReferences.add(Collections.unmodifiableList(refs));
        }
    }

    private void collectPassReference(int stage, int source, String name,
            int[] assignedUnits, int[] nextUnit, int maxUnits,
            List<PassReference> refs) throws IOException {
        int location = glGetUniformLocation(programs[stage], name);
        if (location < 0) return;
        if (source < 0 || source >= stage) {
            throw new IOException("Noncausal GLSLP pass dependency: " + name
                    + " in pass " + stage);
        }
        int unit;
        if (source == stage - 1) {
            unit = 0; // Identical to the regular previous-pass Texture input.
        } else if (source == stage - 2 && usesPrev2[stage]) {
            unit = 2; // Already bound by the existing PassPrev2Texture contract.
        } else if (assignedUnits[source] >= 0) {
            unit = assignedUnits[source];
        } else {
            unit = nextUnit[0]++;
            if (unit >= maxUnits) {
                throw new IOException("Too many simultaneous GLSLP samplers in pass " + stage);
            }
            assignedUnits[source] = unit;
        }
        refs.add(new PassReference(name, location, source, unit));
    }

    private void uploadLookups(TextureLoader images) throws IOException {
        if (lookupIds.length == 0) return;
        int[] maxUnits = new int[1], maxSize = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, maxUnits, 0);
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxSize, 0);
        if (lookupIds.length + 3 > maxUnits[0]) {
            throw new IOException("Not enough fragment texture units for GLSLP LUTs");
        }
        long bytes = 0;
        glGenTextures(lookupIds.length, lookupIds, 0);
        try {
            for (int i = 0; i < lookupIds.length; i++) {
                GlslpPreset.Texture lookup = preset.textures.get(i);
                Bitmap bitmap = images.load(lookup.sourcePath);
                if (bitmap == null) throw new IOException("Missing GLSLP LUT: " + lookup.sourcePath);
                try {
                    int w = bitmap.getWidth(), h = bitmap.getHeight();
                    long baseBytes = 4L * w * h;
                    bytes += baseBytes + (lookup.mipmap
                            ? GlslpPreset.mipChainBytes(w, h) : 0);
                    if (w <= 0 || h <= 0 || w > maxSize[0] || h > maxSize[0]
                            || bytes > MAX_LOOKUP_BYTES) {
                        throw new IOException("GLSLP lookup texture budget exceeded: " + lookup.sourcePath);
                    }
                    boolean pot = (w & (w - 1)) == 0 && (h & (h - 1)) == 0;
                    if (!fullNpot && !pot
                            && (lookup.mipmap || !"clamp_to_edge".equals(lookup.wrapMode))) {
                        throw new IOException("GLES2 NPOT lookup requires clamp and no mipmap: "
                                + lookup.sourcePath);
                    }
                    lookupWidths[i] = w;
                    lookupHeights[i] = h;
                    glActiveTexture(GL_TEXTURE0 + 3 + i);
                    glBindTexture(GL_TEXTURE_2D, lookupIds[i]);
                    int baseFilter = lookup.linear ? GL_LINEAR : GL_NEAREST;
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, baseFilter);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, !lookup.mipmap
                            ? baseFilter : (lookup.linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST));
                    applyWrap(lookup.wrapMode);
                    GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0);
                    if (lookup.mipmap) glGenerateMipmap(GL_TEXTURE_2D);
                } finally {
                    bitmap.recycle();
                }
                for (int pass = 0; pass < preset.passes.size(); pass++) {
                    lookupUniforms[pass][i] = glGetUniformLocation(programs[pass], lookup.alias);
                }
            }
            verifyGlError();
        } finally {
            for (int i = 0; i < lookupIds.length; i++) {
                glActiveTexture(GL_TEXTURE0 + 3 + i);
                glBindTexture(GL_TEXTURE_2D, 0);
            }
            glActiveTexture(GL_TEXTURE0);
        }
    }

    /**
     * Real production quad uses (x,y,u,v) per vertex in the order LT LB RT RB.
     * GLSLP requires an exact content-sized source, not a padded softbar backing.
     */
    public void draw(int sourceTexture, FloatBuffer legacyQuad,
                     int textureWidth, int textureHeight, int logicalWidth, int logicalHeight,
                     int surfaceWidth, int surfaceHeight, long guestSequence, int legacyFilter) {
        if (textureWidth != logicalWidth || textureHeight != logicalHeight
                || logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalStateException("GLSLP needs an exact positive-sized source texture");
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            throw new IllegalStateException("GLSLP host viewport is unavailable");
        }
        try {
            int vw = Math.max(1, Math.round(Math.abs(legacyQuad.get(8) - legacyQuad.get(0))
                    * surfaceWidth / 2f));
            int vh = Math.max(1, Math.round(Math.abs(legacyQuad.get(1) - legacyQuad.get(5))
                    * surfaceHeight / 2f));
            boolean planChanged = cachedPlan == null || planSourceWidth != logicalWidth
                    || planSourceHeight != logicalHeight || planViewportWidth != vw
                    || planViewportHeight != vh;
            if (planChanged) {
                int[] maxTexture = new int[1];
                glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxTexture, 0);
                GlslpPreset.Plan updated = preset.plan(logicalWidth, logicalHeight,
                        vw, vh, maxTexture[0], 64L * 1024L * 1024L, fullNpot);
                cachedPlan = updated;
                planSourceWidth = logicalWidth;
                planSourceHeight = logicalHeight;
                planViewportWidth = vw;
                planViewportHeight = vh;
            }
            GlslpPreset.Plan plan = cachedPlan;
            boolean resized = false;
            for (int i = 0; i < plan.passes.size(); i++) {
                GlslpPreset.PlannedPass pass = plan.passes.get(i);
                if (pass.offscreen && (framebuffer[i] == 0
                        || targetWidth[i] != pass.outputWidth || targetHeight[i] != pass.outputHeight)) {
                    allocateIntermediate(i, pass.outputWidth, pass.outputHeight);
                    targetWidth[i] = pass.outputWidth;
                    targetHeight[i] = pass.outputHeight;
                    resized = true;
                }
            }
            boolean newFrame = planChanged || resized || guestSequence != processedSequence;
            int n = plan.passes.size();
            for (int i = 0; i < n; i++) {
                GlslpPreset.PlannedPass pass = plan.passes.get(i);
                if (pass.offscreen && !newFrame) continue;
                if (pass.offscreen) {
                    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[i]);
                    glViewport(0, 0, pass.outputWidth, pass.outputHeight);
                    glDisable(GL_SCISSOR_TEST);
                    setFullQuad();
                } else {
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glViewport(0, 0, surfaceWidth, surfaceHeight);
                    glEnable(GL_SCISSOR_TEST);
                    setGameQuad(legacyQuad);
                }
                int input = i == 0 ? sourceTexture : intermediate[i - 1];
                int prev2 = i == 1 ? sourceTexture : (i > 1 ? intermediate[i - 2] : 0);
                int prevW = i < 2 ? logicalWidth : targetWidth[i - 2];
                int prevH = i < 2 ? logicalHeight : targetHeight[i - 2];
                if (pass.pass.mipmapInput && newFrame) {
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, input);
                    glGenerateMipmap(GL_TEXTURE_2D);
                }
                drawPass(i, input, usesOriginal[i] ? sourceTexture : 0,
                        usesPrev2[i] ? prev2 : 0, pass.inputWidth, pass.inputHeight,
                        pass.outputWidth, pass.outputHeight,
                        passFilter(pass.pass.linearFilter, legacyFilter), legacyFilter,
                        logicalWidth, logicalHeight, prevW, prevH, guestSequence);
            }
            if (plan.finalBlitRequired) {
                GlslpPreset.PlannedPass last = plan.passes.get(n - 1);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, surfaceWidth, surfaceHeight);
                glEnable(GL_SCISSOR_TEST);
                setGameQuad(legacyQuad);
                drawPass(n, intermediate[n - 1], 0, 0,
                        last.outputWidth, last.outputHeight, vw, vh,
                        legacyFilter, legacyFilter, logicalWidth, logicalHeight, 0, 0,
                        guestSequence);
            }
            verifyGlError();
            processedSequence = guestSequence;
        } finally {
            // Return GL state required by the existing Canvas renderer.
            for (int i = 0; i < lookupIds.length + extraPassTextureUnits; i++) {
                glActiveTexture(GL_TEXTURE0 + 3 + i);
                glBindTexture(GL_TEXTURE_2D, 0);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, surfaceWidth, surfaceHeight);
            glEnable(GL_SCISSOR_TEST);
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sourceTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, legacyFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, legacyFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
    }

    private static void verifyGlError() {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("GLSLP GL error: 0x" + Integer.toHexString(error));
        }
    }

    private void requireHalfFloatFiltering(int sourcePass, int filter) {
        if (sourcePass >= 0 && sourcePass < preset.passes.size()
                && preset.passes.get(sourcePass).floatFramebuffer
                && filter == GL_LINEAR && !halfFloatLinear) {
            throw new IllegalStateException(
                    "GLSLP half-float linear filtering requires GL_OES_texture_half_float_linear");
        }
    }

    private static int passFilter(Boolean linear, int fallback) {
        return linear == null ? fallback : (linear ? GL_LINEAR : GL_NEAREST);
    }

    private void allocateIntermediate(int stage, int w, int h) {
        if (intermediate[stage] != 0) glDeleteTextures(1, intermediate, stage);
        if (framebuffer[stage] != 0) glDeleteFramebuffers(1, framebuffer, stage);
        intermediate[stage] = framebuffer[stage] = 0;
        glGenTextures(1, intermediate, stage);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, intermediate[stage]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (preset.passes.get(stage).floatFramebuffer) {
            glTexImage2D(GL_TEXTURE_2D, 0, halfFloatEs3
                    ? GL_RGBA16F_VALUE : GL_RGBA, w, h, 0,
                    GL_RGBA, halfFloatEs3 ? GL_HALF_FLOAT_VALUE
                            : GL_HALF_FLOAT_OES_VALUE, null);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, null);
        }
        glGenFramebuffers(1, framebuffer, stage);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[stage]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                intermediate[stage], 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("GLSLP FBO stage " + stage + " incomplete: 0x"
                    + Integer.toHexString(status));
        }
        verifyGlError();
    }

    private void setFullQuad() {
        quad.clear();
        // FBO texture coordinate y=0 addresses its BOTTOM row. The Android
        // source bitmap's row 0 was uploaded at texture y=0, so offscreen
        // draws must write source y=0 to the FBO bottom row as well.
        // The final host quad deliberately keeps its existing top-left UV
        // orientation; this makes both Texture and PassPrev2Texture agree.
        quad.put(FULL_QUAD);
        quad.rewind();
    }

    private void setGameQuad(FloatBuffer legacy) {
        quad.clear();
        for (int i = 0; i < 4; i++) {
            quad.put(legacy.get(i * 4)).put(legacy.get(i * 4 + 1)).put(0).put(1);
            quad.put(i >= 2 ? 1 : 0).put(i % 2 == 1 ? 1 : 0).put(0).put(1);
        }
        quad.rewind();
    }

    private void drawPass(int stage, int texture, int originalTexture, int prev2Texture,
                          int inputW, int inputH, int outputW, int outputH,
                          int filter, int fallbackFilter,
                          int originalW, int originalH, int prevW, int prevH,
                          long guestSequence) {
        glUseProgram(programs[stage]);
        if (stage < preset.passes.size()) {
            int divisor = preset.passes.get(stage).frameCountMod;
            int frameCount = divisor > 0
                    ? (int) Math.floorMod(guestSequence, (long) divisor)
                    : (int) guestSequence;
            uniform1i(stage, "FrameCount", frameCount);
            // The guest renderer has no rewind direction; the sequence is forward-only.
            uniform1i(stage, "FrameDirection", 1);
        }
        if (stage < lookupUniforms.length) {
            for (int i = 0; i < lookupIds.length; i++) {
                if (lookupUniforms[stage][i] < 0) continue;
                glActiveTexture(GL_TEXTURE0 + 3 + i);
                glBindTexture(GL_TEXTURE_2D, lookupIds[i]);
                glUniform1i(lookupUniforms[stage][i], 3 + i);
                String alias = preset.textures.get(i).alias;
                uniform2f(stage, alias + "Size", lookupWidths[i], lookupHeights[i]);
                uniform2f(stage, alias + "TextureSize", lookupWidths[i], lookupHeights[i]);
                uniform2f(stage, alias + "InputSize", lookupWidths[i], lookupHeights[i]);
            }
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        requireHalfFloatFiltering(stage - 1, filter);
        GlslpPreset.Pass pass = stage < preset.passes.size() ? preset.passes.get(stage) : null;
        boolean mipmap = pass != null && pass.mipmapInput;
        int minFilter = !mipmap ? filter
                : (filter == GL_LINEAR ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        applyWrap(pass == null ? null : pass.wrapMode);
        if (originalTexture != 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, originalTexture);
            int originalFilter = passFilter(preset.passes.get(0).linearFilter, fallbackFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, originalFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, originalFilter);
            uniform1i(stage, "OrigTexture", 1);
            uniform2f(stage, "OrigTextureSize", originalW, originalH);
            uniform2f(stage, "OrigInputSize", originalW, originalH);
        }
        if (prev2Texture != 0) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, prev2Texture);
            int previousFilter = passFilter(
                    preset.passes.get(stage - 1).linearFilter, fallbackFilter);
            requireHalfFloatFiltering(stage - 2, previousFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, previousFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, previousFilter);
            uniform1i(stage, "PassPrev2Texture", 2);
            uniform2f(stage, "PassPrev2TextureSize", prevW, prevH);
            uniform2f(stage, "PassPrev2InputSize", prevW, prevH);
        }
        if (stage < passReferences.size()) {
            for (PassReference reference : passReferences.get(stage)) {
                if (reference.unit >= 3 + lookupIds.length) {
                    glActiveTexture(GL_TEXTURE0 + reference.unit);
                    glBindTexture(GL_TEXTURE_2D, intermediate[reference.sourcePass]);
                    // filter_linearN describes sampling the output of pass N-1.
                    int sourceFilter = passFilter(
                            preset.passes.get(reference.sourcePass + 1).linearFilter,
                            fallbackFilter);
                    requireHalfFloatFiltering(reference.sourcePass, sourceFilter);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sourceFilter);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sourceFilter);
                }
                glUniform1i(reference.location, reference.unit);
                int w = targetWidth[reference.sourcePass];
                int h = targetHeight[reference.sourcePass];
                uniform2f(stage, reference.uniformName + "Size", w, h);
                uniform2f(stage, reference.uniformName + "InputSize", w, h);
            }
        }
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_BLEND);
        uniform1i(stage, "Texture", 0);
        if (pass != null && pass.legacyValues != null) {
            int location = uniformLocation(stage, "u_setting");
            if (location >= 0) {
                Arrays.fill(legacyUniformValues, 0f);
                for (int i = 0; i < Math.min(4, pass.legacyValues.size()); i++) {
                    legacyUniformValues[i] = pass.legacyValues.get(i);
                }
                glUniform4fv(location, 1, legacyUniformValues, 0);
            }
            uniform2f(stage, "u_texelDelta", 1f / inputW, 1f / inputH);
            uniform2f(stage, "u_pixelDelta", 1f / outputW, 1f / outputH);
        }
        uniform2f(stage, "InputSize", inputW, inputH);
        uniform2f(stage, "TextureSize", inputW, inputH);
        uniform2f(stage, "OutputSize", outputW, outputH);
        for (Map.Entry<String, Float> param : parameters.entrySet()) {
            uniform1f(stage, param.getKey(), param.getValue());
        }
        int matrix = uniformLocation(stage, "MVPMatrix");
        if (matrix >= 0) glUniformMatrix4fv(matrix, 1, false, IDENTITY, 0);
        int position = vertexAttributes[stage];
        int texcoord = textureAttributes[stage];
        if (position < 0) {
            throw new IllegalStateException("GLSLP position attribute missing");
        }
        quad.position(0);
        glVertexAttribPointer(position, 4, GL_FLOAT, false, 32, quad);
        glEnableVertexAttribArray(position);
        if (texcoord >= 0) {
            quad.position(4);
            glVertexAttribPointer(texcoord, 4, GL_FLOAT, false, 32, quad);
            glEnableVertexAttribArray(texcoord);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(position);
        if (texcoord >= 0) glDisableVertexAttribArray(texcoord);
    }

    private int uniformLocation(int stage, String name) {
        Map<String, Integer> locations = uniformLocations.get(stage);
        Integer cached = locations.get(name);
        if (cached != null) return cached;
        int location = glGetUniformLocation(programs[stage], name);
        locations.put(name, location); // Missing/optimized-out uniforms are cached as -1.
        return location;
    }

    private void uniform1i(int stage, String name, int value) {
        int location = uniformLocation(stage, name);
        if (location >= 0) glUniform1i(location, value);
    }

    private void uniform1f(int stage, String name, float value) {
        int location = uniformLocation(stage, name);
        if (location >= 0) glUniform1f(location, value);
    }

    private void uniform2f(int stage, String name, float x, float y) {
        int location = uniformLocation(stage, name);
        if (location >= 0) glUniform2f(location, x, y);
    }

    private static int link(String source, boolean useParameterUniforms) {
        // Enabling PARAMETER_UNIFORM requires binding ALL #pragma defaults,
        // not only the preset's explicitly overridden values.
        String prefix = useParameterUniforms ? "#define PARAMETER_UNIFORM\n" : "";
        // Libretro GL 1.30 compatibility shaders use GLES2 branches when
        // compiled without the desktop version directive.
        if (source.startsWith("#version 130\n")) {
            source = source.substring("#version 130\n".length());
        } else if (source.matches("(?s)^\\s*#version\\s+.*")) {
            throw new IllegalArgumentException("Unsupported desktop GLSL dialect");
        }
        int vertex = compile(GL_VERTEX_SHADER, "#define VERTEX\n" + prefix + source);
        int fragment = 0, program = 0;
        try {
            fragment = compile(GL_FRAGMENT_SHADER,
                    "#define FRAGMENT\n" + prefix + source);
            program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            int[] status = new int[1];
            glGetProgramiv(program, GL_LINK_STATUS, status, 0);
            if (status[0] != GL_TRUE) {
                throw new IllegalStateException("GLSLP link: "
                        + glGetProgramInfoLog(program));
            }
            return program;
        } catch (RuntimeException e) {
            if (program != 0) glDeleteProgram(program);
            throw e;
        } finally {
            glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        int[] status = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
        if (status[0] != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Libretro research compile: " + log);
        }
        return shader;
    }

    public void release() {
        glDeleteTextures(lookupIds.length, lookupIds, 0);
        glDeleteTextures(intermediate.length, intermediate, 0);
        glDeleteFramebuffers(framebuffer.length, framebuffer, 0);
        for (int program : programs) if (program != 0) glDeleteProgram(program);
        for (int i = 0; i < intermediate.length; i++) intermediate[i] = framebuffer[i] = 0;
        for (int i = 0; i < programs.length; i++) programs[i] = 0;
        for (int i = 0; i < lookupIds.length; i++) lookupIds[i] = 0;
    }
}
