// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import static android.opengl.GLES20.*;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Disposable R&D: genuine pinned Libretro Gauss horizontal/vertical GLSL
 * executing after the real Canvas.GLRenderer's guest texture publication.
 * Not a general GLSLP parser/engine. No host frame queue or temporal state.
 */
public final class LibretroTwoPassResearchPipeline {
    private static final float[] IDENTITY = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1
    };
    private final int[] programs = new int[2];
    private final int[] framebuffer = new int[1];
    private final int[] intermediate = new int[1];
    // R&D: xBRZ pass 1 reads the original source as PassPrev2Texture.
    private final boolean hasOriginalSourceInput;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(4 * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private int intermediateWidth;
    private int intermediateHeight;
    private long processedSequence = Long.MIN_VALUE;
    private int offscreenPassExecutions;

    public LibretroTwoPassResearchPipeline(String horizontal, String vertical) {
        try {
            programs[0] = link(horizontal);
            programs[1] = link(vertical);
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
            if (intermediateWidth != logicalWidth || intermediateHeight != logicalHeight) {
                allocateIntermediate(logicalWidth, logicalHeight);
            }
            if (processedSequence != guestSequence) {
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0]);
                glViewport(0, 0, logicalWidth, logicalHeight);
                glDisable(GL_SCISSOR_TEST);
                setFullQuad();
                drawPass(programs[0], sourceTexture, 0,
                        logicalWidth, logicalHeight, logicalWidth, logicalHeight,
                        hasOriginalSourceInput ? GL_NEAREST : GL_LINEAR);
                processedSequence = guestSequence;
                offscreenPassExecutions++;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, surfaceWidth, surfaceHeight);
            glEnable(GL_SCISSOR_TEST);
            int outputWidth = Math.max(1, Math.round(
                    Math.abs(legacyQuad.get(8) - legacyQuad.get(0)) * surfaceWidth / 2));
            int outputHeight = Math.max(1, Math.round(
                    Math.abs(legacyQuad.get(1) - legacyQuad.get(5)) * surfaceHeight / 2));
            setGameQuad(legacyQuad);
            drawPass(programs[1], intermediate[0], sourceTexture,
                    logicalWidth, logicalHeight, outputWidth, outputHeight,
                    hasOriginalSourceInput ? GL_NEAREST : GL_LINEAR);
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

    private void allocateIntermediate(int w, int h) {
        if (intermediate[0] != 0) glDeleteTextures(1, intermediate, 0);
        if (framebuffer[0] != 0) glDeleteFramebuffers(1, framebuffer, 0);
        intermediate[0] = framebuffer[0] = 0;
        intermediateWidth = intermediateHeight = 0;
        processedSequence = Long.MIN_VALUE;
        glGenTextures(1, intermediate, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, intermediate[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, null);
        glGenFramebuffers(1, framebuffer, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                intermediate[0], 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Research FBO incomplete: 0x"
                    + Integer.toHexString(status));
        }
        intermediateWidth = w;
        intermediateHeight = h;
    }

    private void setFullQuad() {
        quad.clear();
        quad.put(new float[] {
                -1,  1, 0, 1, 0, 0, 0, 1,
                -1, -1, 0, 1, 0, 1, 0, 1,
                 1,  1, 0, 1, 1, 0, 0, 1,
                 1, -1, 0, 1, 1, 1, 0, 1
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
                          int inputW, int inputH, int outputW, int outputH, int filter) {
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
            uniform2f(program, "PassPrev2TextureSize", inputW, inputH);
            uniform2f(program, "PassPrev2InputSize", inputW, inputH);
            glActiveTexture(GL_TEXTURE0);
        }
        glDisable(GL_BLEND);
        uniform1i(program, "Texture", 0);
        uniform2f(program, "InputSize", inputW, inputH);
        uniform2f(program, "TextureSize", inputW, inputH);
        uniform2f(program, "OutputSize", outputW, outputH);
        uniform1f(program, "SIGMA", 1f);
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

    private static int link(String source) {
        // Use original upstream #pragma defaults for this bounded R&D check.
        // A real resolver must expose named parameter values independently.
        int vertex = compile(GL_VERTEX_SHADER, "#define VERTEX\n" + source);
        int fragment = 0, program = 0;
        try {
            fragment = compile(GL_FRAGMENT_SHADER,
                    "#define FRAGMENT\n" + source);
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
        if (intermediate[0] != 0) glDeleteTextures(1, intermediate, 0);
        if (framebuffer[0] != 0) glDeleteFramebuffers(1, framebuffer, 0);
        for (int program : programs) if (program != 0) glDeleteProgram(program);
        intermediate[0] = framebuffer[0] = 0;
        programs[0] = programs[1] = 0;
    }
}
