// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.micro3d;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that the public Mascot texture role survives GLES upload and sampling.
 * JVM parser tests cannot verify the shader-visible RGB of transparent palette entries.
 */
@RunWith(AndroidJUnit4.class)
public class EnvironmentTextureGlesTest {
    private static final int SURFACE_SIZE = 4;
    private static final float TEXEL = 1.0f / 64.0f;

    @Test
    public void environmentPaletteZeroAndPaddingRemainBlackAfterGlesUpload() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertTrue(display != EGL14.EGL_NO_DISPLAY);
        int[] version = new int[2];
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        int[] configAttrs = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        int program = 0;
        int environmentId = 0;
        int modelId = 0;
        try {
            assertTrue(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0));
            assertTrue(numConfigs[0] > 0);
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {EGL14.EGL_WIDTH, SURFACE_SIZE,
                            EGL14.EGL_HEIGHT, SURFACE_SIZE, EGL14.EGL_NONE}, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));

            byte[] indexedBmp = indexedBmp2x2();
            com.mascotcapsule.micro3d.v3.Texture environment =
                    new com.mascotcapsule.micro3d.v3.Texture(indexedBmp, false);
            com.mascotcapsule.micro3d.v3.Texture model =
                    new com.mascotcapsule.micro3d.v3.Texture(indexedBmp, true);

            environmentId = environment.impl.getId();
            modelId = model.impl.getId();
            program = createProgram();
            GLES20.glUseProgram(program);
            GLES20.glViewport(0, 0, SURFACE_SIZE, SURFACE_SIZE);
            GLES20.glDisable(GLES20.GL_BLEND);

            int position = GLES20.glGetAttribLocation(program, "aPosition");
            FloatBuffer triangle = ByteBuffer.allocateDirect(6 * Float.BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            triangle.put(new float[] {-1, -1, 3, -1, -1, 3}).rewind();
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, triangle);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            int uv = GLES20.glGetUniformLocation(program, "uUv");

            // Palette entry 0 is red with alpha zero. The environment path
            // suppresses its RGB contribution; model texture semantics remain unchanged.
            assertRgb(0, 0, 0, sample(environmentId, uv, 0.5f * TEXEL, 0.5f * TEXEL, GLES20.GL_NEAREST));
            assertRgb(255, 255, 255, sample(environmentId, uv, 1.5f * TEXEL, 0.5f * TEXEL, GLES20.GL_NEAREST));
            assertRgb(0, 0, 0, sample(environmentId, uv, 32.5f * TEXEL, 32.5f * TEXEL, GLES20.GL_NEAREST));
            assertRgb(255, 0, 0, sample(modelId, uv, 0.25f, 0.25f, GLES20.GL_NEAREST));

            // Exercise optional source enhancement on the same immutable GLES texture.
            // UV coordinates still use the original 64×64 logical texture dimensions.
            TextureImpl.configureEnhancement(2);
            TextureImpl.setScreenTargetScale(2);
            assertEquals(modelId, model.impl.getId());
            assertRgb(255, 0, 0, sample(modelId, uv,
                    0.25f, 0.25f, GLES20.GL_NEAREST));
            TextureImpl.configureEnhancement(0);
            TextureImpl.setScreenTargetScale(1);

            // Linear filtering must not blend hidden palette[0] RGB into the highlight.
            int[] midpoint = sample(environmentId, uv, TEXEL, 0.5f * TEXEL, GLES20.GL_LINEAR);
            for (int color : midpoint) {
                assertTrue("Interpolated grayscale channel: " + color, color >= 123 && color <= 132);
            }
            GLES20.glDisableVertexAttribArray(position);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            TextureImpl.configureEnhancement(0);
            TextureImpl.setScreenTargetScale(1);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (environmentId != 0) GLES20.glDeleteTextures(1, new int[] {environmentId}, 0);
            if (modelId != 0) GLES20.glDeleteTextures(1, new int[] {modelId}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static int[] sample(int textureId, int uvLocation,
                                float u, float v, int filter) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glUniform2f(uvLocation, u, v);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(SURFACE_SIZE / 2, SURFACE_SIZE / 2, 1, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
        assertEquals("Sampling OpenGL error", GLES20.GL_NO_ERROR, GLES20.glGetError());
        return new int[] {pixel.get(0) & 0xff, pixel.get(1) & 0xff, pixel.get(2) & 0xff};
    }

    private static void assertRgb(int r, int g, int b, int[] actual) {
        assertEquals("red", r, actual[0]);
        assertEquals("green", g, actual[1]);
        assertEquals("blue", b, actual[2]);
    }

    private static int createProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 aPosition; void main(){gl_Position=vec4(aPosition,0.0,1.0);}");
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; uniform sampler2D uTexture; uniform vec2 uUv;"
                        + "void main(){gl_FragColor=vec4(texture2D(uTexture,uUv).rgb,1.0);}");
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        assertEquals(GLES20.glGetProgramInfoLog(program), GLES20.GL_TRUE, linked[0]);
        return program;
    }

    private static int compile(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        assertEquals(GLES20.glGetShaderInfoLog(shader), GLES20.GL_TRUE, compiled[0]);
        return shader;
    }

    private static byte[] indexedBmp2x2() {
        final int pixelOffset = 14 + 40 + 256 * 4;
        byte[] bytes = new byte[pixelOffset + 8];
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0x4D42);
        header.putInt(bytes.length);
        header.putInt(0);
        header.putInt(pixelOffset);
        header.putInt(40);
        header.putInt(2);
        header.putInt(-2); // Top-down BMP
        header.putShort((short) 1);
        header.putShort((short) 8);
        header.putInt(0);
        header.putInt(8);
        header.putInt(0);
        header.putInt(0);
        header.putInt(256);
        header.putInt(0);
        for (int i = 0; i < 256; i++) {
            header.put((byte) (i == 1 ? 255 : 0)); // B
            header.put((byte) (i == 1 ? 255 : 0)); // G
            header.put((byte) (i == 0 || i == 1 ? 255 : 0)); // R
            header.put((byte) 0);
        }
        bytes[pixelOffset] = 0;
        bytes[pixelOffset + 1] = 1;
        bytes[pixelOffset + 4] = 1;
        bytes[pixelOffset + 5] = 0;
        return bytes;
    }
}
