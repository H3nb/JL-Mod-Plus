// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Disposable R&D: genuine pinned Libretro stock.glsl / bilinear.glslp,
 * logical-content UV crop, filtering at the backing-texture boundary,
 * and two-pass FBO/host-scissor composition. Not a production GLSLP loader.
 */
@RunWith(AndroidJUnit4.class)
public final class RealLibretroPresetResearchTest {
    private static final String ASSETS = "graphics-rd/";
    private static final float[] IDENTITY = {
            1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1
    };

    @Test public void realStockPresetShowsCropBleedAndFboIsolatesHostState()
            throws Exception {
        Properties preset = new Properties();
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(ASSETS + "bilinear.glslp")) {
            preset.load(input);
        }
        assertEquals("1", preset.getProperty("shaders"));
        assertEquals("stock.glsl", preset.getProperty("shader0"));
        assertEquals("true", preset.getProperty("filter_linear0"));
        String shader = readAsset(ASSETS + preset.getProperty("shader0"));

        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, d);
        assertTrue(EGL14.eglInitialize(d, new int[1], 0, new int[1], 0));
        EGLConfig[] cfg = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(d, attrs, 0, cfg, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext c = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, c);
        EGLSurface s = EGL14.eglCreatePbufferSurface(d, cfg[0],
                new int[]{EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 12, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, s);
        assertTrue(EGL14.eglMakeCurrent(d, s, s, c));

        int program = 0, source = 0, intermediate = 0, fbo = 0;
        try {
            Log.i("GraphicsRD", "REAL_GLSLP " + GLES20.glGetString(GLES20.GL_VERSION)
                    + " renderer=" + GLES20.glGetString(GLES20.GL_RENDERER));
            // Pinned original source, not a synthetic approximation of the Libretro ABI.
            program = link("#define VERTEX\n" + shader, "#define FRAGMENT\n" + shader);
            assertTrue(GLES20.glGetAttribLocation(program, "VertexCoord") >= 0);
            assertTrue(GLES20.glGetAttribLocation(program, "TexCoord") >= 0);
            assertTrue(GLES20.glGetUniformLocation(program, "Texture") >= 0);

            // Backing bitmap is 4x4 but only its left 2 columns are guest content.
            // The green right half represents undefined/inactive backing pixels.
            ByteBuffer data = ByteBuffer.allocateDirect(4 * 4 * 4);
            for (int i = 0; i < 16; i++) {
                boolean guest = (i % 4) < 2;
                data.put((byte)(guest ? 255 : 0)).put((byte)(guest ? 0 : 255))
                        .put((byte)0).put((byte)255);
            }
            data.rewind();
            source = texture(4, 4, data);
            intermediate = texture(2, 2, null);
            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            fbo = ids[0];

            // Existing host may have scissor/padding, and its game viewport is
            // smaller than the Android surface. First draw a real bilinear preset.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, source, 2, 2, 12, 8, 0.5f, GLES20.GL_LINEAR,
                    2, 4, 4, 4);
            pixel(0, 0, 0, 0, 255);
            pixel(4, 5, 255, 0, 0);
            ByteBuffer edge = readPixel(13, 5);
            int leakedGreen = edge.get(1) & 255;
            Log.i("GraphicsRD", "REAL_GLSLP edgeGreen=" + leakedGreen);
            assertTrue("Bilinear border samples inactive backing pixels", leakedGreen > 0);

            // Independent 2-pass path: source-relative target (2x2), then
            // game viewport (12x8). Host scissor must not clip an offscreen FBO.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, intermediate, 0);
            assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(1, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            draw(program, source, 0, 0, 2, 2, 0.5f, GLES20.GL_NEAREST,
                    2, 4, 4, 4);
            pixel(0, 0, 255, 0, 0);
            pixel(1, 1, 255, 0, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0, 0, 1, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(1, 1, 14, 10);
            draw(program, intermediate, 2, 2, 12, 8, 1.0f, GLES20.GL_LINEAR,
                    2, 2, 2, 2);
            pixel(4, 5, 255, 0, 0);
            pixel(13, 5, 255, 0, 0);
            pixel(0, 0, 0, 0, 255);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            if (source != 0) GLES20.glDeleteTextures(1, new int[]{source}, 0);
            if (intermediate != 0) GLES20.glDeleteTextures(1, new int[]{intermediate}, 0);
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, c);
            EGL14.eglTerminate(d);
        }
    }

    private static String readAsset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int texture(int w, int h, ByteBuffer pixels) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return id[0];
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        assertEquals(GLES20.glGetShaderInfoLog(shader), 1, ok[0]);
        return shader;
    }

    private static int link(String vertex, String fragment) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        assertEquals(GLES20.glGetProgramInfoLog(p), 1, ok[0]);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static void draw(int program, int texture, int x, int y, int w, int h,
                             float rightU, int filter, int inputW, int inputH,
                             int textureW, int textureH) {
        FloatBuffer vertices = ByteBuffer.allocateDirect(4 * 8 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{
                -1, -1, 0, 1, 0, 0, 0, 1,
                 1, -1, 0, 1, rightU, 0, 0, 1,
                -1,  1, 0, 1, 0, 1, 0, 1,
                 1,  1, 0, 1, rightU, 1, 0, 1
        }).rewind();
        GLES20.glViewport(x, y, w, h);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "Texture"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "MVPMatrix"),
                1, false, IDENTITY, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "InputSize"), inputW, inputH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "TextureSize"),
                textureW, textureH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "OutputSize"), w, h);
        int pos = GLES20.glGetAttribLocation(program, "VertexCoord");
        int uv = GLES20.glGetAttribLocation(program, "TexCoord");
        assertTrue(pos >= 0 && uv >= 0);
        GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 32, vertices);
        GLES20.glEnableVertexAttribArray(pos);
        vertices.position(4);
        GLES20.glVertexAttribPointer(uv, 4, GLES20.GL_FLOAT, false, 32, vertices);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glFinish(); // Research readback only; do not add to production.
    }

    private static ByteBuffer readPixel(int x, int y) {
        ByteBuffer out = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out);
        return out;
    }

    private static void pixel(int x, int y, int r, int g, int b) {
        ByteBuffer out = readPixel(x, y);
        assertEquals("r " + x + "," + y, r, out.get(0) & 255);
        assertEquals("g " + x + "," + y, g, out.get(1) & 255);
        assertEquals("b " + x + "," + y, b, out.get(2) & 255);
        assertEquals("a " + x + "," + y, 255, out.get(3) & 255);
    }
}
