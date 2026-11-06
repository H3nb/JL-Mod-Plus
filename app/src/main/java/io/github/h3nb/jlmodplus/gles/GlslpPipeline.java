// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import static android.opengl.GLES20.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GLES2 presentation-only execution of bounded GLSLP multipass presets.
 * Uses the existing guest frame sequence; unsupported dependencies fail closed.
 */
public final class GlslpPipeline {
    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1
    };
    private final GlslpPreset preset;
    private final Map<String, Float> parameters;
    private final int[] programs, framebuffer, intermediate, targetWidth, targetHeight;
    private final boolean[] usesOriginal, usesPrev2;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(4 * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private long processedSequence = Long.MIN_VALUE;
    private GlslpPreset.Plan cachedPlan;
    private int planSourceWidth, planSourceHeight, planViewportWidth, planViewportHeight;

    public GlslpPipeline(GlslpPreset resolved, String stockSource) throws IOException {
        if (resolved == null || resolved.passes.isEmpty() || resolved.passes.size() > 16) {
            throw new IOException("GLSLP supports 1–16 passes in this prototype");
        }
        preset = resolved;
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(resolved.resolvedParameters()));
        int n = preset.passes.size();
        programs = new int[n + 1];
        framebuffer = new int[n];
        intermediate = new int[n];
        targetWidth = new int[n];
        targetHeight = new int[n];
        usesOriginal = new boolean[n];
        usesPrev2 = new boolean[n];
        try {
            for (int i = 0; i < n; i++) {
                GlslpPreset.Pass pass = preset.passes.get(i);
                if (pass.wrapMode != null && !"clamp_to_edge".equals(pass.wrapMode)) {
                    throw new IOException("Unsupported sampler wrap " + pass.wrapMode);
                }
                programs[i] = link(pass.sourceText, !parameters.isEmpty());
                usesOriginal[i] = glGetUniformLocation(programs[i], "OrigTexture") >= 0;
                usesPrev2[i] = glGetUniformLocation(programs[i], "PassPrev2Texture") >= 0;
                if (i == 0 && (usesOriginal[i] || usesPrev2[i])) {
                    throw new IOException("First pass cannot read preceding images");
                }
                for (String unsupported : new String[] {
                        "PrevTexture", "Prev1Texture", "Prev2Texture", "Prev3Texture",
                        "PassPrev3Texture", "PassPrev4Texture", "PassPrev5Texture",
                        "FeedbackTexture", "Pass1Texture", "Pass2Texture",
                        "FrameCount", "FrameDirection"
                }) {
                    if (glGetUniformLocation(programs[i], unsupported) >= 0) {
                        throw new IOException("Unsupported GLSLP dependency " + unsupported);
                    }
                }
            }
            programs[n] = link(stockSource, false);
        } catch (IOException | RuntimeException e) {
            release();
            throw e;
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
                        vw, vh, maxTexture[0], 64L * 1024L * 1024L);
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
                drawPass(programs[i], input, usesOriginal[i] ? sourceTexture : 0,
                        usesPrev2[i] ? prev2 : 0, pass.inputWidth, pass.inputHeight,
                        pass.outputWidth, pass.outputHeight,
                        passFilter(pass.pass.linearFilter, legacyFilter),
                        logicalWidth, logicalHeight, prevW, prevH);
            }
            if (plan.finalBlitRequired) {
                GlslpPreset.PlannedPass last = plan.passes.get(n - 1);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glViewport(0, 0, surfaceWidth, surfaceHeight);
                glEnable(GL_SCISSOR_TEST);
                setGameQuad(legacyQuad);
                drawPass(programs[n], intermediate[n - 1], 0, 0,
                        last.outputWidth, last.outputHeight, vw, vh,
                        legacyFilter, logicalWidth, logicalHeight, 0, 0);
            }
            verifyGlError();
            processedSequence = guestSequence;
        } finally {
            // Return GL state required by the existing Canvas renderer.
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
        }
    }

    private static void verifyGlError() {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("GLSLP GL error: 0x" + Integer.toHexString(error));
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
            throw new IllegalStateException("GLSLP FBO stage " + stage + " incomplete: 0x"
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

    private void drawPass(int program, int texture, int originalTexture, int prev2Texture,
                          int inputW, int inputH, int outputW, int outputH, int filter,
                          int originalW, int originalH, int prevW, int prevH) {
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        if (originalTexture != 0) {
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, originalTexture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            uniform1i(program, "OrigTexture", 1);
            uniform2f(program, "OrigTextureSize", originalW, originalH);
            uniform2f(program, "OrigInputSize", originalW, originalH);
        }
        if (prev2Texture != 0) {
            glActiveTexture(GL_TEXTURE2);
            glBindTexture(GL_TEXTURE_2D, prev2Texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            uniform1i(program, "PassPrev2Texture", 2);
            uniform2f(program, "PassPrev2TextureSize", prevW, prevH);
            uniform2f(program, "PassPrev2InputSize", prevW, prevH);
        }
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_BLEND);
        uniform1i(program, "Texture", 0);
        uniform2f(program, "InputSize", inputW, inputH);
        uniform2f(program, "TextureSize", inputW, inputH);
        uniform2f(program, "OutputSize", outputW, outputH);
        for (Map.Entry<String, Float> param : parameters.entrySet()) {
            uniform1f(program, param.getKey(), param.getValue());
        }
        int matrix = glGetUniformLocation(program, "MVPMatrix");
        if (matrix >= 0) glUniformMatrix4fv(matrix, 1, false, IDENTITY, 0);
        int position = glGetAttribLocation(program, "VertexCoord");
        int texcoord = glGetAttribLocation(program, "TexCoord");
        if (position < 0 || texcoord < 0) {
            throw new IllegalStateException("GLSLP attributes VertexCoord/TexCoord missing");
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
        glDeleteTextures(intermediate.length, intermediate, 0);
        glDeleteFramebuffers(framebuffer.length, framebuffer, 0);
        for (int program : programs) if (program != 0) glDeleteProgram(program);
        for (int i = 0; i < intermediate.length; i++) intermediate[i] = framebuffer[i] = 0;
        for (int i = 0; i < programs.length; i++) programs[i] = 0;
    }
}
