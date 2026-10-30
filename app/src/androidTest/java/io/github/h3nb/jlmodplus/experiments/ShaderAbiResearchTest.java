// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.experiments;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** R&D-only: compares the actual legacy shader asset ABI with a minimal, original
 * Libretro-style combined GLSL source. This does not implement or validate GLSLP. */
@RunWith(AndroidJUnit4.class)
public final class ShaderAbiResearchTest {
    private static final String RETRO =
            "#if defined(VERTEX)\n" +
            "attribute vec4 VertexCoord;\n" +
            "attribute vec4 TexCoord;\n" +
            "uniform mat4 MVPMatrix;\n" +
            "varying vec2 uv;\n" +
            "void main(){gl_Position=MVPMatrix*VertexCoord;uv=TexCoord.xy;}\n" +
            "#elif defined(FRAGMENT)\n" +
            "precision mediump float;\n" +
            "uniform sampler2D Texture;\n" +
            "uniform vec2 InputSize;\n" +
            "uniform vec2 TextureSize;\n" +
            "varying vec2 uv;\n" +
            "void main(){gl_FragColor=texture2D(Texture,uv*InputSize/TextureSize);}\n" +
            "#endif\n";

    @Test public void legacyAssetsAndLibretroStyleShaderPreserveFourPixelQuadrants()
            throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] cfg = new EGLConfig[1];
        int[] found = new int[1];
        int[] configAttrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue(EGL14.eglChooseConfig(display, configAttrs, 0, cfg, 0, 1, found, 0));
        assertTrue(found[0] > 0);
        EGLContext context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, cfg[0],
                new int[]{EGL14.EGL_WIDTH, 8, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));

        int texture = 0, legacy = 0, libretro = 0;
        try {
            Log.i("ShaderAbiRD", "Renderer: " + GLES20.glGetString(GLES20.GL_RENDERER));
            texture = fourQuadrants();
            legacy = link(readAsset("shaders/simple.vsh"), readAsset("shaders/simple.fsh"));
            libretro = link("#define VERTEX\n" + RETRO, "#define FRAGMENT\n" + RETRO);

            // The real shipped identity shader must still work under the old ABI.
            assertTrue(GLES20.glGetAttribLocation(legacy, "a_position") >= 0);
            assertTrue(GLES20.glGetAttribLocation(legacy, "a_texcoord0") >= 0);
            assertTrue(GLES20.glGetUniformLocation(legacy, "sampler0") >= 0);
            draw(legacy, texture, "a_position", "a_texcoord0", "sampler0", false);
            assertQuadrants();

            // A Libretro-style source uses different attribute/uniform names and
            // is compiled separately for VERTEX and FRAGMENT on the same GLES2 context.
            assertTrue(GLES20.glGetAttribLocation(libretro, "VertexCoord") >= 0);
            assertTrue(GLES20.glGetAttribLocation(libretro, "TexCoord") >= 0);
            assertTrue(GLES20.glGetUniformLocation(libretro, "Texture") >= 0);
            draw(libretro, texture, "VertexCoord", "TexCoord", "Texture", true);
            assertQuadrants();
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            if (legacy != 0) GLES20.glDeleteProgram(legacy);
            if (libretro != 0) GLES20.glDeleteProgram(libretro);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static String readAsset(String path) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getAssets().open(path)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int fourQuadrants() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int r = y < 2 ? (x < 2 ? 255 : 0) : (x < 2 ? 0 : 255);
                int g = x >= 2 ? 255 : 0;
                int b = y >= 2 ? 255 : 0;
                pixels.put((byte)r).put((byte)g).put((byte)b).put((byte)255);
            }
        }
        pixels.rewind();
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 4, 4,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        return ids[0];
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

    private static int link(String vs, String fs) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vs);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        assertEquals(GLES20.glGetProgramInfoLog(program), 1, ok[0]);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static void draw(int program, int texture,
                             String position, String texcoord, String sampler, boolean retro) {
        FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{
                -1, -1, 0, 0,
                 1, -1, 1, 0,
                -1,  1, 0, 1,
                 1,  1, 1, 1
        });
        vertices.rewind();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, 8, 8);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, sampler), 0);
        if (retro) {
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "MVPMatrix"), 1, false, identity, 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "InputSize"), 4, 4);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "TextureSize"), 4, 4);
        }
        int pos = GLES20.glGetAttribLocation(program, position);
        int uv = GLES20.glGetAttribLocation(program, texcoord);
        assertTrue(pos >= 0 && uv >= 0);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(pos);
        vertices.position(2);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glFinish(); // Readback synchronization in R&D only.
    }

    private static void assertQuadrants() {
        pixel(1, 1, 255, 0, 0);
        pixel(6, 1, 0, 255, 0);
        pixel(1, 6, 0, 0, 255);
        pixel(6, 6, 255, 255, 255);
    }

    private static void pixel(int x, int y, int r, int g, int b) {
        ByteBuffer out = ByteBuffer.allocateDirect(4);
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out);
        assertEquals("red " + x + "," + y, r, out.get(0) & 255);
        assertEquals("green " + x + "," + y, g, out.get(1) & 255);
        assertEquals("blue " + x + "," + y, b, out.get(2) & 255);
        assertEquals("alpha " + x + "," + y, 255, out.get(3) & 255);
    }
}
