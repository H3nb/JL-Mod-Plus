// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import static android.opengl.GLES20.*;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disposable R&D: genuine pinned Libretro Gauss horizontal/vertical GLSL
 * executing after the real Canvas.GLRenderer's guest texture publication.
 * Not a general GLSLP parser/engine. No host frame queue or temporal state.
 */
public final class LibretroTwoPassResearchPipeline {
    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1
    };
    private final int[] programs = new int[3];
    private final int[] framebuffer = new int[2];
    private final int[] intermediate = new int[2];
    private final GlslpPresetResearch preset;
    // R&D: xBRZ pass 1 reads the original source as PassPrev2Texture.
    private final boolean hasOriginalSourceInput;
    private final Map<String, Float> parameters;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(4 * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int intermediateWidth;
    private int intermediateHeight;
    private int finalWidth;
    private int finalHeight;
    private long processedSequence = Long.MIN_VALUE;
    private long processedFinalSequence = Long.MIN_VALUE;
    private int offscreenPassExecutions;
    private int finalPassExecutions;

    public LibretroTwoPassResearchPipeline(String horizontal, String vertical) {
        this(horizontal, vertical, Collections.<String, Float>emptyMap());
    }

    /** Parameter mode is enabled only for this bounded Crisp research trial. */
    public LibretroTwoPassResearchPipeline(String horizontal, String vertical,
                                           Map<String, Float> resolvedParameters) {
        this(horizontal, vertical, resolvedParameters, null, null);
    }

    /** Executes exactly two already-resolved GLSLP passes; other features remain unsupported. */
    public LibretroTwoPassResearchPipeline(GlslpPresetResearch resolved, String stockSource)
            throws IOException {
        this(twoPassSource(resolved, 0), twoPassSource(resolved, 1),
                resolved.resolvedParameters(), resolved, stockSource);
    }

    private static String twoPassSource(GlslpPresetResearch value, int pass) {
        if (value == null || value.passes.size() != 2) {
            throw new IllegalArgumentException("R&D executor requires precisely two GLSL passes");
        }
        if (value.passes.get(pass).wrapMode != null
                && !"clamp_to_edge".equals(value.passes.get(pass).wrapMode)) {
            throw new IllegalArgumentException("R&D executor only supports clamp_to_edge");
        }
        return value.passes.get(pass).sourceText;
    }

    private LibretroTwoPassResearchPipeline(String horizontal, String vertical,
            Map<String, Float> resolvedParameters, GlslpPresetResearch resolved,
            String stockSource) {
        preset = resolved;
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedParameters));
        try {
            programs[0] = link(horizontal, !parameters.isEmpty());
            programs[1] = link(vertical, !parameters.isEmpty());
            if (preset != null) programs[2] = link(stockSource, false);
            hasOriginalSourceInput = glGetUniformLocation(programs[1], "PassPrev2Texture") >= 0;
        } catch (RuntimeException ex) {
            release();
            throw ex;
        }
    }

    /** Counts first-pass execution, not host-only presentation redraws. */
    public int offscreenPassExecutions() {
        return offscreenPassExecutions;
    }

    /** Counts the final GLSLP shader pass when it targets an FBO before stock presentation. */
    public int finalPassExecutions() {
        return finalPassExecutions;
    }

    public int finalOutputWidth() { return finalWidth; }
    public int finalOutputHeight() { return finalHeight; }

    /**
     * Real production quad uses (x,y,u,v) per vertex in the order LT LB RT RB.
     * This prototype deliberately rejects padded backing bitmaps: their
     * bilinear edge requires a separately proven source normalization policy.
     */
    public void draw(int sourceTexture, FloatBuffer legacyQuad,
                     int textureWidth, int textureHeight, int logicalWidth, int logicalHeight,
                     int surfaceWidth, int surfaceHeight, long guestSequence, int legacyFilter) {
        if (textureWidth != logicalWidth || textureHeight != logicalHeight) {
            throw new IllegalStateException("R&D GLSLP needs a tight guest texture, got "
                    + logicalWidth + "x" + logicalHeight + " inside "
                    + textureWidth + "x" + textureHeight);
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalStateException("Missing GL viewport/guest dimensions");
        }
        try {
            int outputWidth = Math.max(1, Math.round(
                    Math.abs(legacyQuad.get(8) - legacyQuad.get(0)) * surfaceWidth / 2));
            int outputHeight = Math.max(1, Math.round(
                    Math.abs(legacyQuad.get(1) - legacyQuad.get(5)) * surfaceHeight / 2));
            GlslpPresetResearch.Plan plan = null;
            if (preset != null) {
                int[] maxTextureSize = new int[1];
                glGetIntegerv(GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
                plan = preset.plan(logicalWidth, logicalHeight, outputWidth, outputHeight,
                        maxTextureSize[0], 64L * 1024L * 1024L);
            }
            int pass0W = plan == null ? logicalWidth : plan.passes.get(0).outputWidth;
            int pass0H = plan == null ? logicalHeight : plan.passes.get(0).outputHeight;
            int pass1W = plan == null ? outputWidth : plan.passes.get(1).outputWidth;
            int pass1H = plan == null ? outputHeight : plan.passes.get(1).outputHeight;
            boolean needsFinalBlit = plan != null && plan.finalBlitRequired;
            int filter0 = plan == null ? (hasOriginalSourceInput ? GL_NEAREST : GL_LINEAR)
                    : passFilter(plan.passes.get(0).pass.linearFilter, legacyFilter);
            int filter1 = plan == null ? (hasOriginalSourceInput ? GL_NEAREST : GL_LINEAR)
                    : passFilter(plan.passes.get(1).pass.linearFilter, legacyFilter);
            if (intermediateWidth != pass0W || intermediateHeight != pass0H) {
                allocateIntermediate(0, pass0W, pass0H);
                intermediateWidth = pass0W;
                intermediateHeight = pass0H;
                processedSequence = Long.MIN_VALUE;
                processedFinalSequence = Long.MIN_VALUE;
            }
            if (processedSequence != guestSequence) {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0]);
                glViewport(0, 0, pass0W, pass0H);
                glDisable(GL_SCISSOR_TEST);
                setFullQuad();
                drawPass(programs[0], sourceTexture, 0,
                        logicalWidth, logicalHeight, pass0W, pass0H, filter0,
                        logicalWidth, logicalHeight);
                processedSequence = guestSequence;
                processedFinalSequence = Long.MIN_VALUE;
                offscreenPassExecutions++;
            }

            if (needsFinalBlit) {
                if (finalWidth != pass1W || finalHeight != pass1H) {
                    allocateIntermediate(1, pass1W, pass1H);
                    finalWidth = pass1W;
                    finalHeight = pass1H;
                    processedFinalSequence = Long.MIN_VALUE;
                }
                if (processedFinalSequence != guestSequence) {
                    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[1]);
                    glViewport(0, 0, pass1W, pass1H);
                    glDisable(GL_SCISSOR_TEST);
                    setFullQuad();
                    drawPass(programs[1], intermediate[0],
                            hasOriginalSourceInput ? sourceTexture : 0,
                            pass0W, pass0H, pass1W, pass1H, filter1,
                            logicalWidth, logicalHeight);
                    processedFinalSequence = guestSequence;
                    finalPassExecutions++;
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, surfaceWidth, surfaceHeight);
                glEnable(GL_SCISSOR_TEST);
                setGameQuad(legacyQuad);
                // This R&D stock presentation uses the host's existing filter.
                // The pass-1 input filter is still applied independently above.
                drawPass(programs[2], intermediate[1], 0,
                        pass1W, pass1H, outputWidth, outputHeight, legacyFilter,
                        logicalWidth, logicalHeight);
            } else {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, surfaceWidth, surfaceHeight);
                glEnable(GL_SCISSOR_TEST);
                setGameQuad(legacyQuad);
                drawPass(programs[1], intermediate[0],
                        hasOriginalSourceInput ? sourceTexture : 0,
                        pass0W, pass0H, pass1W, pass1H, filter1,
                        logicalWidth, logicalHeight);
            }
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                throw new IllegalStateException("Libretro research GL error: 0x"
                        + Integer.toHexString(error));
            }
        } finally {
            // Restore host FB/viewport/scissor and the original source sampler
            // even on shader/FBO failure. The caller rebinds the legacy program.
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, surfaceWidth, surfaceHeight);
            glEnable(GL_SCISSOR_TEST);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, sourceTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, legacyFilter);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, legacyFilter);
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
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, null);
        glGenFramebuffers(1, framebuffer, stage);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[stage]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                intermediate[stage], 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Research FBO stage " + stage + " incomplete: 0x"
                    + Integer.toHexString(status));
        }
    }

    private void setFullQuad() {
        quad.clear();
        // FBO texture coordinate y=0 addresses its BOTTOM row. The Android
        // source bitmap's row 0 was uploaded at texture y=0, so offscreen
        // draws must write source y=0 to the FBO bottom row as well.
        // The final host quad deliberately keeps its existing top-left UV
        // orientation; this makes both Texture and PassPrev2Texture agree.
        quad.put(new float[] {
                -1,  1, 0, 1, 0, 1, 0, 1,
                -1, -1, 0, 1, 0, 0, 0, 1,
                 1,  1, 0, 1, 1, 1, 0, 1,
                 1, -1, 0, 1, 1, 0, 0, 1
        });
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

    private void drawPass(int program, int texture, int originalTexture,
                          int inputW, int inputH, int outputW, int outputH, int filter,
                          int originalW, int originalH) {
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        // The original texture is an independent source for xBRZ pass 1.
        // A zero sampler location is NOT evidence of unused binding; only -1 is.
        if (originalTexture != 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, originalTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            uniform1i(program, "PassPrev2Texture", 1);
            uniform2f(program, "PassPrev2TextureSize", originalW, originalH);
            uniform2f(program, "PassPrev2InputSize", originalW, originalH);
            glActiveTexture(GL_TEXTURE0);
        }
        glDisable(GL_BLEND);
        uniform1i(program, "Texture", 0);
        uniform2f(program, "InputSize", inputW, inputH);
        uniform2f(program, "TextureSize", inputW, inputH);
        uniform2f(program, "OutputSize", outputW, outputH);
        uniform1f(program, "SIGMA", 1f);
        for (Map.Entry<String, Float> param : parameters.entrySet()) {
            uniform1f(program, param.getKey(), param.getValue());
        }
        int matrix = glGetUniformLocation(program, "MVPMatrix");
        if (matrix >= 0) glUniformMatrix4fv(matrix, 1, false, IDENTITY, 0);
        int position = glGetAttribLocation(program, "VertexCoord");
        int texcoord = glGetAttribLocation(program, "TexCoord");
        if (position < 0 || texcoord < 0) {
            throw new IllegalStateException("Libretro research ABI attributes missing");
        }
        quad.position(0);
        glVertexAttribPointer(position, 4, GL_FLOAT, false, 32, quad);
        glEnableVertexAttribArray(position);
        quad.position(4);
        glVertexAttribPointer(texcoord, 4, GL_FLOAT, false, 32, quad);
        glEnableVertexAttribArray(texcoord);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(position);
        glDisableVertexAttribArray(texcoord);
    }

    private static void uniform1i(int program, String name, int value) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform1i(location, value);
    }

    private static void uniform1f(int program, String name, float value) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform1f(location, value);
    }

    private static void uniform2f(int program, String name, float x, float y) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform2f(location, x, y);
    }

    private static int link(String source, boolean useParameterUniforms) {
        // Enabling PARAMETER_UNIFORM requires binding ALL #pragma defaults,
        // not only the preset's explicitly overridden values.
        String prefix = useParameterUniforms ? "#define PARAMETER_UNIFORM\n" : "";
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
                throw new IllegalStateException("Libretro research link: "
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
        glDeleteTextures(intermediate.length, intermediate, 0);
        glDeleteFramebuffers(framebuffer.length, framebuffer, 0);
        for (int program : programs) if (program != 0) glDeleteProgram(program);
        for (int i = 0; i < intermediate.length; i++) intermediate[i] = framebuffer[i] = 0;
        for (int i = 0; i < programs.length; i++) programs[i] = 0;
    }
}
