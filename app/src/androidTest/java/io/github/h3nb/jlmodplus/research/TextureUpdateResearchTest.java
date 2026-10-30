// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.research;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Disposable R&D: GLUtils.texSubImage2D (reuse) and texImage2D (redefinition)
 * must produce equal pixels for same-size bitmap updates, including alpha.
 * This test makes no throughput or energy claim.
 */
@RunWith(AndroidJUnit4.class)
public final class TextureUpdateResearchTest {
    @Test public void subImageMatchesRedefinitionAndResizingReallocatesCorrectly() {
        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, d);
        assertTrue(EGL14.eglInitialize(d, new int[1], 0, new int[1], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        assertTrue(EGL14.eglChooseConfig(d, attrs, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext c = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, c);
        EGLSurface s = EGL14.eglCreatePbufferSurface(d, configs[0],
                new int[]{EGL14.EGL_WIDTH, 8, EGL14.EGL_HEIGHT, 8, EGL14.EGL_NONE}, 0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, s);
        assertTrue(EGL14.eglMakeCurrent(d, s, s, c));
        int[] tex = new int[2], fb = new int[1];
        Bitmap b = null;
        try {
            GLES20.glGenTextures(2, tex, 0);
            for (int id : tex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            GLES20.glGenFramebuffers(1, fb, 0);
            b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            for (int frame = 0; frame < 5; frame++) {
                fill(b, frame);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1]);
                if (frame == 0) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b);
                }
                assertArrayEquals("frame " + frame, read(tex[0], fb[0], 4, 4),
                        read(tex[1], fb[0], 4, 4));
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            }
            b.recycle();
            b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            fill(b, 7);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1]);
            // Size changed: a subimage update is NOT sufficient.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            assertArrayEquals("resized", read(tex[0], fb[0], 8, 8),
                    read(tex[1], fb[0], 8, 8));
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            if (b != null && !b.isRecycled()) b.recycle();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (fb[0] != 0) GLES20.glDeleteFramebuffers(1, fb, 0);
            if (tex[0] != 0) GLES20.glDeleteTextures(2, tex, 0);
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, c);
            EGL14.eglTerminate(d);
        }
    }

    private static void fill(Bitmap b, int frame) {
        for (int y = 0; y < b.getHeight(); y++)
            for (int x = 0; x < b.getWidth(); x++) {
                // A variation of color and alpha, not merely one solid color.
                int alpha = (x * 31 + y * 17 + frame * 37) & 255;
                int red = (x * 79 + frame * 29) & 255;
                int green = (y * 53 + frame * 41) & 255;
                int blue = (x * 23 + y * 19 + frame * 11) & 255;
                b.setPixel(x, y, Color.argb(alpha, red, green, blue));
            }
    }

    private static byte[] read(int texture, int fbo, int w, int h) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
        assertEquals(GLES20.GL_FRAMEBUFFER_COMPLETE,
                GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));
        ByteBuffer data = ByteBuffer.allocateDirect(w * h * 4);
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data);
        byte[] out = new byte[w * h * 4];
        data.position(0);
        data.get(out);
        return out;
    }
}
