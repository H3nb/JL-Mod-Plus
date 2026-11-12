// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.gles;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.h3nb.jlmodplus.config.EffectEntry;
import io.github.h3nb.jlmodplus.config.ShaderInfo;
import io.github.h3nb.jlmodplus.config.ShaderSelection;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LegacyNativeInputGlesTest {
    private static final int GAME = 8, VIEW = 32;

    @Test
    public void legacyShaderReceivesNativeInputOnHdWithoutEditingItsSource() throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
        EGLConfig[] config = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            assertTrue(EGL14.eglChooseConfig(display, attrs, 0, config, 0, 1, count, 0));
            assertTrue(count[0] > 0);
            context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            assertTrue(context != EGL14.EGL_NO_CONTEXT);
            surface = EGL14.eglCreatePbufferSurface(display, config[0],
                    new int[] {EGL14.EGL_WIDTH, VIEW, EGL14.EGL_HEIGHT, VIEW, EGL14.EGL_NONE}, 0);
            assertTrue(surface != EGL14.EGL_NO_SURFACE);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));

            File dir = new File(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getCacheDir(), "native-shader-test");
            assertTrue(dir.isDirectory() || dir.mkdirs());
            write(new File(dir, "native.vsh"),
                    "attribute vec2 a_position, a_texcoord0;\n"
                    + "uniform vec2 u_texelDelta;\n"
                    + "varying vec2 v_uv; varying float v_step;\n"
                    + "void main() { gl_Position = vec4(a_position, 0., 1.);"
                    + " v_uv = a_texcoord0; v_step = u_texelDelta.x; }\n");
            write(new File(dir, "native.fsh"),
                    "precision mediump float; uniform sampler2D sampler0;\n"
                    + "varying vec2 v_uv; varying float v_step;\n"
                    + "void main() { gl_FragColor = vec4(v_step * 8.,"
                    + " texture2D(sampler0, v_uv).g, 0., 1.); }\n");

            ShaderInfo info = new ShaderInfo("Fixture", "Test");
            info.vertex = "native.vsh";
            info.fragment = "native.fsh";
            ShaderSelection user = ShaderSelection.fromInfo(info);
            PostProcessRenderer compat = renderer(dir, user, true);
            PostProcessRenderer original = renderer(dir, user, false);
            try {
                int[] low = render(compat, 1);
                int[] high = render(compat, 4);
                int[] unmodified = render(original, 4);
                assertArrayEquals("Native proxy changed logical pixels", low, high);
                assertTrue("Native input uniform not propagated", high[0] >= 252);
                assertTrue("Original resolution contract changed", unmodified[0] < 100);
            } finally {
                compat.release();
                original.release();
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static PostProcessRenderer renderer(File folder, ShaderSelection selection,
                                                boolean nativeInput) throws IOException {
        EffectEntry effect = new EffectEntry("legacy", null, selection,
                Collections.emptyMap(), nativeInput);
        return new PostProcessRenderer(Collections.singletonList(effect), folder);
    }

    private static int[] render(PostProcessRenderer renderer, int scale) {
        int content = GAME * scale, size = content + 2;
        ByteBuffer image = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int green = x < content && y < content && (x / scale + y / scale) % 2 == 0
                    ? 255 : 0;
            image.put((byte) 0).put((byte) green).put((byte) 0).put((byte) 255);
        }
        image.flip();
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, image);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, VIEW, VIEW);
            GLES20.glScissor(0, 0, VIEW, VIEW);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            assertTrue(renderer.draw(texture[0], quad(), size, size,
                    content, content, GAME, GAME, VIEW, VIEW, GLES20.GL_NEAREST));
            ByteBuffer out = ByteBuffer.allocateDirect(VIEW * VIEW * 4);
            GLES20.glReadPixels(0, 0, VIEW, VIEW,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out);
            int[] values = new int[VIEW * VIEW * 2];
            for (int i = 0; i < VIEW * VIEW; i++) {
                values[2 * i] = out.get(4 * i) & 255;
                values[2 * i + 1] = out.get(4 * i + 1) & 255;
            }
            return values;
        } finally {
            GLES20.glDeleteTextures(1, texture, 0);
        }
    }

    private static FloatBuffer quad() {
        FloatBuffer buffer = ByteBuffer.allocateDirect(16 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(new float[]{-1, 1, 0, 0, -1, -1, 0, 1,
                1, 1, 1, 0, 1, -1, 1, 1}).rewind();
        return buffer;
    }

    private static void write(File file, String value) throws IOException {
        try (FileWriter writer = new FileWriter(file)) { writer.write(value); }
    }
}
