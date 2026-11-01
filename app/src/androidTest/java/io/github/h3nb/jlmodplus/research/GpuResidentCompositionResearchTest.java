// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Discriminating prototype: CPU 2D -> GPU 3D-like draw -> CPU HUD via GL
 * textures, all composited in a GPU-owned offscreen attachment. No intermediate
 * GPU -> Android Bitmap readback. This is NOT a native M3G/Mascot test.
 */
@RunWith(AndroidJUnit4.class)
public final class GpuResidentCompositionResearchTest {
    private static final int W = 48, H = 32; // 24x16 guest at 2x output
    private static final String TAG = "GraphicsRD_GPUResident";
    private static final String VS =
            "attribute vec2 pos; varying vec2 uv;\n" +
            "void main() { gl_Position=vec4(pos,0.,1.); uv=vec2((pos.x+1.)*.5,1.-(pos.y+1.)*.5); }";
    private static final String FS =
            "precision mediump float; varying vec2 uv; uniform sampler2D tex;\n" +
            "void main() { gl_FragColor=texture2D(tex,uv); }";
    private static final float[] QUAD = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};

    private static int shader(int type, String code) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, code);
        GLES20.glCompileShader(id);
        int[] ok = {0};
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        assertEquals(GLES20.glGetShaderInfoLog(id), 1, ok[0]);
        return id;
    }

    private static int program() {
        int vs = shader(GLES20.GL_VERTEX_SHADER, VS);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, FS);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = {0};
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        assertEquals(GLES20.glGetProgramInfoLog(p), 1, ok[0]);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int texture(Bitmap bitmap) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (bitmap == null) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    W, H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        }
        return ids[0];
    }

    private static void blit(int program, int texture) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0);
        int attr = GLES20.glGetAttribLocation(program, "pos");
        FloatBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(QUAD).position(0);
        GLES20.glEnableVertexAttribArray(attr);
        GLES20.glVertexAttribPointer(attr, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(attr);
    }

    @Test public void cpuBackgroundGpuGeometryAndCpuHudComposeWithoutIntermediateReadback() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        assertTrue(EGL14.eglInitialize(display, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] found = new int[1];
        int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, found, 0));
        assertTrue(found[0] > 0);
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        int[] tex = new int[3], fbo = new int[1];
        int program = 0;
        Bitmap background = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap hud = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Bitmap oracle = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        try {
            background.eraseColor(Color.BLUE);
            hud.eraseColor(Color.TRANSPARENT);
            Paint green = new Paint();
            green.setColor(Color.GREEN);
            new Canvas(hud).drawRect(20, 8, 28, 14, green);
            Canvas cpu = new Canvas(oracle);
            cpu.drawColor(Color.BLUE);
            Paint red = new Paint();
            red.setColor(Color.RED);
            cpu.drawRect(14, 10, 30, 24, red);
            cpu.drawBitmap(hud, 0, 0, null);

            program = program();
            tex[0] = texture(background);
            tex[1] = texture(hud);
            tex[2] = texture(null);
            GLES20.glGenFramebuffers(1, fbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tex[2], 0);
            assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE, GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
            GLES20.glViewport(0, 0, W, H);
            GLES20.glDisable(GLES20.GL_BLEND);
            blit(program, tex[0]); // CPU 2D upload once; no GPU readback

            // GPU native geometry stand-in. GLES1 M3G/Mascot cross-context
            // interoperability, depth, clipping, and ownership are NOT proven.
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(14, H - 24, 16, 14);
            GLES20.glClearColor(1f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            blit(program, tex[1]); // CPU 2D HUD after GPU draw, still on GPU
            GLES20.glDisable(GLES20.GL_BLEND);
            ByteBuffer pixels = ByteBuffer.allocateDirect(W * H * 4);
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            int mismatches = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int index = ((H - 1 - y) * W + x) * 4;
                    int r = pixels.get(index) & 255;
                    int g = pixels.get(index + 1) & 255;
                    int b = pixels.get(index + 2) & 255;
                    int a = pixels.get(index + 3) & 255;
                    if (oracle.getPixel(x, y) != Color.argb(a, r, g, b)) mismatches++;
                }
            }
            assertEquals("GPU-resident mixed stages must match CPU oracle at all pixels",
                    0, mismatches);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            Log.i(TAG, "GPU-owned source texture remains FBO-attached: 48x32 full-frame parity; "
                    + "no intermediate glReadPixels, one final test-only readback");
        } finally {
            background.recycle(); hud.recycle(); oracle.recycle();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (fbo[0] != 0) GLES20.glDeleteFramebuffers(1, fbo, 0);
            if (program != 0) GLES20.glDeleteProgram(program);
            if (tex[0] != 0) GLES20.glDeleteTextures(3, tex, 0);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
