// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static android.opengl.GLES20.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.util.ContextHolder;

import io.github.h3nb.jlmodplus.config.EffectEntry;
import io.github.h3nb.jlmodplus.config.ShaderCatalog;
import io.github.h3nb.jlmodplus.config.ShaderSelection;
import io.github.h3nb.jlmodplus.util.FileUtils;

/**
 * OpenGL ES 2.0 post-processing on the owning GLSurfaceView thread.
 * Passes form a linear effect chain; an effect may retain its original input
 * for compositing (Bloom). Guest framebuffer and Android host UI stay untouched.
 */
public final class PostProcessRenderer {
    private static final String TAG = "PostProcessRenderer";
    private static final String ASSETS = "shaders/effects/";
    private static final int MAX_PASSES = 16;
    private static final int MAX_SOURCE_CHARS = 256 * 1024;

    private final ArrayList<Stage> stages = new ArrayList<>();
    private final ArrayList<EffectTargetPlan.Pass> planPasses = new ArrayList<>();
    private final ArrayList<Target> targets = new ArrayList<>();
    private final Map<String, Program> programs = new HashMap<>();
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final int maxTextureSize;
    private EffectTargetPlan framePlan;
    private int lastViewportWidth, lastViewportHeight;
    private int lastContentWidth, lastContentHeight;
    private int lastLogicalWidth, lastLogicalHeight;
    private final Program stock;

    private static final class Program {
        int id;
        int position, uv, input, base, inputSize, contentSize, outputSize;
        int texelDelta, pixelDelta, setting, baseScale, baseOffset;
        int gameSize, displayUvScale, displayUvOffset, contentUvMax;
        int baseSize, baseUvMax, blurCenter, blurWeights, blurOffsets;
        final Map<String, Integer> parameters = new HashMap<>();
    }

    private static final class Stage {
        final Program program;
        final EffectEntry entry;
        final EffectCatalog.Pass pass;
        final EffectCatalog.Effect definition;
        final boolean first;
        final float[] legacyValues;
        final float blurRadius;
        BlurKernel blurKernel;
        float cachedBlurScale = Float.NaN;

        Stage(Program program, EffectEntry entry, EffectCatalog.Pass pass,
              EffectCatalog.Effect definition, boolean first) {
            this.program = program;
            this.entry = entry;
            this.pass = pass;
            this.definition = definition;
            this.first = first;
            this.legacyValues = new float[4];
            float radius = 0f;
            if (definition != null && pass.fragment.startsWith("bloom-blur-")) {
                radius = 1f;
                for (EffectCatalog.Parameter parameter : definition.parameters) {
                    if ("uRadius".equals(parameter.id)) {
                        radius = effectiveParameter(entry, parameter);
                        break;
                    }
                }
            }
            this.blurRadius = radius;
            if (entry.user != null && entry.user.values != null) {
                for (int i = 0; i < Math.min(4, entry.user.values.size()); i++) {
                    Float value = entry.user.values.get(i);
                    if (value != null && !Float.isNaN(value) && !Float.isInfinite(value)) legacyValues[i] = value;
                }
            }
        }
    }

    /** CPU-side Gaussian weights: computed once, then uploaded as uniforms each frame. */
    private static final class BlurKernel {
        final float center;
        final float[] weights = new float[4];
        final float[] offsets = new float[4];

        BlurKernel(float radius) {
            // sigma=1.8 at Radius 1 approximates the previous default blur.
            // Fixed +/-8 texel support avoids gaps when the radius grows.
            double sigma = 1.8 * radius;
            double[] samples = new double[9];
            double sum = 0.0;
            for (int i = 0; i < samples.length; i++) {
                samples[i] = Math.exp(-0.5 * i * i / (sigma * sigma));
                sum += i == 0 ? samples[i] : 2.0 * samples[i];
            }
            center = (float) (samples[0] / sum);
            for (int i = 0; i < 4; i++) {
                int near = 2 * i + 1;
                int far = near + 1;
                double pair = samples[near] + samples[far];
                weights[i] = (float) (pair / sum);
                offsets[i] = (float) ((samples[near] * near + samples[far] * far) / pair);
            }
        }
    }

    private static float effectiveParameter(EffectEntry entry, EffectCatalog.Parameter parameter) {
        Float override = entry.parameters.get(parameter.id);
        float value = override != null && !Float.isNaN(override) && !Float.isInfinite(override)
                ? override : parameter.initial;
        return Math.max(parameter.minimum, Math.min(parameter.maximum, value));
    }

    private static final class Target {
        int texture, framebuffer;
    }

    public PostProcessRenderer(List<EffectEntry> configured, File shaderDirectory) throws IOException {
        int[] limit = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, limit, 0);
        maxTextureSize = limit[0];
        Program identity = null;
        try {
            String sharedVertex = asset("fullscreen.vsh");
            identity = program("builtin/identity", sharedVertex, asset("identity.fsh"));
            if (configured != null) for (EffectEntry entry : configured) {
                EffectCatalog.Effect definition = EffectCatalog.get(entry.builtinId);
                if (entry.builtinId != null && definition == null) {
                    throw new IOException("Unknown built-in effect: " + entry.builtinId);
                }
                if (definition != null) {
                    int index = 0;
                    for (EffectCatalog.Pass pass : definition.passes) {
                        Program p = program("builtin/" + pass.fragment, sharedVertex,
                                asset(pass.fragment));
                        stages.add(new Stage(p, entry, pass, definition,
                                index == 0));
                        index++;
                    }
                } else {
                    ShaderSelection user = entry.user;
                    if (user == null || user.vertex == null || user.fragment == null
                            || shaderDirectory == null) throw new IOException("Missing user shader");
                    String vert = local(shaderDirectory, user.vertex);
                    String frag = local(shaderDirectory, user.fragment);
                    Program p = program("user/" + user.vertex + "|" + user.fragment, vert, frag);
                    if (entry.nativeInput) {
                        EffectCatalog.Pass proxy = new EffectCatalog.Pass("identity.fsh",
                                EffectCatalog.Size.GAME, EffectCatalog.Sampling.NEAREST, false);
                        stages.add(new Stage(identity, entry, proxy, null, true));
                    }
                    stages.add(new Stage(p, entry, legacyPass(user), null, true));
                }
                if (stages.size() > MAX_PASSES) throw new IOException("Too many shader passes");
            }
            for (Stage stage : stages) {
                planPasses.add(new EffectTargetPlan.Pass(stage.pass.size, stage.first));
            }
        } catch (IOException | RuntimeException e) {
            release();
            throw e;
        }
        stock = identity;
    }

    private static String asset(String name) throws IOException {
        String text = ContextHolder.getAssetAsString(ASSETS + name);
        if (text == null || text.length() > MAX_SOURCE_CHARS) {
            throw new IOException("Missing or oversized bundled shader: " + name);
        }
        return text;
    }

    private static String local(File root, String name) throws IOException {
        File file = ShaderCatalog.resolveLegacySource(root, name);
        if (file == null || !file.isFile() || file.length() > MAX_SOURCE_CHARS) {
            throw new IOException("Unavailable user shader: " + name);
        }
        String source = FileUtils.getText(file.getPath());
        if (source == null || source.length() > MAX_SOURCE_CHARS) {
            throw new IOException("Unreadable user shader: " + name);
        }
        return source;
    }

    /**
     * Released legacy INI shaders drew directly to the display. Their historical
     * OutputResolution/Upscaling flags did not affect GLES sizing or sampling.
     */
    static EffectCatalog.Pass legacyPass(ShaderSelection user) {
        return new EffectCatalog.Pass(user.fragment, EffectCatalog.Size.VIEWPORT,
                EffectCatalog.Sampling.DISPLAY, false);
    }

    private Program program(String key, String vertex, String fragment) throws IOException {
        Program existing = programs.get(key);
        if (existing != null) return existing;
        int vert = compile(GL_VERTEX_SHADER, vertex);
        int frag = 0, id = 0;
        try {
            frag = compile(GL_FRAGMENT_SHADER, fragment);
            id = glCreateProgram();
            glAttachShader(id, vert);
            glAttachShader(id, frag);
            glLinkProgram(id);
            int[] linked = new int[1];
            glGetProgramiv(id, GL_LINK_STATUS, linked, 0);
            if (linked[0] != GL_TRUE) throw new IOException("Shader link: " + glGetProgramInfoLog(id));
            Program p = new Program();
            p.id = id;
            p.position = glGetAttribLocation(id, "a_position");
            p.uv = glGetAttribLocation(id, "a_texcoord0");
            if (p.position < 0) throw new IOException("Missing vertex position in " + key);
            p.input = glGetUniformLocation(id, "sampler0");
            p.base = glGetUniformLocation(id, "uBase");
            p.inputSize = glGetUniformLocation(id, "uInputSize");
            p.contentSize = glGetUniformLocation(id, "uContentSize");
            p.outputSize = glGetUniformLocation(id, "uOutputSize");
            p.texelDelta = glGetUniformLocation(id, "u_texelDelta");
            p.pixelDelta = glGetUniformLocation(id, "u_pixelDelta");
            p.setting = glGetUniformLocation(id, "u_setting");
            p.baseScale = glGetUniformLocation(id, "uBaseUvScale");
            p.baseOffset = glGetUniformLocation(id, "uBaseUvOffset");
            p.gameSize = glGetUniformLocation(id, "uGameSize");
            p.displayUvScale = glGetUniformLocation(id, "uDisplayUvScale");
            p.displayUvOffset = glGetUniformLocation(id, "uDisplayUvOffset");
            p.contentUvMax = glGetUniformLocation(id, "uContentUvMax");
            p.baseSize = glGetUniformLocation(id, "uBaseSize");
            p.baseUvMax = glGetUniformLocation(id, "uBaseUvMax");
            p.blurCenter = glGetUniformLocation(id, "uBlurCenter");
            p.blurWeights = glGetUniformLocation(id, "uBlurWeights");
            p.blurOffsets = glGetUniformLocation(id, "uBlurOffsets");
            programs.put(key, p);
            return p;
        } catch (IOException | RuntimeException e) {
            if (id != 0) glDeleteProgram(id);
            throw e;
        } finally {
            glDeleteShader(vert);
            if (frag != 0) glDeleteShader(frag);
        }
    }

    private static int compile(int type, String source) throws IOException {
        int shader = glCreateShader(type);
        if (shader == 0) throw new IOException("glCreateShader failed");
        glShaderSource(shader, source);
        glCompileShader(shader);
        int[] status = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
        if (status[0] != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IOException("Shader compile: " + log);
        }
        return shader;
    }

    public boolean isEmpty() { return stages.isEmpty(); }

    /**
     * @param displayQuad guest screen geometry in NDC, including the cropped original UVs.
     * @param logicalW guest width before any internal-resolution multiplier
     * @param logicalH guest height before any internal-resolution multiplier
     * @return true when a post-processing program presented the game image.
     */
    public boolean draw(int sourceTexture, FloatBuffer displayQuad,
                        int textureW, int textureH, int contentW, int contentH,
                        int logicalW, int logicalH, int hostW, int hostH, int filter) {
        if (stages.isEmpty() || hostW <= 0 || hostH <= 0) return false;
        if (textureW <= 0 || textureH <= 0 || contentW <= 0 || contentH <= 0
                || logicalW <= 0 || logicalH <= 0) return false;
        float left = displayQuad.get(0), right = displayQuad.get(8);
        float top = displayQuad.get(1), bottom = displayQuad.get(5);
        int width = Math.max(1, Math.round(Math.abs(right - left) * hostW * 0.5f));
        int height = Math.max(1, Math.round(Math.abs(top - bottom) * hostH * 0.5f));
        if (framePlan == null || width != lastViewportWidth || height != lastViewportHeight
                || contentW != lastContentWidth || contentH != lastContentHeight
                || logicalW != lastLogicalWidth || logicalH != lastLogicalHeight) {
            EffectTargetPlan next = EffectTargetPlan.create(planPasses, contentW, contentH,
                    logicalW, logicalH, width, height, maxTextureSize);
            clearTargets();
            framePlan = next;
            for (EffectTargetPlan.Slot slot : next.slots) {
                targets.add(createTarget(slot.width, slot.height));
            }
            lastViewportWidth = width;
            lastViewportHeight = height;
            lastContentWidth = contentW;
            lastContentHeight = contentH;
            lastLogicalWidth = logicalW;
            lastLogicalHeight = logicalH;
        }

        int current = sourceTexture, inW = textureW, inH = textureH;
        int visibleW = contentW, visibleH = contentH;
        double reducedScale = EffectTargetPlan.bloomScale(
                width, height, contentW, contentH);
        float uMax = (float) contentW / textureW;
        float vMax = (float) contentH / textureH;
        boolean flipped = false;
        int base = sourceTexture;
        float baseU = uMax, baseV = vMax;
        int baseW = inW, baseH = inH;
        boolean baseFlip = false;
        try {
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glDepthMask(false);
            for (int i = 0; i < stages.size(); i++) {
                Stage stage = stages.get(i);
                if (stage.first) {
                    reducedScale = EffectTargetPlan.bloomScale(
                            width, height, visibleW, visibleH);
                    base = current;
                    baseU = uMax;
                    baseV = vMax;
                    baseW = inW;
                    baseH = inH;
                    baseFlip = flipped;
                }
                int outW = width, outH = height;
                if (stage.pass.size == EffectCatalog.Size.REDUCED_VIEWPORT) {
                    outW = EffectTargetPlan.bloomSize(width, reducedScale);
                    outH = EffectTargetPlan.bloomSize(height, reducedScale);
                } else if (stage.pass.size == EffectCatalog.Size.INPUT) {
                    outW = visibleW;
                    outH = visibleH;
                } else if (stage.pass.size == EffectCatalog.Size.GAME) {
                    outW = logicalW;
                    outH = logicalH;
                }
                if (stage.blurRadius > 0f) {
                    // Keep the output-space blur width stable as the FBO resolution changes.
                    float footprint = stage.pass.fragment.equals("bloom-blur-h.fsh")
                            ? (float) outW / width : (float) outH / height;
                    float blurScale = 2f * footprint;
                    if (stage.blurKernel == null || stage.cachedBlurScale != blurScale) {
                        stage.blurKernel = new BlurKernel(stage.blurRadius * blurScale);
                        stage.cachedBlurScale = blurScale;
                    }
                }
                int destination = framePlan.destination(i);
                boolean direct = destination == EffectTargetPlan.DISPLAY;
                Target target = direct ? null : targets.get(destination);
                if (direct) {
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glViewport(0, 0, hostW, hostH);
                    glEnable(GL_SCISSOR_TEST);
                } else {
                    glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
                    glViewport(0, 0, outW, outH);
                    glDisable(GL_SCISSOR_TEST);
                }
                drawPass(stage.program, stage, current, inW, inH, visibleW, visibleH,
                        uMax, vMax, flipped, base, baseU, baseV, baseW, baseH, baseFlip,
                        outW, outH, logicalW, logicalH, hostW, hostH, filter,
                        direct ? left : -1f, direct ? right : 1f,
                        direct ? top : 1f, direct ? bottom : -1f);
                if (direct) return true;
                current = target.texture;
                inW = outW;
                inH = outH;
                visibleW = outW;
                visibleH = outH;
                uMax = vMax = 1f;
                flipped = true;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, hostW, hostH);
            glEnable(GL_SCISSOR_TEST);
            drawPass(stock, null, current, inW, inH, visibleW, visibleH,
                    uMax, vMax, flipped, 0, 1f, 1f, 1, 1, true, width, height,
                    logicalW, logicalH, hostW, hostH, filter, left, right, top, bottom);
            return true;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, hostW, hostH);
            glEnable(GL_SCISSOR_TEST);
            glActiveTexture(GL_TEXTURE0);
        }
    }

    private void drawPass(Program p, Stage stage, int texture, int inputW, int inputH,
                          int visibleW, int visibleH, float uMax, float vMax, boolean flipped,
                          int base, float baseU, float baseV, int baseW, int baseH,
                          boolean baseFlip,
                          int outW, int outH, int logicalW, int logicalH,
                          int hostW, int hostH, int filter,
                          float left, float right, float top, float bottom) {
        glUseProgram(p.id);
        float topUv = flipped ? vMax : 0f, bottomUv = flipped ? 0f : vMax;
        quad.clear();
        quad.put(left).put(top).put(0f).put(topUv);
        quad.put(left).put(bottom).put(0f).put(bottomUv);
        quad.put(right).put(top).put(uMax).put(topUv);
        quad.put(right).put(bottom).put(uMax).put(bottomUv);
        quad.position(0);
        glVertexAttribPointer(p.position, 2, GL_FLOAT, false, 16, quad);
        glEnableVertexAttribArray(p.position);
        if (p.uv >= 0) {
            quad.position(2);
            glVertexAttribPointer(p.uv, 2, GL_FLOAT, false, 16, quad);
            glEnableVertexAttribArray(p.uv);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        int sample = filter;
        if (stage != null) {
            if (stage.pass.sampling == EffectCatalog.Sampling.NEAREST) sample = GL_NEAREST;
            else if (stage.pass.sampling == EffectCatalog.Sampling.LINEAR) sample = GL_LINEAR;
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sample);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sample);
        if (p.input >= 0) glUniform1i(p.input, 0);
        if (p.inputSize >= 0) glUniform2f(p.inputSize, inputW, inputH);
        if (p.contentSize >= 0) glUniform2f(p.contentSize, visibleW, visibleH);
        if (p.outputSize >= 0) glUniform2f(p.outputSize, outW, outH);
        if (p.texelDelta >= 0) glUniform2f(p.texelDelta, 1f / inputW, 1f / inputH);
        if (p.pixelDelta >= 0) glUniform2f(p.pixelDelta, 1f / hostW, 1f / hostH);
        if (p.gameSize >= 0) glUniform2f(p.gameSize, logicalW, logicalH);
        if (p.displayUvScale >= 0) glUniform2f(p.displayUvScale,
                1f / uMax, flipped ? -1f / vMax : 1f / vMax);
        if (p.displayUvOffset >= 0) glUniform2f(p.displayUvOffset,
                0f, flipped ? 1f : 0f);
        if (p.contentUvMax >= 0) glUniform2f(p.contentUvMax, uMax, vMax);
        if (stage != null && stage.blurKernel != null) {
            BlurKernel kernel = stage.blurKernel;
            if (p.blurCenter >= 0) glUniform1f(p.blurCenter, kernel.center);
            if (p.blurWeights >= 0) glUniform4fv(p.blurWeights, 1, kernel.weights, 0);
            if (p.blurOffsets >= 0) glUniform4fv(p.blurOffsets, 1, kernel.offsets, 0);
        }
        if (stage != null) {
            if (p.setting >= 0) glUniform4fv(p.setting, 1, stage.legacyValues, 0);
            if (stage.definition != null) for (EffectCatalog.Parameter parameter : stage.definition.parameters) {
                float value = effectiveParameter(stage.entry, parameter);
                Integer location = p.parameters.get(parameter.id);
                if (location == null) {
                    location = glGetUniformLocation(p.id, parameter.id);
                    p.parameters.put(parameter.id, location);
                }
                if (location >= 0) glUniform1f(location, value);
            }
            if (stage.pass.composite) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_2D, base);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
                if (p.base >= 0) glUniform1i(p.base, 1);
                if (p.baseSize >= 0) glUniform2f(p.baseSize, baseW, baseH);
                if (p.baseUvMax >= 0) glUniform2f(p.baseUvMax, baseU, baseV);
                // The blurred input is a top-left-aligned framebuffer texture.
                if (p.baseScale >= 0) glUniform2f(p.baseScale, baseU, baseFlip ? baseV : -baseV);
                if (p.baseOffset >= 0) glUniform2f(p.baseOffset, 0f, baseFlip ? 0f : baseV);
            }
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(p.position);
        if (p.uv >= 0) glDisableVertexAttribArray(p.uv);
        glActiveTexture(GL_TEXTURE0);
    }

    private Target createTarget(int w, int h) {
        Target result = new Target();
        int[] id = new int[1];
        try {
            glGenTextures(1, id, 0);
            result.texture = id[0];
            if (result.texture == 0) throw new IllegalStateException("Cannot create effect texture");
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, result.texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new IllegalStateException("Effect texture allocation failed: GL error 0x"
                        + Integer.toHexString(error));
            }
            glGenFramebuffers(1, id, 0);
            result.framebuffer = id[0];
            if (result.framebuffer == 0) throw new IllegalStateException("Cannot create effect framebuffer");
            glBindFramebuffer(GL_FRAMEBUFFER, result.framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, result.texture, 0);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete post-processing framebuffer: 0x"
                        + Integer.toHexString(status));
            }
            error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new IllegalStateException("Effect framebuffer creation failed: GL error 0x"
                        + Integer.toHexString(error));
            }
            return result;
        } catch (RuntimeException e) {
            if (result.framebuffer != 0) glDeleteFramebuffers(1, new int[]{result.framebuffer}, 0);
            if (result.texture != 0) glDeleteTextures(1, new int[]{result.texture}, 0);
            throw e;
        } finally {
            // A failed allocation must not leave Canvas's stock fallback drawing offscreen.
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
    }

    private void clearTargets() {
        for (Target t : targets) {
            glDeleteTextures(1, new int[]{t.texture}, 0);
            glDeleteFramebuffers(1, new int[]{t.framebuffer}, 0);
        }
        targets.clear();
        framePlan = null;
    }

    public void release() {
        clearTargets();
        for (Program p : programs.values()) if (p.id != 0) glDeleteProgram(p.id);
        programs.clear();
        stages.clear();
        planPasses.clear();
    }
}
